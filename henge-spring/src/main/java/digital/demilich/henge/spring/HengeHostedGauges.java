package digital.demilich.henge.spring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

/**
 * What this process hosts, as {@code henge.service.hosted}: one gauge per service version, {@code 1} when
 * this process serves it (it is embedded here, and any lease it needs was granted), {@code 0} when it is
 * reached remotely, whether by configuration or because its lease went elsewhere. The tags are
 * {@code service}, {@code version} and {@code mode}, how the version was configured, so a {@code 0} on an
 * {@code embedded} version is a lease refusal.
 *
 * <p>Registered once every singleton exists, since which services are hosted is decided as they are created.
 * Boot registers it when a {@link MeterRegistry} bean exists. Without Boot, declare it as a bean; it needs
 * {@link EnableHengeServices} on the application, like everything else here.
 */
public class HengeHostedGauges implements SmartInitializingSingleton {

    private final MeterRegistry meters;
    private final ApplicationContext applicationContext;

    public HengeHostedGauges(MeterRegistry meters, ApplicationContext applicationContext) {
        this.meters = meters;
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        HengeServiceRegistry registry = applicationContext.getBean(HengeServiceRegistry.class);
        for (HengeTopologyCatalog.Entry entry : applicationContext.getBean(HengeTopologyCatalog.class).entries()) {
            Gauge.builder("henge.service.hosted", () -> registry.find(entry.name(), entry.version()).isPresent() ? 1 : 0)
                    .tag("service", entry.name())
                    .tag("version", String.valueOf(entry.version()))
                    .tag("mode", entry.mode().name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .register(meters);
        }
    }
}
