package digital.demilich.henge.core;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@code Set} counterpart to {@link ImmutableList} — see its Javadoc for why a distinctly
 * named, genuinely immutable type is the {@code @HengeService} boundary container instead of
 * plain {@code java.util.Set}. Backed by {@link Set#copyOf}. Mutators are overridden as deprecated
 * and throwing so IDEs flag them instead of offering them as working calls.
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

    /**
     * Delegated rather than inherited: {@code AbstractCollection.contains} scans every element.
     * {@code null} is never an element, and the delegate would throw on it rather than answer.
     */
    @Override
    public boolean contains(Object element) {
        return element != null && delegate.contains(element);
    }

    @Override
    @Deprecated
    public final boolean add(T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean addAll(Collection<? extends T> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean remove(Object element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean removeAll(Collection<?> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean retainAll(Collection<?> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean removeIf(Predicate<? super T> filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void clear() {
        throw new UnsupportedOperationException();
    }
}
