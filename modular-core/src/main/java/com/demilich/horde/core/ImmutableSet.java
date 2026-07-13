package com.demilich.horde.core;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * The {@code Set} counterpart to {@link ImmutableList} — see its Javadoc for why a distinctly
 * named, genuinely immutable type is the {@code @ModularService} boundary container instead of
 * plain {@code java.util.Set}. Backed by {@link Set#copyOf}.
 */
public final class ImmutableSet<T> extends AbstractSet<T> {

    private final Set<T> delegate;

    private ImmutableSet(Set<T> delegate) {
        this.delegate = delegate;
    }

    public static <T> ImmutableSet<T> of() {
        return new ImmutableSet<>(Set.of());
    }

    @SafeVarargs
    public static <T> ImmutableSet<T> of(T... items) {
        return new ImmutableSet<>(Set.of(items));
    }

    public static <T> ImmutableSet<T> copyOf(Collection<? extends T> source) {
        return new ImmutableSet<>(Set.copyOf(source));
    }

    @Override
    public Iterator<T> iterator() {
        return delegate.iterator();
    }

    @Override
    public int size() {
        return delegate.size();
    }
}
