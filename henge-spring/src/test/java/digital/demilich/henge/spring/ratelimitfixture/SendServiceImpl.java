package digital.demilich.henge.spring.ratelimitfixture;

import digital.demilich.henge.core.RateLimited;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.ServiceVersion;

/** Limits each recipient on its own. */
@ServiceVersion(value = SendService.class, version = 1)
public class SendServiceImpl implements SendService {

    private final RateLimiter limiter;

    public SendServiceImpl(@RateLimited("sends") RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean send(String recipient) {
        return limiter.tryAcquire(recipient);
    }
}
