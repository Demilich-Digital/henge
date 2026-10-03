package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ServiceVersion;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ServiceVersion(value = EchoService.class, version = 1)
public class EchoServiceImpl implements EchoService {

    /** What application code sees in the SecurityContext while {@link #echo} runs (null with no authentication). */
    public static final AtomicReference<Authentication> LAST_AUTHENTICATION = new AtomicReference<>();

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String echo(String value) {
        callCount.incrementAndGet();
        LAST_AUTHENTICATION.set(SecurityContextHolder.getContext().getAuthentication());
        return "echo:" + value;
    }

    public int getCallCount() {
        return callCount.get();
    }
}
