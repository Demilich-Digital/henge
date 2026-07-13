package io.modular.spring;

import org.springframework.http.HttpStatus;

class ModularDispatchException extends RuntimeException {

    private final HttpStatus status;
    private final String remoteExceptionType;
    private final String remoteExceptionMessage;

    ModularDispatchException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    /**
     * Used only when {@code message} describes a genuine business-logic failure thrown by the
     * target method itself (as opposed to a dispatch-level failure like "no such service") —
     * {@code remoteExceptionType}/{@code remoteExceptionMessage} let the caller attempt to
     * reconstruct that original exception; see {@link RemoteExceptionReconstructor}.
     */
    ModularDispatchException(HttpStatus status, String message, String remoteExceptionType, String remoteExceptionMessage) {
        super(message);
        this.status = status;
        this.remoteExceptionType = remoteExceptionType;
        this.remoteExceptionMessage = remoteExceptionMessage;
    }

    HttpStatus getStatus() {
        return status;
    }

    String getRemoteExceptionType() {
        return remoteExceptionType;
    }

    String getRemoteExceptionMessage() {
        return remoteExceptionMessage;
    }
}
