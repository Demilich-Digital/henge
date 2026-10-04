package digital.demilich.henge.core;

/**
 * Thrown client-side when a connection to the process hosting a service couldn't be made (refused,
 * unknown host, no route, or a connect timeout), so the call was <em>not run</em> and is safe to make
 * again whatever the method does. A failure after the connection exists (a read timeout, a reset) is
 * a plain {@link RemoteServiceException}: it says nothing about whether the remote ran the call.
 */
public class ServiceUnreachableException extends RemoteServiceException {

    public ServiceUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
