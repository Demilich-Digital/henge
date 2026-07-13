package io.modular.spring.fixture.echo;

/** A custom (non-JDK) unchecked exception, used to prove reconstruction round-trips real business exceptions, not just builtins. */
public class EchoFailureException extends RuntimeException {

    public EchoFailureException(String message) {
        super(message);
    }
}
