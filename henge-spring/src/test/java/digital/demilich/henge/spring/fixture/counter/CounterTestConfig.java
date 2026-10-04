package digital.demilich.henge.spring.fixture.counter;

import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Plain-Spring equivalent of the Boot fixtures' {@code @SpringBootApplication} entry points. */
@Configuration
@ComponentScan
@EnableHengeServices
public class CounterTestConfig {
}
