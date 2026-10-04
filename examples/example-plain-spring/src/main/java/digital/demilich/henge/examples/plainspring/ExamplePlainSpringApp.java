package digital.demilich.henge.examples.plainspring;

import digital.demilich.henge.examples.contracts.AuditService;
import digital.demilich.henge.examples.contracts.GreetingService;
import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Smallest possible proof that henge-spring's core wiring mechanism — discovery, embedded-mode
 * bean registration, default-version resolution via {@code @Primary} — works under plain Spring
 * Framework, with no {@code @SpringBootApplication}/{@code SpringApplication} and no
 * {@code henge-spring-boot-starter} anywhere in the call stack. Reuses the exact same
 * {@code example-contracts}/{@code example-services} the Boot-based {@code examples/example-app}
 * does. This deliberately doesn't serve HTTP — see {@code henge-spring}'s
 * {@code HengeDispatchPlainSpringTest} for the Boot-free embedded-Tomcat round trip that does.
 */
public class ExamplePlainSpringApp {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
            GreetingService greetingService = context.getBean(GreetingService.class);
            AuditService auditService = context.getBean(AuditService.class);

            System.out.println(greetingService.greet("plain Spring"));
            System.out.println("Audit trail: " + auditService.getEvents());
        }
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.examples")
    static class AppConfig {
    }
}
