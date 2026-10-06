package digital.demilich.henge.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Holds Boot's readiness at {@code REFUSING_TRAFFIC} until the process is ready ({@link HengeBootGate}).
 * Boot reports liveness as correct as soon as the application has started, and readiness only once every
 * {@link ApplicationRunner} has returned, so a runner that waits is the whole of "alive, and not ready":
 * the web server is already listening, and the orchestrator is told not to send it traffic.
 */
class HengeBootGateRunner implements ApplicationRunner {

    private final ObjectProvider<HengeBootGate> gate;

    HengeBootGateRunner(ObjectProvider<HengeBootGate> gate) {
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        HengeBootGate current = gate.getIfAvailable();
        if (current != null) {
            current.awaitReady();
        }
    }
}
