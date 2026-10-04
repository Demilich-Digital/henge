package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceInvocation;

/**
 * Wraps every call a caller makes to a {@code @ModularService}, embedded or remote alike: the one place
 * to observe or alter a call without knowing how it is fulfilled. Beans of this type are picked up from
 * the application context and run in {@code @Order} (lowest outermost), around the call's target.
 *
 * <p>It sees calls from this process's callers only. A call that arrives over {@code /_modular} was
 * already seen on the node that made it, and is handed to the implementation directly.
 */
@FunctionalInterface
public interface ServiceCallInterceptor {

    /**
     * Carries out the call, by calling {@link Chain#proceed()} (once, or not at all, or several times) and
     * returning or throwing what the caller should see.
     */
    Object intercept(ServiceInvocation invocation, Chain chain) throws Throwable;

    /** The rest of the call: the interceptors after this one, then the target. */
    interface Chain {

        /** Runs the rest of the call. Throws what the target threw, as the caller would see it. */
        Object proceed() throws Throwable;

        /** How this call is fulfilled: in this process, or over the transport. */
        ModularMode mode();
    }
}
