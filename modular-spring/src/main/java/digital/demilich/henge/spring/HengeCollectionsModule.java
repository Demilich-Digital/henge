package digital.demilich.henge.spring;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ImmutableMap;
import digital.demilich.henge.core.ImmutableSet;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deserialization support for {@link ImmutableList}/{@link ImmutableSet}/{@link ImmutableMap} on
 * the modular transport's {@code ObjectMapper}. These types deliberately don't expose the
 * construct-then-{@code add()} shape Jackson's default collection deserializer relies on (that's
 * the whole point — see their Javadoc), so each of these delegates to the standard {@code List}/
 * {@code Map} deserializer for the resolved element/key/value type and wraps the result via
 * {@code copyOf}. Kept out of {@code modular-core} so that module stays Jackson-free; only the
 * transport (already a jackson-databind consumer) needs to know how these serialize on the wire.
 * Serialization needs no special handling: both types implement the standard {@code Collection}/
 * {@code Map} interfaces, so Jackson's default container serializers already handle them.
 */
final class HengeCollectionsModule extends SimpleModule {

    HengeCollectionsModule() {
        addDeserializer(ImmutableList.class, new ImmutableListDeserializer());
        addDeserializer(ImmutableSet.class, new ImmutableSetDeserializer());
        addDeserializer(ImmutableMap.class, new ImmutableMapDeserializer());
    }

    private abstract static class AbstractImmutableCollectionDeserializer<T> extends JsonDeserializer<T> implements ContextualDeserializer {

        private final JavaType elementType;
        private final JsonDeserializer<Object> delegate;

        AbstractImmutableCollectionDeserializer() {
            this(null, null);
        }

        AbstractImmutableCollectionDeserializer(JavaType elementType, JsonDeserializer<Object> delegate) {
            this.elementType = elementType;
            this.delegate = delegate;
        }

        abstract AbstractImmutableCollectionDeserializer<T> withResolvedType(JavaType elementType, JsonDeserializer<Object> delegate);

        abstract T wrap(List<Object> values);

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
            JavaType containerType = property != null ? property.getType() : ctxt.getContextualType();
            JavaType resolvedElementType = containerType != null && containerType.hasGenericTypes()
                    ? containerType.containedType(0)
                    : ctxt.getTypeFactory().constructType(Object.class);
            JavaType listType = ctxt.getTypeFactory().constructCollectionType(ArrayList.class, resolvedElementType);
            JsonDeserializer<Object> listDeserializer = ctxt.findContextualValueDeserializer(listType, property);
            return withResolvedType(resolvedElementType, listDeserializer);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            List<Object> values = (List<Object>) delegate.deserialize(p, ctxt);
            return wrap(values);
        }
    }

    private static final class ImmutableListDeserializer extends AbstractImmutableCollectionDeserializer<ImmutableList<?>> {

        ImmutableListDeserializer() {
            super();
        }

        private ImmutableListDeserializer(JavaType elementType, JsonDeserializer<Object> delegate) {
            super(elementType, delegate);
        }

        @Override
        AbstractImmutableCollectionDeserializer<ImmutableList<?>> withResolvedType(JavaType elementType, JsonDeserializer<Object> delegate) {
            return new ImmutableListDeserializer(elementType, delegate);
        }

        @Override
        ImmutableList<?> wrap(List<Object> values) {
            return ImmutableList.copyOf(values);
        }
    }

    private static final class ImmutableSetDeserializer extends AbstractImmutableCollectionDeserializer<ImmutableSet<?>> {

        ImmutableSetDeserializer() {
            super();
        }

        private ImmutableSetDeserializer(JavaType elementType, JsonDeserializer<Object> delegate) {
            super(elementType, delegate);
        }

        @Override
        AbstractImmutableCollectionDeserializer<ImmutableSet<?>> withResolvedType(JavaType elementType, JsonDeserializer<Object> delegate) {
            return new ImmutableSetDeserializer(elementType, delegate);
        }

        @Override
        ImmutableSet<?> wrap(List<Object> values) {
            return ImmutableSet.copyOf(values);
        }
    }

    private static final class ImmutableMapDeserializer extends JsonDeserializer<ImmutableMap<?, ?>> implements ContextualDeserializer {

        private final JsonDeserializer<Object> delegate;

        ImmutableMapDeserializer() {
            this(null);
        }

        private ImmutableMapDeserializer(JsonDeserializer<Object> delegate) {
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
            JsonDeserializer<Object> mapDeserializer = ctxt.findContextualValueDeserializer(mapType, property);
            return new ImmutableMapDeserializer(mapDeserializer);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ImmutableMap<?, ?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            Map<Object, Object> values = (Map<Object, Object>) delegate.deserialize(p, ctxt);
            return ImmutableMap.copyOf(values);
        }
    }
}
