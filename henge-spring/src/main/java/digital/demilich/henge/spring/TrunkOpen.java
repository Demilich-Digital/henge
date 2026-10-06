package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import org.springframework.http.HttpStatus;

/**
 * The payload of an {@code OPEN} frame: {@code {"service": ..., "version": ..., "method": ..., "args": [...]}},
 * the fields in that order. The arguments are the method's parameters but the final {@code Channel}, written
 * and read as {@link ServiceArguments} does for {@code /_henge}; reading them needs the method, so the
 * reader asks for it as soon as it has seen the three names.
 */
final class TrunkOpen {

    /** What an {@code OPEN} frame asks for, with its arguments bound. */
    record Request(String service, int version, Method method, Object[] args) {
    }

    /** Finds the method of {@code service@version}, or throws a {@link HengeDispatchException}. */
    @FunctionalInterface
    interface MethodResolver {

        Method resolve(String service, int version, String method);
    }

    private TrunkOpen() {
    }

    static byte[] write(ObjectMapper objectMapper, String service, int version, String methodName, Method method, Object[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = objectMapper.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField("service", service);
            generator.writeNumberField("version", version);
            generator.writeStringField("method", methodName);
            generator.writeFieldName("args");
            ServiceArguments.write(objectMapper, method, args, method.getParameterCount() - 1, generator);
            generator.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize the arguments of " + service + "#" + methodName, e);
        }
        return out.toByteArray();
    }

    /** @throws HengeDispatchException {@code 400} for a payload that isn't an open, or what the resolver throws */
    static Request read(ObjectMapper objectMapper, byte[] payload, MethodResolver resolver) {
        try (JsonParser parser = objectMapper.createParser(payload)) {
            String service = field(parser, "service") ? parser.getText() : null;
            int version = field(parser, "version") ? parser.getIntValue() : 0;
            String name = field(parser, "method") ? parser.getText() : null;
            if (service == null || name == null) {
                throw malformed();
            }
            Method method = resolver.resolve(service, version, name);
            if (!field(parser, "args") || parser.currentToken() != JsonToken.START_ARRAY) {
                throw malformed();
            }
            Object[] args = ServiceArguments.readArray(objectMapper, method, method.getParameterCount() - 1, parser);
            return new Request(service, version, method, args);
        } catch (StreamReadException e) {
            throw malformed();
        } catch (IOException e) {
            throw new HengeDispatchException(HttpStatus.BAD_REQUEST, "Failed to read the open request");
        }
    }

    /** Moves to the value of the next field if it is called {@code name}. */
    private static boolean field(JsonParser parser, String name) throws IOException {
        if (parser.currentToken() == null) {
            parser.nextToken(); // the start of the object
        }
        return parser.nextToken() == JsonToken.FIELD_NAME && parser.currentName().equals(name) && parser.nextToken() != null;
    }

    private static HengeDispatchException malformed() {
        return new HengeDispatchException(HttpStatus.BAD_REQUEST, "The open request is not valid");
    }
}
