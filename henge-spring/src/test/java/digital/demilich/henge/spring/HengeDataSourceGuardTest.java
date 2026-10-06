package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.HengeAcknowledgeThisOpensAPoolOnEveryNode;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class HengeDataSourceGuardTest {

    private static int connectionsOpened;

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class WithDataSource {
        @Bean
        DataSource dataSource() {
            // Opening a connection is what the guard must prevent: counted, never reached by a refusal.
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        connectionsOpened++;
                        return null;
                    });
        }
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class AcknowledgedOnTheBean {
        @Bean
        @HengeAcknowledgeThisOpensAPoolOnEveryNode
        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> null);
        }
    }

    @Configuration
    @HengeAcknowledgeThisOpensAPoolOnEveryNode
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class AcknowledgedOnTheClass {
    }

    /** Stands for what Boot's auto-configuration builds: a pool in a class the author doesn't own. */
    @Configuration
    static class ForeignPool {
        @Bean
        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> null);
        }
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class WithoutDataSource {
    }

    @Test
    void aDataSourceBeanFailsStartupAndSaysWhatToDoInstead() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(WithDataSource.class);
            assertThatThrownBy(ctx::refresh)
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("'dataSource'")
                    .hasStackTraceContaining("@LeasedResource ResourceProvider<DataSource>")
                    .hasStackTraceContaining("DataSourceAutoConfiguration")
                    .hasStackTraceContaining("@HengeAcknowledgeThisOpensAPoolOnEveryNode");
        }
        assertThat(connectionsOpened).isZero();
    }

    @Test
    void anAcknowledgementOnTheBeanMethodLetsItStart() {
        try (var ctx = new AnnotationConfigApplicationContext(AcknowledgedOnTheBean.class)) {
            assertThat(ctx.getBeansOfType(DataSource.class)).hasSize(1);
        }
    }

    @Test
    void anAcknowledgementOnTheApplicationClassCoversAPoolItDidNotWrite() {
        try (var ctx = new AnnotationConfigApplicationContext(AcknowledgedOnTheClass.class, ForeignPool.class)) {
            assertThat(ctx.getBeansOfType(DataSource.class)).hasSize(1);
        }
    }

    @Test
    void noDataSourceBeanIsNothingToSay() {
        try (var ctx = new AnnotationConfigApplicationContext(WithoutDataSource.class)) {
            assertThat(ctx.getBeansOfType(DataSource.class)).isEmpty();
        }
    }
}
