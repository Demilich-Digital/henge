package digital.demilich.henge.examples.app;

import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One binary that can play either role in the "greeting-service" / "audit-service" pair, or
 * both at once — see the repository README's Quickstart for exact commands.
 * {@code @EnableHengeServices}'s {@code basePackages} has to point at
 * {@code digital.demilich.henge.examples}, since the contracts and their implementations live in
 * sibling modules/packages, not under this class's own package. Plain component scanning needs
 * nothing extra: the implementations are registered by the framework, not scanned, and
 * {@code DemoController} is in this class's own package.
 */
@SpringBootApplication
@EnableHengeServices(basePackages = "digital.demilich.henge.examples")
public class ExampleApp {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class, args);
    }
}
