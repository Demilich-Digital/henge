package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ImmutableMap;
import digital.demilich.henge.core.ImmutableSet;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every type {@code modular-processor} accepts across a {@code @ModularService} boundary must
 * survive the transport's mapper unchanged -- otherwise it works embedded and fails only once the
 * service is split.
 */
class ModularTransportSupportTest {

    private final ObjectMapper mapper = ModularTransportSupport.objectMapper();

    record Point(int x, int y) {
    }

    record Box<T>(T value, ImmutableList<T> more) {
    }

    enum Color {
        RED
    }

    record Event(String name, Instant at, Optional<LocalDate> due, ImmutableList<Point> points) {
    }

    /** The wire path: written as the declared type, read straight back into it, no tree in between. */
    private <T> T roundTrip(T value, TypeReference<T> type) throws Exception {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return mapper.readValue(mapper.writerFor(javaType).writeValueAsBytes(value), javaType);
    }

    @Test
    void optionalRoundTrips() throws Exception {
        assertThat(roundTrip(Optional.of("x"), new TypeReference<Optional<String>>() {})).contains("x");
        assertThat(roundTrip(Optional.<String>empty(), new TypeReference<Optional<String>>() {})).isEmpty();
    }

    @Test
    void javaTimeTypesRoundTrip() throws Exception {
        Instant instant = Instant.parse("2026-10-03T12:34:56.789Z");
        assertThat(roundTrip(instant, new TypeReference<Instant>() {})).isEqualTo(instant);
        LocalDate date = LocalDate.of(2026, 10, 3);
        assertThat(roundTrip(date, new TypeReference<LocalDate>() {})).isEqualTo(date);
        LocalDateTime dateTime = LocalDateTime.of(2026, 10, 3, 12, 34, 56);
        assertThat(roundTrip(dateTime, new TypeReference<LocalDateTime>() {})).isEqualTo(dateTime);
        OffsetDateTime offset = OffsetDateTime.of(dateTime, ZoneOffset.ofHours(-4));
        // Compared as strings: AssertJ's isEqualTo for these types only checks the instant, and the
        // point is that the offset and zone region survive too, as they would embedded.
        assertThat(roundTrip(offset, new TypeReference<OffsetDateTime>() {})).hasToString(offset.toString());
        ZonedDateTime zoned = ZonedDateTime.parse("2026-10-03T12:34:56-04:00[America/New_York]");
        assertThat(roundTrip(zoned, new TypeReference<ZonedDateTime>() {})).hasToString(zoned.toString());
        Duration duration = Duration.ofMillis(90_500);
        assertThat(roundTrip(duration, new TypeReference<Duration>() {})).isEqualTo(duration);
    }

    @Test
    void datesAreWrittenAsIsoStringsNotNumbers() throws Exception {
        assertThat(mapper.writeValueAsString(Instant.parse("2026-10-03T12:34:56Z"))).isEqualTo("\"2026-10-03T12:34:56Z\"");
    }

    @Test
    void otherAllowedLeafTypesRoundTrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertThat(roundTrip(uuid, new TypeReference<UUID>() {})).isEqualTo(uuid);
        assertThat(roundTrip(new BigDecimal("1.50"), new TypeReference<BigDecimal>() {})).isEqualByComparingTo("1.50");
        assertThat(roundTrip(BigInteger.TEN.pow(30), new TypeReference<BigInteger>() {})).isEqualTo(BigInteger.TEN.pow(30));
        assertThat(roundTrip(Color.RED, new TypeReference<Color>() {})).isEqualTo(Color.RED);
    }

    record PointV1(int x, int y) {
    }

    record PointV2(int x, int y, int z) {
    }

    @Test
    void aRecordComponentAddedOnOneSideDoesNotBreakTheOther() throws Exception {
        // Newer writer, older reader: the extra component is ignored.
        PointV1 older = mapper.readValue(mapper.writeValueAsBytes(new PointV2(1, 2, 3)), PointV1.class);
        assertThat(older).isEqualTo(new PointV1(1, 2));
        // Older writer, newer reader: the missing component reads as its default.
        PointV2 newer = mapper.readValue(mapper.writeValueAsBytes(new PointV1(1, 2)), PointV2.class);
        assertThat(newer).isEqualTo(new PointV2(1, 2, 0));
    }

    @Test
    void decimalsRoundTripExactly() throws Exception {
        BigDecimal precise = new BigDecimal("12345678901234567.8901");
        assertThat(roundTrip(precise, new TypeReference<BigDecimal>() {})).isEqualTo(precise);
        // isEqualTo, not isEqualByComparingTo: the scale has to survive too.
        assertThat(roundTrip(new BigDecimal("1.50"), new TypeReference<BigDecimal>() {})).isEqualTo(new BigDecimal("1.50"));
        assertThat(roundTrip(-0.0, new TypeReference<Double>() {})).isEqualTo(-0.0);
    }

    @Test
    void recordsContainingOptionalsTimesAndImmutableCollectionsRoundTrip() throws Exception {
        Event event = new Event("launch", Instant.parse("2026-10-03T12:34:56Z"), Optional.of(LocalDate.of(2026, 11, 1)),
                ImmutableList.of(new Point(1, 2)));
        assertThat(roundTrip(event, new TypeReference<Event>() {})).isEqualTo(event);
    }

    @Test
    void immutableCollectionsRoundTrip() throws Exception {
        assertThat(roundTrip(ImmutableSet.of("a", "b"), new TypeReference<ImmutableSet<String>>() {})).containsExactlyInAnyOrder("a", "b");
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of("k", new Point(1, 2))), new TypeReference<ImmutableMap<String, Point>>() {}))
                .containsEntry("k", new Point(1, 2));
    }

    @Test
    void mapKeysTheProcessorAllowsRoundTrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(uuid, 1)), new TypeReference<ImmutableMap<UUID, Integer>>() {}))
                .containsEntry(uuid, 1);
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(7L, 1)), new TypeReference<ImmutableMap<Long, Integer>>() {}))
                .containsEntry(7L, 1);
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(Color.RED, 1)), new TypeReference<ImmutableMap<Color, Integer>>() {}))
                .containsEntry(Color.RED, 1);
        LocalDate date = LocalDate.of(2026, 10, 3);
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(date, 1)), new TypeReference<ImmutableMap<LocalDate, Integer>>() {}))
                .containsEntry(date, 1);
        Instant instant = Instant.parse("2026-10-03T12:34:56.789Z");
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(instant, 1)), new TypeReference<ImmutableMap<Instant, Integer>>() {}))
                .containsEntry(instant, 1);
        ZonedDateTime zoned = ZonedDateTime.parse("2026-10-03T12:34:56-04:00[America/New_York]");
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(zoned, 1)), new TypeReference<ImmutableMap<ZonedDateTime, Integer>>() {}))
                .containsEntry(zoned, 1);
        BigDecimal amount = new BigDecimal("1.50");
        assertThat(roundTrip(ImmutableMap.copyOf(java.util.Map.of(amount, 1)), new TypeReference<ImmutableMap<BigDecimal, Integer>>() {}))
                .containsEntry(amount, 1);
    }

    @Test
    void genericRecordsRoundTripWithTheirTypeArguments() throws Exception {
        Box<Point> box = new Box<>(new Point(1, 2), ImmutableList.of(new Point(3, 4)));
        assertThat(roundTrip(box, new TypeReference<Box<Point>>() {})).isEqualTo(box);
    }
}
