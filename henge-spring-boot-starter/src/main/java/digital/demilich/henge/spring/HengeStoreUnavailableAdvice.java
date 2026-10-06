package digital.demilich.henge.spring;

import digital.demilich.henge.core.StoreUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Answers a request that failed because Henge couldn't reach the ephemeral store with {@code 503},
 * rather than the {@code 500} an unhandled exception would be. It is the last advice in line, so an
 * application that handles {@link StoreUnavailableException} itself, or catches it in its controller,
 * decides instead.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class HengeStoreUnavailableAdvice {

    @ExceptionHandler(StoreUnavailableException.class)
    ResponseEntity<Void> storeUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
