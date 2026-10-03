package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImmutableCollectionsTest {

    @Test
    void immutableListRejectsMutation() {
        ImmutableList<String> list = ImmutableList.of("a", "b");

        assertThat(list).containsExactly("a", "b");
        assertThatThrownBy(() -> list.add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "z")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutableListRejectsSequencedMutation() {
        ImmutableList<String> list = ImmutableList.of("a");

        assertThatThrownBy(() -> list.addFirst("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.addLast("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.removeLast()).isInstanceOf(UnsupportedOperationException.class);
        // Not NoSuchElementException, as the inherited default would throw for an empty list.
        assertThatThrownBy(() -> ImmutableList.of().removeFirst()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutableListCopyOfIsUnaffectedByLaterMutationOfSource() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        ImmutableList<String> copy = ImmutableList.copyOf(source);

        source.add("c");

        assertThat(copy).containsExactly("a", "b");
    }

    @Test
    void immutableListEqualsPlainListWithSameElements() {
        assertThat(ImmutableList.of("a", "b")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void immutableSetRejectsMutation() {
        ImmutableSet<String> set = ImmutableSet.of("a", "b");

        assertThat(set).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(() -> set.add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> set.remove("a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutableMapRejectsMutation() {
        ImmutableMap<String, Integer> map = ImmutableMap.copyOf(Map.of("a", 1, "b", 2));

        assertThat(map).containsEntry("a", 1).containsEntry("b", 2);
        assertThatThrownBy(() -> map.put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.remove("a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutableMapCopyOfIsUnaffectedByLaterMutationOfSource() {
        Map<String, Integer> source = new HashMap<>(Map.of("a", 1));
        ImmutableMap<String, Integer> copy = ImmutableMap.copyOf(source);

        source.put("b", 2);

        assertThat(copy).containsOnlyKeys("a");
    }

    @Test
    void immutableSetLookupsAnswerLikeAnyOtherSet() {
        ImmutableSet<String> set = ImmutableSet.of("a", "b");

        assertThat(set.contains("a")).isTrue();
        assertThat(set.contains("z")).isFalse();
        assertThat(set.contains(null)).isFalse();
        assertThat(set.containsAll(List.of("a", "b"))).isTrue();
    }

    @Test
    void immutableMapLookupsAnswerLikeAnyOtherMap() {
        ImmutableMap<String, Integer> map = ImmutableMap.copyOf(Map.of("a", 1));

        assertThat(map.get("a")).isEqualTo(1);
        assertThat(map.get("z")).isNull();
        assertThat(map.get(null)).isNull();
        assertThat(map.containsKey("a")).isTrue();
        assertThat(map.containsKey(null)).isFalse();
        assertThat(map.getOrDefault("z", 9)).isEqualTo(9);
    }
}
