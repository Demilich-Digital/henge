package com.demilich.horde.spring.fixture.counter;

import com.demilich.horde.spring.EnableModularServices;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Plain-Spring equivalent of the Boot fixtures' {@code @SpringBootApplication} entry points. */
@Configuration
@ComponentScan
@EnableModularServices
public class CounterTestConfig {
}
