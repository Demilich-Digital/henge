package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.counter.CounterTestConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

class HengeDatastoreInstallerTest {

    private static final List<String> CREATED = new ArrayList<>();

    /** Records the order beans finish initializing in. */
    static class CreationOrderRecorder implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            CREATED.add(beanName);
            return bean;
        }
    }

    @Configuration
    static class RecorderConfig {
        @Bean
        static CreationOrderRecorder creationOrderRecorder() {
            return new CreationOrderRecorder();
        }
    }

    @Test
    void anInProcessDatastoreIsRegisteredWhenTheApplicationBringsNone() {
        try (var ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            assertThat(ctx.getBean(SystemEphemeralDatastore.class)).isInstanceOf(GuardedDatastore.class);
            assertThat(((GuardedDatastore) ctx.getBean(SystemEphemeralDatastore.class)).delegate())
                    .isInstanceOf(InProcessEphemeralDatastore.class);
            assertThat(ctx.getBeansOfType(SystemEphemeralDatastore.class)).containsOnlyKeys("hengeDatastore");
        }
    }

    @Test
    void theDatastoreIsCreatedBeforeEveryServiceBean() {
        CREATED.clear();
        try (var ctx = new AnnotationConfigApplicationContext(RecorderConfig.class, CounterTestConfig.class)) {
            int datastore = CREATED.indexOf("hengeDatastore");
            assertThat(datastore).isGreaterThanOrEqualTo(0);
            assertThat(CREATED.indexOf("counter-service-1")).isGreaterThan(datastore);
            assertThat(CREATED.indexOf("counter-service-2")).isGreaterThan(datastore);
        }
    }

    @Test
    void everyServiceBeanDependsOnTheDatastore() {
        try (var ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            assertThat(ctx.getBeanFactory().getDependentBeans("hengeDatastore"))
                    .contains("counter-service-1", "counter-service-2");
        }
    }

    @Configuration
    static class OwnDatastoreConfig {
        @Bean
        SystemEphemeralDatastore myStore() {
            return new InProcessEphemeralDatastore();
        }
    }

    @Test
    void anApplicationsOwnDatastoreIsUsedAndCreatedFirstToo() {
        CREATED.clear();
        try (var ctx = new AnnotationConfigApplicationContext(RecorderConfig.class, OwnDatastoreConfig.class, CounterTestConfig.class)) {
            assertThat(ctx.getBeansOfType(SystemEphemeralDatastore.class)).containsOnlyKeys("myStore");
            assertThat(CREATED.indexOf("counter-service-1")).isGreaterThan(CREATED.indexOf("myStore"));
        }
    }

    @Configuration
    static class TwoDatastoresConfig {
        @Bean
        SystemEphemeralDatastore one() {
            return new InProcessEphemeralDatastore();
        }

        @Bean
        SystemEphemeralDatastore two() {
            return new InProcessEphemeralDatastore();
        }
    }

    @Test
    void twoDatastoresWithoutAPrimaryFailAtStartup() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(TwoDatastoresConfig.class, CounterTestConfig.class).close())
                .hasStackTraceContaining("2 SystemEphemeralDatastore beans")
                .hasStackTraceContaining("@Primary");
    }

    @Configuration
    static class PrimaryDatastoreConfig {
        @Bean
        SystemEphemeralDatastore one() {
            return new InProcessEphemeralDatastore();
        }

        @Bean
        @Primary
        SystemEphemeralDatastore two() {
            return new InProcessEphemeralDatastore();
        }
    }

    @Test
    void aPrimaryDatastoreBreaksTheTie() {
        CREATED.clear();
        try (var ctx = new AnnotationConfigApplicationContext(RecorderConfig.class, PrimaryDatastoreConfig.class, CounterTestConfig.class)) {
            assertThat(ctx.getBeanFactory().getDependentBeans("two")).contains("counter-service-1");
            assertThat(ctx.getBeanFactory().getDependentBeans("one")).doesNotContain("counter-service-1");
        }
    }
}
