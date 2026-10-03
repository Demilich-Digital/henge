package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * A failure in a split service must leave its stack trace in the serving process's log: the caller
 * only ever gets the exception's type and message. (This module logs through java.util.logging.)
 */
class ModularDispatcherControllerLoggingTest {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(ModularDispatcherController.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private GenericApplicationContext context;
    private ModularDispatcherController controller;

    @BeforeEach
    void setUp() {
        logger.addHandler(capture);
        context = new GenericApplicationContext();
        context.registerBean("echo-service-1", EchoServiceImpl.class);
        context.refresh();
        ModularServiceRegistry registry = new ModularServiceRegistry(
                List.of(ModularServiceDescriptor.of("echo-service", 1, EchoService.class, "echo-service-1")));
        controller = new ModularDispatcherController(
                context, registry, ModularTransportSupport.objectMapper(), new ModularProperties(new MockEnvironment()));
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        context.close();
    }

    private void explode(String reason) {
        assertThatThrownBy(() -> controller.dispatch("echo-service", 1, "explode", null,
                new ByteArrayInputStream(("[\"" + reason + "\"]").getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ModularDispatchException.class);
    }

    @Test
    void aServerErrorIsLoggedWithItsStackTrace() {
        explode("boom");

        assertThat(records).anySatisfy(logRecord -> {
            assertThat(logRecord.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(logRecord.getMessage()).contains("echo-service").contains("explode").contains("500");
            assertThat(logRecord.getThrown()).isInstanceOf(EchoFailureException.class);
        });
    }

    @Test
    void aClientErrorTheServiceChoseIsNotLoggedAsAnError() {
        explode("not-found"); // EchoNotFoundException is @ErrorStatus(404)

        assertThat(records).noneMatch(logRecord -> logRecord.getLevel().intValue() >= Level.WARNING.intValue());
    }
}
