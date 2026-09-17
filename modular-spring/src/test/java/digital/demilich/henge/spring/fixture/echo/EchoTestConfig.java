package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.spring.EnableModularServices;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Plain-Spring equivalent of the Boot fixtures' {@code @SpringBootApplication} entry points. */
@Configuration
@ComponentScan
@EnableModularServices
public class EchoTestConfig {
}
