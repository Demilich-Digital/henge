package digital.demilich.henge.core;

import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;

/**
 * A genuinely immutable, distinctly-named {@code List} — unlike the {@code java.util.List}
 * interface, a reference typed as {@code ImmutableList<T>} carries a compile-time guarantee that
 * no caller can mutate it after construction. {@code modular-processor} recognizes this type (and
 * {@link ImmutableSet} / {@link ImmutableMap}) as an allowed {@code @ModularService} boundary
 * container, where a plain {@code java.util.List} is not: Jackson deserializes {@code List}
 * parameters to a mutable {@code ArrayList} by default, and nothing stops the caller from holding
 * onto and later mutating an argument or return value it passed by reference in embedded mode —
 * exactly the aliasing divergence between embedded and internal-rest dispatch the state-ownership
 * doctrine is meant to close.
 *
 * <p>Backed by {@link List#copyOf}, which performs its own defensive copy and rejects
 * {@code null} elements.
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
}
