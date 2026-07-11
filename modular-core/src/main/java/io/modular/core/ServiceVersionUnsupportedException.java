package io.modular.core;

/**
 * Thrown by a generated {@code {Interface}Skeleton} stub method — reached when a
 * {@code @ServiceVersion} implementation didn't override a method outside the version range it
 * declared support for (via {@link AddedIn} / {@link DeprecatedSince}).
 */
public class ServiceVersionUnsupportedException extends RuntimeException {

    public ServiceVersionUnsupportedException(String message) {
        super(message);
    }
}
