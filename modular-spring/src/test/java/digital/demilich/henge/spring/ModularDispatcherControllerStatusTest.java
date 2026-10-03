package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.ErrorStatus;
import digital.demilich.henge.core.ServiceVersionUnsupportedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ModularDispatcherControllerStatusTest {

    @ErrorStatus(409)
    static class ConflictException extends RuntimeException {
    }

    static class SpecificConflictException extends ConflictException {
    }

    @ErrorStatus(200)
    static class SuccessStatusException extends RuntimeException {
    }

    @ErrorStatus(600)
    static class OutOfRangeException extends RuntimeException {
    }

    @Test
    void unannotatedExceptionIsA500() {
        assertThat(ModularDispatcherController.statusFor(new IllegalStateException("x"))).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void annotatedExceptionChoosesItsStatus() {
        assertThat(ModularDispatcherController.statusFor(new ConflictException()).value()).isEqualTo(409);
    }

    @Test
    void subclassInheritsTheAnnotation() {
        assertThat(ModularDispatcherController.statusFor(new SpecificConflictException()).value()).isEqualTo(409);
    }

    @Test
    void callingAMethodOutsideTheImplementationsVersionRangeIsA501() {
        assertThat(ModularDispatcherController.statusFor(new ServiceVersionUnsupportedException("x")).value()).isEqualTo(501);
    }

    @Test
    void statusesOutsideFourHundredToFiveNinetyNineFallBackTo500() {
        assertThat(ModularDispatcherController.statusFor(new SuccessStatusException())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ModularDispatcherController.statusFor(new OutOfRangeException())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
