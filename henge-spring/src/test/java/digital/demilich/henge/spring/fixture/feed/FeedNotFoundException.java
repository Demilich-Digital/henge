package digital.demilich.henge.spring.fixture.feed;

import digital.demilich.henge.core.ErrorStatus;

@ErrorStatus(404)
public class FeedNotFoundException extends RuntimeException {

    public FeedNotFoundException(String message) {
        super(message);
    }
}
