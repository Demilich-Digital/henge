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
}
