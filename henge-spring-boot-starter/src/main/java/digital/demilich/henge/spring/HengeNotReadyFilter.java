package digital.demilich.henge.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers every request with {@code 503} until this process is ready ({@link HengeBootGate}): it has
 * reached the ephemeral store and decided what it hosts. Only the actuator's health endpoints are let
 * through, which is how an orchestrator is told the process is alive and not ready.
 *
 * <p>The response says to retry shortly and closes the connection, so a client that holds one open
 * dials again instead of staying with a node that isn't serving.
 */
class HengeNotReadyFilter extends OncePerRequestFilter {

    private final ObjectProvider<HengeBootGate> gate;
    private final String healthPath;
    private volatile boolean open;

    HengeNotReadyFilter(ObjectProvider<HengeBootGate> gate, String actuatorBasePath) {
        this.gate = gate;
        this.healthPath = actuatorBasePath + "/health";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (open || ready() || isHealthCheck(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "1");
        response.setHeader("Connection", "close");
    }

    /** Once ready, always ready as far as this filter is concerned: only the first contact is gated. */
    private boolean ready() {
        HengeBootGate current = gate.getIfAvailable();
        if (current == null || current.isReady()) {
            open = true;
        }
        return open;
    }

    private boolean isHealthCheck(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(healthPath) || path.startsWith(healthPath + "/");
    }
}
