package digital.demilich.henge.spring.fixture.echo;

import java.math.BigDecimal;

/**
 * A record's {@code equals} compares a {@code BigDecimal} with its scale and a {@code double} with
 * {@code Double.compare} (so {@code -0.0 != 0.0}) -- both have to survive the wire exactly.
 */
public record Measurement(BigDecimal amount, double reading) {
}
