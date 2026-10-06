package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import org.springframework.http.HttpStatus;

/**
 * A call's arguments as they cross the wire, in one place for everything that carries them: the JSON array
 * of {@code POST /_henge/...} and the {@code OPEN} frame of a trunk. Positional, each typed by the method's
 * generic parameter type, streamed through a parser and a generator and never held as a {@code JsonNode},
 * which keeps a JSON decimal as a double and so loses a {@code BigDecimal}'s precision and scale.
 */
final class ServiceArguments {

    private ServiceArguments() {
    }

    /** Writes the first {@code count} of {@code args} as a JSON array, each as its declared parameter type. */
    static void write(ObjectMapper objectMapper, Method method, Object[] args, int count, JsonGenerator generator)
            throws IOException {
        Type[] parameterTypes = method.getGenericParameterTypes();
        generator.writeStartArray();
        for (int i = 0; i < count; i++) {
            objectMapper.writerFor(objectMapper.getTypeFactory().constructType(parameterTypes[i]))
                    .writeValue(generator, args[i]);
        }
        generator.writeEndArray();
    }

    /**
     * Reads the array {@code parser} is at the start of, up to its end, into the first {@code count}
     * parameters of {@code method}. Extra elements are skipped and too few are an error, either as a
     * {@code 400}.
     */
    static Object[] readArray(ObjectMapper objectMapper, Method method, int count, JsonParser parser) throws StreamReadException {
        Object[] args = new Object[method.getParameterCount()];
        int provided = 0;
        try {
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (provided < count) {
                    args[provided] = readArgument(objectMapper, parser, method, provided);
                } else {
                    parser.skipChildren();
                }
                provided++;
            }
        } catch (StreamReadException e) {
            throw e;
        } catch (IOException e) {
            throw new HengeDispatchException(HttpStatus.BAD_REQUEST, "Failed to read request body");
        }
        if (provided != count) {
            throw new HengeDispatchException(HttpStatus.BAD_REQUEST,
                    "Expected " + count + " argument(s) for " + method.getName() + " but received " + provided);
        }
        return args;
    }

    private static Object readArgument(ObjectMapper objectMapper, JsonParser parser, Method method, int index)
            throws StreamReadException {
        try {
            return objectMapper.readValue(parser, objectMapper.getTypeFactory().constructType(method.getGenericParameterTypes()[index]));
        } catch (StreamReadException e) {
            throw e; // malformed JSON, not a binding problem
        } catch (IOException | RuntimeException e) {
            // Not just Jackson's own binding errors: a custom deserializer can throw anything, e.g.
            // ImmutableList rejecting a null element.
            throw new HengeDispatchException(HttpStatus.BAD_REQUEST, "Failed to bind argument " + index + " of " + method.getName());
        }
    }
}
