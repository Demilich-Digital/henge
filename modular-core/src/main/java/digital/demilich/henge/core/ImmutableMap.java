package digital.demilich.henge.core;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The {@code Map} counterpart to {@link ImmutableList} — see its Javadoc for why a distinctly
 * named, genuinely immutable type is the {@code @ModularService} boundary container instead of
 * plain {@code java.util.Map}. Backed by {@link Map#copyOf}. Mutators are overridden as deprecated
 * and throwing so IDEs flag them instead of offering them as working calls.
 */
public final class ImmutableMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> delegate;

    private ImmutableMap(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    public static <K, V> ImmutableMap<K, V> of() {
        return new ImmutableMap<>(Map.of());
    }

    // Up to five pairs, as Guava offers; ImmutableMap.copyOf(Map.of(...)) covers more.

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1) {
        return new ImmutableMap<>(Map.of(k1, v1));
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2) {
        return new ImmutableMap<>(Map.of(k1, v1, k2, v2));
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return new ImmutableMap<>(Map.of(k1, v1, k2, v2, k3, v3));
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return new ImmutableMap<>(Map.of(k1, v1, k2, v2, k3, v3, k4, v4));
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return new ImmutableMap<>(Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5));
    }

    public static <K, V> ImmutableMap<K, V> copyOf(Map<? extends K, ? extends V> source) {
        return new ImmutableMap<>(Map.copyOf(source));
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }

    /**
     * Delegated rather than inherited: {@code AbstractMap} answers these by scanning every entry.
     * {@code null} is never a key, and the delegate would throw on it rather than answer.
     */
    @Override
    public V get(Object key) {
        return key == null ? null : delegate.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && delegate.containsKey(key);
    }

    @Override
    @Deprecated
    public final V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void putAll(Map<? extends K, ? extends V> source) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean remove(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V putIfAbsent(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V replace(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean replace(K key, V oldValue, V newValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }
}
