package com.demilich.horde.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.demilich.horde.spring.fixture.echo.EchoTestApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the modular transport's internal {@code ObjectMapper} (see {@link ModularTransportSupport})
 * never leaks onto the application's own Jackson wiring: Boot's {@code spring.jackson.*}-customized
 * primary {@code ObjectMapper} is still what the app sees, and a user-defined {@code ObjectMapper}
 * bean causes no ambiguity, with the modular starter active in both cases.
 */
class ModularJacksonIsolationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EchoTestApp.class);

    @Test
    void bootsJacksonCustomizationIsStillAppliedToTheApplicationsObjectMapper() {
        contextRunner.withPropertyValues("spring.jackson.serialization.indent-output=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(ObjectMapper.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            assertThat(mapper.getSerializationConfig().isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
        });
    }

    @Test
    void userDefinedObjectMapperBeanCausesNoAmbiguity() {
        contextRunner.withUserConfiguration(CustomObjectMapperConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ObjectMapper.class);
            assertThat(ctx.getBean(ObjectMapper.class)).isSameAs(CustomObjectMapperConfig.INSTANCE);
        });
    }

    @Configuration
    static class CustomObjectMapperConfig {

        static final ObjectMapper INSTANCE = new ObjectMapper();

        @Bean
        ObjectMapper myObjectMapper() {
            return INSTANCE;
        }
    }
}
