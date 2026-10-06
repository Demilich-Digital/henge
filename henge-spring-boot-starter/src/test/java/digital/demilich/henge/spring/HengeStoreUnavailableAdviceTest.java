package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import digital.demilich.henge.core.StoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A request that failed for want of the ephemeral store is a 503 at the edge, unless the application handles it. */
class HengeStoreUnavailableAdviceTest {

    @RestController
    static class Frontend {
        @GetMapping("/orders")
        String orders() {
            throw new StoreUnavailableException("no store");
        }
    }

    @RestController
    static class OwnHandling {
        @GetMapping("/orders")
        String orders() {
            throw new StoreUnavailableException("no store");
        }

        @ExceptionHandler(StoreUnavailableException.class)
        @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
        void handled() {
        }
    }

    @Test
    void anUnhandledStoreFailureIsA503() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Frontend()).setControllerAdvice(new HengeStoreUnavailableAdvice()).build();

        mvc.perform(get("/orders")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void anApplicationThatHandlesItItselfDecides() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OwnHandling()).setControllerAdvice(new HengeStoreUnavailableAdvice()).build();

        mvc.perform(get("/orders")).andExpect(status().isGatewayTimeout());
    }

    @Test
    void theAdviceComesWithAServletApplicationAndNotWithoutOne() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(HengeAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(HengeStoreUnavailableAdvice.class));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HengeAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HengeStoreUnavailableAdvice.class));
    }
}
