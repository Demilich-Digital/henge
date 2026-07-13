package io.modular.spring.fixture.echo;

import io.modular.spring.EnableModularServices;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Plain-Spring equivalent of the Boot fixtures' {@code @SpringBootApplication} entry points. */
@Configuration
@ComponentScan
@EnableModularServices
public class EchoTestConfig {
}
