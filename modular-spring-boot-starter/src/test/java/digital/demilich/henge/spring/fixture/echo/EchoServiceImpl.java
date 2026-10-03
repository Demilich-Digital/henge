package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ServiceVersion;
import java.util.concurrent.atomic.AtomicInteger;

@ServiceVersion(value = EchoService.class, version = 1)
public class EchoServiceImpl implements EchoService {

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String echo(String value) {
        callCount.incrementAndGet();
        return "echo:" + value;
    }

    public int getCallCount() {
        return callCount.get();
    }
}
