package com.demilich.horde.spring.fixture.echo;

import com.demilich.horde.core.ImmutableList;
import com.demilich.horde.core.ServiceVersion;
import java.util.concurrent.atomic.AtomicInteger;

@ServiceVersion(value = EchoService.class, version = "1")
public class EchoServiceImpl implements EchoService {

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String echo(String value) {
        callCount.incrementAndGet();
        return "echo:" + value;
    }

    @Override
    public void explode(String reason) {
        throw new EchoFailureException(reason);
    }

    @Override
    public ImmutableList<String> upperCaseAll(ImmutableList<String> values) {
        return ImmutableList.copyOf(values.stream().map(String::toUpperCase).toList());
    }

    public int getCallCount() {
        return callCount.get();
    }
}
