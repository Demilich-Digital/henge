package digital.demilich.henge.spring;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import digital.demilich.henge.core.StoreUnavailableException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * One {@code service@version} as this process sees it, and what every injection point of it talks to
 * (through a proxy, see {@link HengeServiceInvocationHandler}): the same thing whether the
 * implementation is here, was refused its lease and is reached remotely, or is configured remote.
 * Callers hold the interface, never the implementation.
 *
 * <p>A call from a caller goes through the {@link ServiceCallInterceptor}s to the target
 * ({@link #call}). A call that arrived over {@code /_henge} is handed to the implementation directly
 * ({@link #invokeLocal}): the node that made it already ran the interceptors.
 *
 * <p>See "The service binding" in {@code docs/design/self-orchestration.md}.
 */
final class ServiceBinding {

    private static final Log log = LogFactory.getLog(ServiceBinding.class);

    /** Where a call ends up. */
    private interface Target {

        HengeMode mode();

        Object invoke(ServiceInvocation invocation) throws Throwable;
    }

    /**
     * The implementation here, and a count of the calls running in it, so it can be retired: once
     * {@link #close} has been called no call enters, and it returns when those that did have finished.
     */
    private static final class Local implements Target {

        private final Object implementation;
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile boolean closed;
        // Open channels are not calls in flight (that would hold a drain open for as long as they live),
        // so they are counted apart, and closed once the calls have drained.
        private final Set<ChannelSession> channels = ConcurrentHashMap.newKeySet();

        Local(Object implementation) {
            this.implementation = implementation;
        }

        Object implementation() {
            return implementation;
        }

        @Override
        public HengeMode mode() {
            return HengeMode.EMBEDDED;
        }

        /** Whether a call may run here; if so it must be matched by {@link #exit}. False once closed. */
        boolean enter() {
            inFlight.incrementAndGet();
            // Counted before the check, so a close that sees no calls running also keeps out any that
            // are about to start.
            if (closed) {
                exit();
                return false;
            }
            return true;
        }

        void exit() {
            if (inFlight.decrementAndGet() == 0 && closed) {
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        /** Keeps new calls out and waits for the running ones; whether they all finished within {@code timeout}. */
        synchronized boolean close(Duration timeout) throws InterruptedException {
            closed = true;
            long deadline = System.nanoTime() + timeout.toNanos();
            while (inFlight.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }

        /** Closes every open channel with {@code status}; each handler's {@code onClose} runs. */
        void closeChannels(CloseStatus status) {
            for (ChannelSession session : List.copyOf(channels)) {
                try {
                    session.close(status);
                } catch (RuntimeException e) {
                    log.warn("Closing a channel failed", e);
                }
            }
        }

        /** Runs a channel method: its last argument, the client's {@link Channel}, is replaced by a tracked one. */
        ChannelHandler openChannel(Method method, Object[] args) throws Throwable {
            Object[] callArgs = args.clone();
            int last = callArgs.length - 1;
            ChannelSession[] session = new ChannelSession[1];
            session[0] = new ChannelSession((Channel) callArgs[last], () -> channels.remove(session[0]));
            channels.add(session[0]);
            callArgs[last] = session[0].toClient();
            ChannelHandler handler;
            try {
                handler = (ChannelHandler) method.invoke(implementation, callArgs);
            } catch (InvocationTargetException e) {
                session[0].abandon();
                channels.remove(session[0]);
                throw e.getCause();
            }
            return session[0].attach(handler);
        }

        @Override
        public Object invoke(ServiceInvocation invocation) throws Throwable {
            Method method = invocation.method();
            // The proxy's own Method object, which can belong to a non-public interface (legal, and
            // callable directly): without this it can't be invoked reflectively from here.
            method.trySetAccessible();
            if (HengeServiceDescriptor.isChannelMethod(method)) {
                return openChannel(method, invocation.args());
            }
            try {
                return method.invoke(implementation, invocation.args());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * A leased service that couldn't be decided yet, because the datastore its lease is claimed from
     * couldn't be reached: not hosted here, and every call fails as the datastore being unavailable,
     * until {@link #becomeLocal} or {@link #becomeRemote}.
     */
    private record Pending(String serviceName, int serviceVersion) implements Target {

        @Override
        public HengeMode mode() {
            return HengeMode.EMBEDDED;
        }

        @Override
        public Object invoke(ServiceInvocation invocation) {
            throw new StoreUnavailableException("Henge service '" + serviceName + "' version " + serviceVersion
                    + " is waiting for the ephemeral store to say whether this process hosts it");
        }
    }

    private record Remote(String serviceName, ServiceTransport transport) implements Target {

        /** What opens a channel method: the transport, if it can. */
        private ChannelOpener channelOpener() {
            return transport instanceof ChannelOpener opener ? opener : null;
        }

        @Override
        public HengeMode mode() {
            return HengeMode.INTERNAL_REST;
        }

        @Override
        public Object invoke(ServiceInvocation invocation) {
            if (HengeServiceDescriptor.isChannelMethod(invocation.method())) {
                ChannelOpener opener = channelOpener();
                if (opener == null) {
                    throw new UnsupportedOperationException("Henge service '" + serviceName + "#" + invocation.methodName()
                            + "' opens a channel, and the transport can't open one");
                }
                Object[] args = invocation.args();
                return opener.open(invocation, (Channel) args[args.length - 1]);
            }
            try {
                return transport.invoke(invocation);
            } catch (RuntimeException e) {
                // Passes through unchanged -- not just RemoteServiceException itself, but also
                // whatever original exception type RemoteExceptionReconstructor managed to
                // reconstruct from the remote failure. Only checked exceptions from a transport
                // still get wrapped below, since those can't be reconstructed reliably anyway (see
                // RemoteExceptionReconstructor's Javadoc).
                throw e;
            } catch (Exception e) {
                throw new RemoteServiceException(
                        "Failed to invoke Henge service '" + serviceName + "#" + invocation.methodName() + "'", e);
            }
        }
    }

    private final String serviceName;
    private final int serviceVersion;
    private volatile Target target;
    private final Supplier<List<ServiceCallInterceptor>> interceptorSource;
    private volatile List<ServiceCallInterceptor> interceptors;

    private ServiceBinding(String serviceName, int serviceVersion, Target target,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.target = target;
        this.interceptorSource = interceptorSource;
    }

    /**
     * @param interceptorSource asked for the interceptors on the first call, not before: the beans that
     *     are interceptors needn't exist yet when the services are being created
     */
    static ServiceBinding local(String serviceName, int serviceVersion, Object implementation,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        return new ServiceBinding(serviceName, serviceVersion, new Local(implementation), interceptorSource);
    }

    static ServiceBinding remote(String serviceName, int serviceVersion, ServiceTransport transport,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        return new ServiceBinding(serviceName, serviceVersion, new Remote(serviceName, transport), interceptorSource);
    }

    /** A binding whose target is decided later, once the datastore can be reached: see {@link Pending}. */
    static ServiceBinding pending(String serviceName, int serviceVersion, Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        return new ServiceBinding(serviceName, serviceVersion, new Pending(serviceName, serviceVersion), interceptorSource);
    }

    /** A {@link #pending} binding is hosted here, by {@code implementation}. */
    synchronized void becomeLocal(Object implementation) {
        requirePending();
        target = new Local(implementation);
    }

    /** A {@link #pending} binding was refused its lease: it is reached through {@code transport}. */
    synchronized void becomeRemote(ServiceTransport transport) {
        requirePending();
        target = new Remote(serviceName, transport);
    }

    /**
     * A binding that was refused its lease is hosted here after all, by {@code implementation}, because
     * the lease has since been granted. Only for a binding that was refused: a retired one stays retired.
     */
    synchronized void rehost(Object implementation) {
        if (!(target instanceof Remote)) {
            throw new IllegalStateException("Henge service '" + serviceName + "' version " + serviceVersion + " is not reached remotely");
        }
        target = new Local(implementation);
    }

    private void requirePending() {
        if (!(target instanceof Pending)) {
            throw new IllegalStateException("Henge service '" + serviceName + "' version " + serviceVersion + " is already decided");
        }
    }

    String serviceName() {
        return serviceName;
    }

    int serviceVersion() {
        return serviceVersion;
    }

    /** Whether this process hosts the implementation: what {@code /_henge} may serve and advertise. */
    boolean isLocal() {
        return target instanceof Local;
    }

    /**
     * Stops hosting the implementation: new calls go to {@code transport} from now on, and this waits up
     * to {@code timeout} for the calls already running in the implementation. Returns whether they all
     * finished; the caller decides what to do with an implementation that is still busy. Does nothing,
     * and returns true, for a binding that isn't local. The implementation itself is the caller's to
     * destroy once this returns.
     */
    synchronized boolean retire(ServiceTransport transport, Duration timeout) throws InterruptedException {
        if (!(target instanceof Local local)) {
            return true;
        }
        // Switched before the wait, so a call that finds the implementation closed finds the new target.
        target = new Remote(serviceName, transport);
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean drained = local.close(timeout);
        closeChannels(local, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        return drained;
    }

    /** Tells every open channel the service is restarting (they reconnect elsewhere), within {@code timeout}. */
    private void closeChannels(Local local, Duration timeout) throws InterruptedException {
        Thread closing = Thread.ofVirtual().start(() ->
                local.closeChannels(new CloseStatus(CloseStatus.SERVICE_RESTART, "service " + serviceName + " is retiring")));
        closing.join(timeout);
        if (closing.isAlive()) {
            log.warn("Channels of " + serviceName + "@" + serviceVersion + " were still closing after " + timeout);
        }
    }

    /** A call from a caller in this process: through the interceptors, then to the target. */
    Object call(ServiceInvocation invocation) throws Throwable {
        return new Step(invocation, interceptors(), 0).proceed();
    }

    /**
     * A call that arrived over {@code /_henge}: straight to the implementation, with the same
     * exceptions {@link Method#invoke} throws.
     */
    Object invokeLocal(Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        if (!(target instanceof Local local) || !local.enter()) {
            throw new IllegalStateException("Henge service '" + serviceName + "' version " + serviceVersion
                    + " is not hosted by this process");
        }
        try {
            return method.invoke(local.implementation(), args);
        } finally {
            local.exit();
        }
    }

    /**
     * A channel opened by a client of this process's trunk: the implementation's channel method, given
     * {@code toClient} as the final argument of {@code args}. The same tracked open as one made by a
     * caller here, so retiring the service closes it too.
     */
    ChannelHandler openLocalChannel(Method method, Object[] args) throws Throwable {
        if (!(target instanceof Local local) || !local.enter()) {
            throw new IllegalStateException("Henge service '" + serviceName + "' version " + serviceVersion
                    + " is not hosted by this process");
        }
        try {
            return local.openChannel(method, args);
        } finally {
            local.exit();
        }
    }

    private Object invokeTarget(ServiceInvocation invocation) throws Throwable {
        while (true) {
            Target current = target;
            if (!(current instanceof Local local)) {
                return current.invoke(invocation);
            }
            if (local.enter()) {
                try {
                    return local.invoke(invocation);
                } finally {
                    local.exit();
                }
            }
            // Retired after this read: the target has already changed, so reading it again finds the new one.
        }
    }

    private List<ServiceCallInterceptor> interceptors() {
        List<ServiceCallInterceptor> current = interceptors;
        if (current == null) {
            current = List.copyOf(interceptorSource.get());
            interceptors = current;
        }
        return current;
    }

    /** The call from interceptor {@code index} on. Immutable, so an interceptor may {@code proceed} again to retry. */
    private final class Step implements ServiceCallInterceptor.Chain {

        private final ServiceInvocation invocation;
        private final List<ServiceCallInterceptor> chain;
        private final int index;

        Step(ServiceInvocation invocation, List<ServiceCallInterceptor> chain, int index) {
            this.invocation = invocation;
            this.chain = chain;
            this.index = index;
        }

        @Override
        public Object proceed() throws Throwable {
            if (index < chain.size()) {
                return chain.get(index).intercept(invocation, new Step(invocation, chain, index + 1));
            }
            return invokeTarget(invocation);
        }

        @Override
        public HengeMode mode() {
            return target.mode();
        }
    }
}
