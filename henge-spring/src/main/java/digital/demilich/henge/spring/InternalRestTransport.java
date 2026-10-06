package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client-side {@link ServiceTransport}: dispatches a {@code @HengeService} call as
 * {@code POST {baseUrl}{pathPrefix}/{service}/{version}/{method}} with a JSON array of
 * arguments, matching {@link HengeDispatcherController} on the receiving end.
 */
class InternalRestTransport implements ServiceTransport, ChannelOpener, AutoCloseable, BeanClassLoaderAware {

    private static final Log log = LogFactory.getLog(InternalRestTransport.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HengeProperties properties;

    /**
     * Where remote exception types are looked up: the application's own classloader (what loaded
     * the beans that threw them), not the loader of whichever interface declares the called method
     * -- that one is {@code null} for an inherited JDK interface method and can be a parent loader
     * that can't see the application's exception classes (e.g. under Spring Boot DevTools).
     */
    private volatile ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

    private final AdvertisedEndpoints advertisedEndpoints;
    private final SystemMetrics metrics;
    private final RetryPolicy retryPolicy;
    private final TrunkPool trunks;

    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, HengeProperties properties) {
        this(restClient, objectMapper, properties, null);
    }

    /** @param advertisedEndpoints where to look for a service with no configured url; null to not look */
    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, HengeProperties properties,
            AdvertisedEndpoints advertisedEndpoints) {
        this(restClient, objectMapper, properties, advertisedEndpoints, SystemMetrics.NONE);
    }

    /** @param metrics told when a call is retried, given up on, or its advertised host fails */
    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, HengeProperties properties,
            AdvertisedEndpoints advertisedEndpoints, SystemMetrics metrics) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.advertisedEndpoints = advertisedEndpoints;
        this.metrics = metrics;
        this.retryPolicy = properties.getRetryPolicy(); // validated at startup, not on the first failure
        requireKnownPlaceholders(properties.getRemoteUrlTemplate());
        properties.getServerPathPrefix(); // validated at startup, not on the first call
        this.trunks = new TrunkPool(properties.getChannelSettings(), properties.getConnectTimeout(),
                properties.getTransportSecret(), properties.getServerPathPrefix());
    }

    /** Closes the trunks, and so the channels on them. */
    @Override
    public void close() {
        trunks.close();
    }

    /**
     * Checked at startup: an unknown placeholder (say {@code {namespace}}) would otherwise fail every
     * call to every service that falls back to the template, and only when the call is made.
     */
    private static void requireKnownPlaceholders(String template) {
        if (template == null) {
            return;
        }
        Matcher placeholder = PLACEHOLDER.matcher(template);
        while (placeholder.find()) {
            if (!placeholder.group().equals("{service}") && !placeholder.group().equals("{version}")) {
                throw new IllegalStateException("henge.remote-url-template '" + template + "' contains "
                        + placeholder.group() + "; only {service} and {version} are substituted");
            }
        }
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    /** Where one attempt goes, and whether it was found among the advertisements (so another one can be tried). */
    private record Endpoint(String baseUrl, boolean advertised) {
    }

    private enum Undelivered {
        CONNECT("connect"), NOT_SERVED("not-served");

        /** As the meters tag it. */
        private final String reason;

        Undelivered(String reason) {
            this.reason = reason;
        }
    }

    /** A call that never ran on the remote, carrying the failure to throw if it isn't retried. */
    private static final class NotDelivered extends RuntimeException {
        private final transient Undelivered kind;
        private final transient RuntimeException failure;

        NotDelivered(Undelivered kind, RuntimeException failure) {
            super(failure.getMessage(), failure, false, false);
            this.kind = kind;
            this.failure = failure;
        }
    }

    @Override
    public Object invoke(ServiceInvocation invocation) {
        Endpoint endpoint = resolveEndpoint(invocation.serviceName(), invocation.serviceVersion());
        byte[] body = writeArguments(invocation);

        byte[] responseBody;
        for (int attempt = 1; ; attempt++) {
            try {
                responseBody = post(invocation, endpoint, body);
                break;
            } catch (NotDelivered notDelivered) {
                if (endpoint.advertised()) {
                    advertisedEndpoints.failed(invocation.serviceName(), invocation.serviceVersion(), endpoint.baseUrl());
                    metrics.endpointFailed(invocation.serviceName(), invocation.serviceVersion());
                }
                if (attempt >= retryPolicy.maxAttempts() || !retries(notDelivered.kind)) {
                    metrics.callGaveUp(invocation.serviceName(), invocation.serviceVersion(), notDelivered.kind.reason);
                    throw attempt == 1 ? notDelivered.failure : gaveUp(notDelivered, attempt);
                }
                metrics.callRetried(invocation.serviceName(), invocation.serviceVersion(), notDelivered.kind.reason);
                log.debug("Retrying " + invocation.serviceName() + "#" + invocation.methodName() + " after attempt " + attempt
                        + " (" + notDelivered.kind + " at " + endpoint.baseUrl() + ")");
                pause(retryPolicy.backoff(), notDelivered.failure);
                endpoint = resolveEndpoint(invocation.serviceName(), invocation.serviceVersion());
            }
        }

        Class<?> returnType = invocation.method().getReturnType();
        if (returnType == void.class || returnType == Void.class || responseBody == null || responseBody.length == 0) {
            return null;
        }
        // Read straight into the declared return type, not via a JsonNode tree, which would hold a
        // JSON decimal as a double and lose a BigDecimal's precision and scale.
        try {
            return objectMapper.readValue(responseBody, objectMapper.getTypeFactory().constructType(invocation.method().getGenericReturnType()));
        } catch (Exception e) {
            throw new RemoteServiceException("Failed to deserialize response from Henge service '"
                    + invocation.serviceName() + "#" + invocation.methodName() + "'", e);
        }
    }

    /**
     * Opens a channel on the trunk to a backend that hosts the service, found the way a call's is: its
     * configured url, else the template, else the advertisements. A backend that can't be reached is
     * marked failed, as for a call, and one more is tried if the service was found among the
     * advertisements. The caller gets the handler at once: a backend that then refuses the channel says
     * so by closing it, which reaches {@code toClient}.
     */
    @Override
    public ChannelHandler open(ServiceInvocation invocation, Channel toClient) {
        String service = invocation.serviceName();
        int version = invocation.serviceVersion();
        byte[] payload = TrunkOpen.write(objectMapper, service, version, invocation.methodName(), invocation.method(), invocation.args());
        Endpoint endpoint = openingEndpoint(service, version);
        for (int attempt = 1; ; attempt++) {
            try {
                return trunks.open(endpoint.baseUrl(), service, version, payload, toClient, retryElsewhere(service, version, endpoint));
            } catch (TrunkPool.TrunkUnavailable e) {
                if (endpoint.advertised()) {
                    advertisedEndpoints.failed(service, version, endpoint.baseUrl());
                    metrics.endpointFailed(service, version);
                }
                if (attempt >= 2 || !endpoint.advertised()) {
                    throw e;
                }
                endpoint = resolveEndpoint(service, version);
            }
        }
    }

    /**
     * Where to open a channel: found as for a call, except that when the datastore can't be reached to say,
     * a backend this process already has a trunk to, and has opened this service on, is used. Whatever is
     * open never needed the datastore, and an open can do without it as long as a backend is known; with
     * none it fails as the datastore being unavailable.
     */
    private Endpoint openingEndpoint(String service, int version) {
        try {
            return resolveEndpoint(service, version);
        } catch (StoreUnavailableException e) {
            String live = trunks.liveTrunkServing(service, version);
            if (live == null) {
                throw e;
            }
            return new Endpoint(live, false);
        }
    }

    /**
     * What to do when the backend a channel was opened on says it doesn't host the service (a {@code 404}:
     * nothing happened): mark it failed and use another advertised one. Only a backend that was found among
     * the advertisements has another to try.
     */
    private TrunkPool.Retry retryElsewhere(String service, int version, Endpoint endpoint) {
        if (!endpoint.advertised()) {
            return null;
        }
        return failedBaseUrl -> {
            advertisedEndpoints.failed(service, version, failedBaseUrl);
            metrics.endpointFailed(service, version);
            try {
                String next = resolveEndpoint(service, version).baseUrl();
                return next.equals(failedBaseUrl) ? null : next;
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    private boolean retries(Undelivered kind) {
        return switch (kind) {
            case CONNECT -> retryPolicy.onConnectFailure();
            case NOT_SERVED -> retryPolicy.onNotServed();
        };
    }

    /**
     * What to throw once the attempts are used up: a transport failure says how many times it was tried;
     * an exception rebuilt from a {@code 404} is thrown as it came, so a caller still catches its own type.
     */
    private static RuntimeException gaveUp(NotDelivered notDelivered, int attempts) {
        if (notDelivered.failure instanceof RemoteServiceException transportFailure) {
            return new RemoteServiceException(transportFailure.getMessage() + " (gave up after " + attempts + " attempts)",
                    transportFailure);
        }
        return notDelivered.failure;
    }

    private static void pause(java.time.Duration backoff, RuntimeException failure) {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** An explicit url, else the template, else a process that advertises the service. */
    private Endpoint resolveEndpoint(String serviceName, int serviceVersion) {
        boolean advertised = false;
        String url = properties.service(serviceName).resolveUrl(serviceVersion);
        if (url == null || url.isBlank()) {
            url = resolveFromTemplate(serviceName, serviceVersion);
        }
        if ((url == null || url.isBlank()) && advertisedEndpoints != null) {
            url = advertisedEndpoints.next(serviceName, serviceVersion);
            advertised = url != null;
        }
        if (url == null || url.isBlank()) {
            throw new RemoteServiceException("No url configured for Henge service '" + serviceName
                    + "' version '" + serviceVersion + "', and no process advertises it (set henge.services."
                    + serviceName + ".url, henge.services." + serviceName + ".versions."
                    + serviceVersion + ".url for a per-version override, henge.remote-url-template for a "
                    + "shared convention, or run a process that hosts it with henge.advertise.url set)");
        }
        // "http://audit:8080/" is a natural way to write a base URL; without this it would produce
        // "//_henge/...", which the server doesn't route.
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new Endpoint(url, advertised);
    }

    /**
     * One attempt. A failure that means the call never ran on the remote is a {@link NotDelivered}, for
     * {@link #invoke} to retry or give up on; anything else is final.
     */
    private byte[] post(ServiceInvocation invocation, Endpoint endpoint, byte[] body) {
        String endpointUrl = endpoint.baseUrl() + properties.getServerPathPrefix() + "/" + invocation.serviceName() + "/"
                + invocation.serviceVersion() + "/" + invocation.methodName();
        // A URI, not a String: RestClient would treat a String as a URI template and try to expand
        // any '{...}' in it.
        URI uri;
        try {
            uri = URI.create(endpointUrl);
        } catch (IllegalArgumentException e) {
            throw new RemoteServiceException("Invalid url '" + endpointUrl + "' for Henge service '" + invocation.serviceName()
                    + "' version '" + invocation.serviceVersion() + "' -- check henge.services." + invocation.serviceName()
                    + ".url (or its per-version override) and henge.remote-url-template", e);
        }

        String secret = properties.getTransportSecret();
        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (secret != null && !secret.isBlank()) {
                            headers.set(HengeDispatcherController.SECRET_HEADER, secret);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            RemoteServiceException fallback = new RemoteServiceException("Henge service call failed: " + invocation.serviceName() + "#"
                    + invocation.methodName() + " -> " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            RuntimeException failure = RemoteExceptionReconstructor.reconstruct(
                    e.getResponseBodyAsString(), beanClassLoader, objectMapper, fallback);
            // A 404 is defined to mean nothing happened (see @ErrorStatus), so it may be tried again.
            if (e.getStatusCode().value() == 404) {
                throw new NotDelivered(Undelivered.NOT_SERVED, failure);
            }
            throw failure;
        } catch (RestClientException e) {
            // The immediate exception's own message is often a generic wrapper (e.g. "Error while
            // extracting response..."); the actually-useful detail -- "connect timed out",
            // "Read timed out" -- is on the root cause.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String message = "Henge service call failed: " + invocation.serviceName() + "#" + invocation.methodName()
                    + " (" + root.getMessage() + ")";
            if (isConnectFailure(e)) {
                throw new NotDelivered(Undelivered.CONNECT, new RemoteServiceException(message, e));
            }
            throw new RemoteServiceException(message, e);
        }
    }

    /**
     * The connection was never made, so nothing ran: refused, unknown host, no route, or a connect (not
     * read) timeout. A read timeout, a reset, or anything else after the connection exists says nothing
     * about whether the remote ran the call, so it isn't one.
     */
    static boolean isConnectFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || t instanceof PortUnreachableException) {
                return true;
            }
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The arguments as a JSON array, each written by the transport mapper as its declared parameter
     * type -- and sent as bytes, so RestClient's own message converters (and their ObjectMapper)
     * never touch them.
     */
    private byte[] writeArguments(ServiceInvocation invocation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = objectMapper.createGenerator(out)) {
            ServiceArguments.write(objectMapper, invocation.method(), invocation.args(), invocation.args().length, generator);
        } catch (IOException e) {
            throw new RemoteServiceException("Failed to serialize arguments for Henge service '"
                    + invocation.serviceName() + "#" + invocation.methodName() + "'", e);
        }
        return out.toByteArray();
    }

    /**
     * Fallback used when a service has no explicit {@code url} configured: substitutes
     * {@code {service}} and {@code {version}} in {@code henge.remote-url-template} with the
     * service's name and version, e.g. {@code http://{service}.default.svc.cluster.local:8080} ->
     * {@code http://audit-service.default.svc.cluster.local:8080}, or
     * {@code http://{service}-v{version}.default.svc.cluster.local:8080} for a per-version split.
     * {@code {service}}-only templates keep working unchanged, since {@code {version}} substitution
     * is a no-op when the placeholder isn't present. Package-private (rather than {@code private})
     * so it's directly, deterministically testable without needing a real HTTP call.
     */
    String resolveFromTemplate(String serviceName, int serviceVersion) {
        return expandTemplate(properties.getRemoteUrlTemplate(), serviceName, serviceVersion);
    }

    /** {@code template} with its placeholders filled in; {@code null} if there is no template. */
    static String expandTemplate(String template, String serviceName, int serviceVersion) {
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("{service}", serviceName).replace("{version}", String.valueOf(serviceVersion));
    }
}
