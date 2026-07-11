package io.modular.core;

/**
 * Thrown client-side when a remote {@link ModularService} invocation fails —
 * either the transport itself failed (connection refused, timeout, ...) or the
 * remote process reported an error while executing the call.
 */
public class RemoteServiceException extends RuntimeException {

    public RemoteServiceException(String message) {
        super(message);
    }

    public RemoteServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
