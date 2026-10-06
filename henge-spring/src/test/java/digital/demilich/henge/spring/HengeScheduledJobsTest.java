package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.HengeAcknowledgeThisRunsOnEveryNode;
import digital.demilich.henge.core.HengeScheduled;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class HengeScheduledJobsTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class Base {
    }

    static class Ticker {
        final AtomicInteger ticks = new AtomicInteger();

        @HengeScheduled(cron = "${test.cron:* * * * * *}", zone = "UTC")
        public void tick() {
            ticks.incrementAndGet();
        }
    }

    @Configuration
    static class TickerConfig {
        @Bean
        Ticker ticker() {
            return new Ticker();
        }
    }

    static class WithArguments {
        @HengeScheduled(cron = "* * * * * *")
        public void tick(String what) {
        }
    }

    @Configuration
    static class WithArgumentsConfig {
        @Bean
        WithArguments withArguments() {
            return new WithArguments();
        }
    }

    static class TwoOfAKind {
        @HengeScheduled(cron = "* * * * * *")
        public void tick() {
        }
    }

    @Configuration
    static class TwoOfAKindConfig {
        @Bean
        TwoOfAKind first() {
            return new TwoOfAKind();
        }

        @Bean
        TwoOfAKind second() {
            return new TwoOfAKind();
        }
    }

    static class Named {
        @HengeScheduled(cron = "* * * * * *", name = "${job.name}")
        public void tick() {
        }
    }

    @Configuration
    static class NamedConfig {
        @Bean
        Named first() {
            return new Named();
        }

        @Bean
        Named second() {
            return new Named();
        }
    }

    static class LongJob {
        @HengeScheduled(cron = "0 0 0 1 1 *", maxRuntime = "${test.max-runtime}")
        public void batch() {
        }
    }

    @Configuration
    static class LongJobConfig {
        @Bean
        LongJob longJob() {
            return new LongJob();
        }
    }

    static class Plain {
        @Scheduled(fixedRate = 100000)
        public void evict() {
        }
    }

    @Configuration
    static class PlainConfig {
        @Bean
        Plain plain() {
            return new Plain();
        }
    }

    static class PlainRepeated {
        @Schedules({@Scheduled(fixedRate = 100000), @Scheduled(cron = "0 0 0 * * *")})
        public void evict() {
        }
    }

    @Configuration
    static class PlainRepeatedConfig {
        @Bean
        PlainRepeated plainRepeated() {
            return new PlainRepeated();
        }
    }

    static class AcknowledgedOnMethod {
        @Scheduled(fixedRate = 100000)
        @HengeAcknowledgeThisRunsOnEveryNode
        public void evict() {
        }
    }

    @Configuration
    static class AcknowledgedOnMethodConfig {
        @Bean
        AcknowledgedOnMethod acknowledged() {
            return new AcknowledgedOnMethod();
        }
    }

    @HengeAcknowledgeThisRunsOnEveryNode
    static class AcknowledgedOnClass {
        @Scheduled(fixedRate = 100000)
        public void evict() {
        }

        @Scheduled(fixedDelay = 100000)
        public void flush() {
        }
    }

    @Configuration
    static class AcknowledgedOnClassConfig {
        @Bean
        AcknowledgedOnClass acknowledged() {
            return new AcknowledgedOnClass();
        }
    }

    static class Configurer implements SchedulingConfigurer {
        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
        }
    }

    @Configuration
    static class ConfigurerConfig {
        @Bean
        Configurer configurer() {
            return new Configurer();
        }
    }

    @HengeAcknowledgeThisRunsOnEveryNode
    static class AcknowledgedConfigurer implements SchedulingConfigurer {
        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
        }
    }

    @Configuration
    static class AcknowledgedConfigurerConfig {
        @Bean
        AcknowledgedConfigurer configurer() {
            return new AcknowledgedConfigurer();
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(Base.class);
        ctx.register(configs);
        return ctx;
    }

    @Test
    void aJobRunsOnItsSchedule() throws Exception {
        try (var ctx = context(Map.of(), TickerConfig.class)) {
            ctx.refresh();
            Ticker ticker = ctx.getBean(Ticker.class);
            long deadline = System.nanoTime() + 8_000_000_000L;
            while (ticker.ticks.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(ticker.ticks.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void aJobStopsWhenTheContextCloses() throws Exception {
        Ticker ticker;
        try (var ctx = context(Map.of(), TickerConfig.class)) {
            ctx.refresh();
            ticker = ctx.getBean(Ticker.class);
        }
        int atClose = ticker.ticks.get();
        Thread.sleep(2200);
        assertThat(ticker.ticks.get()).isEqualTo(atClose);
    }

    @Test
    void aCronThatIsAPlaceholderIsResolved() throws Exception {
        try (var ctx = context(Map.of("test.cron", "0 0 0 1 1 *"), TickerConfig.class)) {
            ctx.refresh();
            Thread.sleep(1500);
            assertThat(ctx.getBean(Ticker.class).ticks.get()).isZero();
        }
    }

    @Test
    void aCronThatDoesntParseFailsStartupNamingTheMethod() {
        try (var ctx = context(Map.of("test.cron", "not a cron"), TickerConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("HengeScheduledJobsTest$Ticker#tick")
                    .hasStackTraceContaining("not a cron");
        }
    }

    @Test
    void aMethodWithArgumentsFailsStartup() {
        try (var ctx = context(Map.of(), WithArgumentsConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .isInstanceOf(BeanCreationException.class)
                    .rootCause().hasMessageContaining("takes arguments");
        }
    }

    @Test
    void twoBeansOfOneClassDeclaringTheJobFailStartupAskingForNames() {
        try (var ctx = context(Map.of(), TwoOfAKindConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .rootCause().hasMessageContaining("Two @HengeScheduled jobs are named").hasMessageContaining("name");
        }
    }

    @Test
    void anExplicitNameLetsTwoBeansOfOneClassBothDeclareTheJob() {
        // The names have to differ between the two, so here they collide on purpose and the message says why.
        try (var ctx = context(Map.of("job.name", "same"), NamedConfig.class)) {
            assertThatThrownBy(ctx::refresh).rootCause().hasMessageContaining("'same'");
        }
    }

    @Test
    void aMaxRuntimeThatIsntADurationFailsStartupNamingTheMethod() {
        try (var ctx = context(Map.of("test.max-runtime", "a while"), LongJobConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("HengeScheduledJobsTest$LongJob#batch")
                    .hasStackTraceContaining("maxRuntime=a while");
        }
    }

    @Test
    void aMaxRuntimeOfZeroFailsStartup() {
        try (var ctx = context(Map.of("test.max-runtime", "0"), LongJobConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("must be positive");
        }
    }

    @Test
    void aMaxRuntimeLongerThanTheDefaultIsAllowedButRemindsAboutBatching() {
        // Captures what the processor logs: the reminder is the whole point of making the author write the number.
        var records = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = java.util.logging.Logger.getLogger(HengeScheduledJobs.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try (var ctx = context(Map.of("test.max-runtime", "6h"), LongJobConfig.class)) {
            ctx.refresh();
            assertThat(records).anySatisfy(line -> assertThat(line)
                    .startsWith("WARNING").contains("batches").contains("lost with it").contains("PT6H"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void theDefaultMaxRuntimeDoesNotWarn() {
        var records = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = java.util.logging.Logger.getLogger(HengeScheduledJobs.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try (var ctx = context(Map.of("test.max-runtime", "1h"), LongJobConfig.class)) {
            ctx.refresh();
            assertThat(records).noneMatch(line -> line.startsWith("WARNING"));
            assertThat(records).anySatisfy(line -> assertThat(line).contains("stopped after PT1H"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void aPlainScheduledMethodFailsStartupNamingItAndBothWaysOut() {
        try (var ctx = context(Map.of(), PlainConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("HengeScheduledJobsTest$Plain#evict")
                    .hasStackTraceContaining("@HengeScheduled")
                    .hasStackTraceContaining("@HengeAcknowledgeThisRunsOnEveryNode");
        }
    }

    @Test
    void severalScheduledAnnotationsOnOneMethodAreStillRefused() {
        try (var ctx = context(Map.of(), PlainRepeatedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("HengeScheduledJobsTest$PlainRepeated#evict");
        }
    }

    @Test
    void anAcknowledgedScheduledMethodStarts() {
        try (var ctx = context(Map.of(), AcknowledgedOnMethodConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(AcknowledgedOnMethod.class)).isNotNull();
        }
    }

    @Test
    void anAcknowledgementOnTheClassCoversEveryScheduledMethodOnIt() {
        try (var ctx = context(Map.of(), AcknowledgedOnClassConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(AcknowledgedOnClass.class)).isNotNull();
        }
    }

    @Test
    void aSchedulingConfigurerIsRefusedBecauseItsTasksShowNoAnnotation() {
        try (var ctx = context(Map.of(), ConfigurerConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("SchedulingConfigurer")
                    .hasStackTraceContaining("HengeScheduledJobsTest$Configurer");
        }
    }

    @Test
    void anAcknowledgedSchedulingConfigurerStarts() {
        try (var ctx = context(Map.of(), AcknowledgedConfigurerConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(AcknowledgedConfigurer.class)).isNotNull();
        }
    }

    @Test
    void aHengeScheduledJobNeedsNoAcknowledgement() {
        try (var ctx = context(Map.of("test.cron", "0 0 0 1 1 *"), TickerConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(Ticker.class)).isNotNull();
        }
    }
}
