package com.demilich.horde.examples.app;

import com.demilich.horde.spring.EnableModularServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One binary that can play either role in the "greeting-service" / "audit-service" pair, or
 * both at once — see the module README for exact commands. Both {@code scanBasePackages} (plain
 * Spring component scanning, for the {@code @Service} implementations) and
 * {@code @EnableModularServices}'s {@code basePackages} (for {@code @ModularService} interface
 * discovery) need to point at {@code com.demilich.horde.examples}, since the contracts and their
 * implementations live in sibling modules/packages, not under this class's own package.
 */
@SpringBootApplication(scanBasePackages = "com.demilich.horde.examples")
@EnableModularServices(basePackages = "com.demilich.horde.examples")
public class ExampleApp {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class, args);
    }
}
