package digital.demilich.henge.core;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A genuinely immutable, distinctly-named {@code List} — unlike the {@code java.util.List}
 * interface, a reference typed as {@code ImmutableList<T>} carries a compile-time guarantee that
 * no caller can mutate it after construction. {@code henge-processor} recognizes this type (and
 * {@link ImmutableSet} / {@link ImmutableMap}) as an allowed {@code @HengeService} boundary
 * container, where a plain {@code java.util.List} is not: Jackson deserializes {@code List}
 * parameters to a mutable {@code ArrayList} by default, and nothing stops the caller from holding
 * onto and later mutating an argument or return value it passed by reference in embedded mode —
 * exactly the aliasing divergence between embedded and internal-rest dispatch the state-ownership
 * doctrine is meant to close.
 *
 * <p>Backed by {@link List#copyOf}, which performs its own defensive copy and rejects
 * {@code null} elements. Mutators are overridden as deprecated and throwing so IDEs flag them
 * instead of offering them as working calls.
 */
public final class ImmutableList<T> extends AbstractList<T> implements RandomAccess {

    private final List<T> delegate;

    private ImmutableList(List<T> delegate) {
        this.delegate = delegate;
    }

    public static <T> ImmutableList<T> of() {
        return new ImmutableList<>(List.of());
    }

    @SafeVarargs
    public static <T> ImmutableList<T> of(T... items) {
        return new ImmutableList<>(List.of(items));
    }

    public static <T> ImmutableList<T> copyOf(Collection<? extends T> source) {
        return new ImmutableList<>(List.copyOf(source));
    }

    @Override
    public T get(int index) {
        return delegate.get(index);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    @Deprecated
    public final boolean add(T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean addAll(Collection<? extends T> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean addAll(int index, Collection<? extends T> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final boolean remove(Object element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final T remove(int index) {
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
    public final T set(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void replaceAll(UnaryOperator<T> operator) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void sort(Comparator<? super T> comparator) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void clear() {
        throw new UnsupportedOperationException();
    }

    // Java 21's SequencedCollection mutators.

    @Override
    @Deprecated
    public final void addFirst(T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final void addLast(T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final T removeFirst() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public final T removeLast() {
        throw new UnsupportedOperationException();
    }
}
