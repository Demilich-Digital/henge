package digital.demilich.henge.examples.app;

import digital.demilich.henge.spring.EnableModularServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One binary that can play either role in the "greeting-service" / "audit-service" pair, or
 * both at once — see the module README for exact commands. Both {@code scanBasePackages} (plain
 * Spring component scanning, for the {@code @Service} implementations) and
 * {@code @EnableModularServices}'s {@code basePackages} (for {@code @ModularService} interface
 * discovery) need to point at {@code digital.demilich.henge.examples}, since the contracts and their
 * implementations live in sibling modules/packages, not under this class's own package.
 */
@SpringBootApplication(scanBasePackages = "digital.demilich.henge.examples")
@EnableModularServices(basePackages = "digital.demilich.henge.examples")
public class ExampleApp {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class, args);
    }
}
