package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.RemoteServiceException;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link RemoteExceptionReconstructor}, exercised without any HTTP
 * involved -- see {@link ModularDispatchPlainSpringTest} for the real end-to-end round trip this
 * is meant to support.
 */
class RemoteExceptionReconstructorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClassLoader classLoader = getClass().getClassLoader();

    @Test
    void reconstructsAKnownRuntimeExceptionWithChainedCause() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");
        String body = "{\"error\":\"...\",\"exceptionType\":\"java.lang.IllegalStateException\",\"exceptionMessage\":\"quota exceeded\"}";

        RuntimeException result = RemoteExceptionReconstructor.reconstruct(body, classLoader, objectMapper, fallback);

        assertThat(result).isInstanceOf(IllegalStateException.class).hasMessage("quota exceeded");
        assertThat(result.getCause()).isSameAs(fallback);
    }

    @Test
    void fallsBackWhenExceptionTypeFieldIsAbsent() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");
        String body = "{\"error\":\"not found\"}";

        RuntimeException result = RemoteExceptionReconstructor.reconstruct(body, classLoader, objectMapper, fallback);

        assertThat(result).isSameAs(fallback);
    }

    @Test
    void fallsBackWhenClassDoesNotExist() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");
        String body = "{\"exceptionType\":\"com.example.NoSuchException\",\"exceptionMessage\":\"boom\"}";

        RuntimeException result = RemoteExceptionReconstructor.reconstruct(body, classLoader, objectMapper, fallback);

        assertThat(result).isSameAs(fallback);
    }

    @Test
    void fallsBackWhenClassIsNotARuntimeException() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");
        String body = "{\"exceptionType\":\"java.lang.String\",\"exceptionMessage\":\"boom\"}";

        RuntimeException result = RemoteExceptionReconstructor.reconstruct(body, classLoader, objectMapper, fallback);

        assertThat(result).isSameAs(fallback);
    }

    @Test
    void fallsBackWhenNoCompatibleConstructorExists() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");
        // NoStringConstructorException below has no (String) constructor.
        String body = "{\"exceptionType\":\"digital.demilich.henge.spring.RemoteExceptionReconstructorTest$NoStringConstructorException\","
                + "\"exceptionMessage\":\"boom\"}";

        RuntimeException result = RemoteExceptionReconstructor.reconstruct(body, classLoader, objectMapper, fallback);

        assertThat(result).isSameAs(fallback);
    }

    @Test
    void fallsBackOnMalformedResponseBody() {
        RemoteServiceException fallback = new RemoteServiceException("fallback");

        RuntimeException result = RemoteExceptionReconstructor.reconstruct("not json at all", classLoader, objectMapper, fallback);

        assertThat(result).isSameAs(fallback);
    }

    static class NoStringConstructorException extends RuntimeException {
        NoStringConstructorException() {
            super("no-arg only");
        }
    }
}
