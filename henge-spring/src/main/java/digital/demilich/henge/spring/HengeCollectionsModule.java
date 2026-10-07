package digital.demilich.henge.spring;

import digital.demilich.henge.core.ImmutableBytes;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ImmutableMap;
import digital.demilich.henge.core.ImmutableSet;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.util.ClassUtils;

/**
 * Wire support for {@link ImmutableBytes}, and deserialization support for the immutable collection types {@code henge-processor} allows at
 * a {@code @HengeService} boundary: {@link ImmutableList}/{@link ImmutableSet}/{@link ImmutableMap},
 * and Guava's {@code ImmutableList}/{@code ImmutableSet}/{@code ImmutableMap} when Guava is on the
 * classpath. None of them has the construct-then-{@code add()} shape Jackson's default collection
 * deserializer relies on (that's the whole point -- see {@link ImmutableList}'s Javadoc), so each
 * delegates to the standard {@code List}/{@code Map} deserializer for the resolved element/key/value
 * type and wraps the result via {@code copyOf}. Kept out of {@code henge-core} so that module stays
 * Jackson-free; only the transport (already a jackson-databind consumer) needs to know how these
 * serialize on the wire. Serialization needs no special handling: they all implement the standard
 * {@code Collection}/{@code Map} interfaces, so Jackson's default container serializers handle them.
 * {@link ImmutableBytes} is written and read as a base64 string, which is what Jackson does with a
 * {@code byte[]}.
 */
final class HengeCollectionsModule extends SimpleModule {

    HengeCollectionsModule() {
        addSerializer(ImmutableBytes.class, new BytesSerializer());
        addDeserializer(ImmutableBytes.class, new BytesDeserializer());
        addDeserializer(ImmutableList.class, new CollectionDeserializer<>(ImmutableList::copyOf));
        addDeserializer(ImmutableSet.class, new CollectionDeserializer<>(ImmutableSet::copyOf));
        addDeserializer(ImmutableMap.class, new MapDeserializer<>(ImmutableMap::copyOf));
        if (ClassUtils.isPresent("com.google.common.collect.ImmutableList", HengeCollectionsModule.class.getClassLoader())) {
            GuavaCollections.register(this);
        }
    }

    /**
     * Separate class so Guava's types are only ever loaded once Guava is known to be present: it's a
     * {@code compileOnly} dependency of this module, never forced on an application.
     */
    private static final class GuavaCollections {

        static void register(SimpleModule module) {
            module.addDeserializer(com.google.common.collect.ImmutableList.class,
                    new CollectionDeserializer<>(com.google.common.collect.ImmutableList::copyOf));
            module.addDeserializer(com.google.common.collect.ImmutableSet.class,
                    new CollectionDeserializer<>(com.google.common.collect.ImmutableSet::copyOf));
            module.addDeserializer(com.google.common.collect.ImmutableMap.class,
                    new MapDeserializer<>(com.google.common.collect.ImmutableMap::copyOf));
        }
    }

    private static final class BytesSerializer extends JsonSerializer<ImmutableBytes> {

        @Override
        public void serialize(ImmutableBytes value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeBinary(value.toByteArray());
        }
    }

    private static final class BytesDeserializer extends JsonDeserializer<ImmutableBytes> {

        @Override
        public ImmutableBytes deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return ImmutableBytes.copyOf(parser.getBinaryValue());
        }
    }

    private static final class CollectionDeserializer<T> extends JsonDeserializer<T> implements ContextualDeserializer {

        private final Function<List<Object>, T> wrap;
        private final JsonDeserializer<Object> delegate;

        CollectionDeserializer(Function<List<Object>, T> wrap) {
            this(wrap, null);
        }

        private CollectionDeserializer(Function<List<Object>, T> wrap, JsonDeserializer<Object> delegate) {
            this.wrap = wrap;
            this.delegate = delegate;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
            JavaType containerType = property != null ? property.getType() : ctxt.getContextualType();
            JavaType elementType = containerType != null && containerType.hasGenericTypes()
                    ? containerType.containedType(0)
                    : ctxt.getTypeFactory().constructType(Object.class);
            JavaType listType = ctxt.getTypeFactory().constructCollectionType(ArrayList.class, elementType);
            return new CollectionDeserializer<>(wrap, ctxt.findContextualValueDeserializer(listType, property));
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return wrap.apply((List<Object>) delegate.deserialize(p, ctxt));
        }
    }

    private static final class MapDeserializer<T> extends JsonDeserializer<T> implements ContextualDeserializer {

        private final Function<Map<Object, Object>, T> wrap;
        private final JsonDeserializer<Object> delegate;

        MapDeserializer(Function<Map<Object, Object>, T> wrap) {
            this(wrap, null);
        }

        private MapDeserializer(Function<Map<Object, Object>, T> wrap, JsonDeserializer<Object> delegate) {
            this.wrap = wrap;
            this.delegate = delegate;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
            JavaType containerType = property != null ? property.getType() : ctxt.getContextualType();
            JavaType keyType = containerType != null && containerType.hasGenericTypes()
                    ? containerType.containedType(0)
                    : ctxt.getTypeFactory().constructType(Object.class);
            JavaType valueType = containerType != null && containerType.containedTypeCount() > 1
                    ? containerType.containedType(1)
                    : ctxt.getTypeFactory().constructType(Object.class);
            JavaType mapType = ctxt.getTypeFactory().constructMapType(LinkedHashMap.class, keyType, valueType);
            return new MapDeserializer<>(wrap, ctxt.findContextualValueDeserializer(mapType, property));
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return wrap.apply((Map<Object, Object>) delegate.deserialize(p, ctxt));
        }
    }
}
