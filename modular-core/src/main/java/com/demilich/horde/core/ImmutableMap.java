package com.demilich.horde.core;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * The {@code Map} counterpart to {@link ImmutableList} — see its Javadoc for why a distinctly
 * named, genuinely immutable type is the {@code @ModularService} boundary container instead of
 * plain {@code java.util.Map}. Backed by {@link Map#copyOf}.
 */
public final class ImmutableMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> delegate;

    private ImmutableMap(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    public static <K, V> ImmutableMap<K, V> of() {
        return new ImmutableMap<>(Map.of());
    }

    public static <K, V> ImmutableMap<K, V> copyOf(Map<? extends K, ? extends V> source) {
        return new ImmutableMap<>(Map.copyOf(source));
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }
}
