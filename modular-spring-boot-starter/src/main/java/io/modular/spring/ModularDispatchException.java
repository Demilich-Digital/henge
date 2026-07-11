package io.modular.spring;

import org.springframework.http.HttpStatus;

class ModularDispatchException extends RuntimeException {

    private final HttpStatus status;

    ModularDispatchException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus getStatus() {
        return status;
    }
}
