package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;

class RetryPolicyTest {

    private static RetryPolicy policy(String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < keyValues.length; i += 2) {
            environment.setProperty("modular.transport.retry." + keyValues[i], keyValues[i + 1]);
        }
        return new ModularProperties(environment).getRetryPolicy();
    }

    @Test
    void theDefaultIsThreeAttemptsOnBothKindsOfFailure() {
        assertThat(policy()).isEqualTo(new RetryPolicy(3, Duration.ofMillis(50), true, true));
    }

    @Test
    void everySettingCanBeOverridden() {
        assertThat(policy("max-attempts", "5", "backoff", "1s", "on", "connect"))
                .isEqualTo(new RetryPolicy(5, Duration.ofSeconds(1), true, false));
        assertThat(policy("on", " not-served ")).isEqualTo(new RetryPolicy(3, Duration.ofMillis(50), false, true));
        assertThat(policy("backoff", "0").backoff()).isZero();
        assertThat(policy("max-attempts", "1").maxAttempts()).isEqualTo(1);
    }

    @Test
    void invalidSettingsFailNamingTheProperty() {
        assertThatThrownBy(() -> policy("max-attempts", "0"))
                .hasMessageContaining("modular.transport.retry.max-attempts=0 is not a positive integer; use 1 for no retries");
        assertThatThrownBy(() -> policy("max-attempts", "many")).hasMessageContaining("modular.transport.retry.max-attempts=many");
        assertThatThrownBy(() -> policy("backoff", "-1s")).hasMessageContaining("modular.transport.retry.backoff=-1s is negative; use 0 for no delay");
        assertThatThrownBy(() -> policy("on", "connect,timeout"))
                .hasMessageContaining("names 'timeout'")
                .hasMessageContaining("connect and not-served");
    }

    @Test
    void anInvalidPolicyFailsWhenTheTransportIsBuiltNotOnTheFirstFailure() {
        MockEnvironment environment = new MockEnvironment().withProperty("modular.transport.retry.on", "everything");
        ModularProperties properties = new ModularProperties(environment);

        assertThatThrownBy(() -> new InternalRestTransport(ModularTransportSupport.restClient(properties),
                ModularTransportSupport.objectMapper(), properties)).hasMessageContaining("modular.transport.retry.on=everything");
    }

    @Test
    void onlyFailuresToConnectAreConnectFailures() {
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new ConnectException("Connection refused")))).isTrue();
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new UnknownHostException("nope")))).isTrue();
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new NoRouteToHostException()))).isTrue();
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new SocketTimeoutException("Connect timed out")))).isTrue();

        // Once connected, nothing says the remote didn't run the call.
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(InternalRestTransport.isConnectFailure(new ResourceAccessException("x", new SocketException("Connection reset")))).isFalse();
        assertThat(InternalRestTransport.isConnectFailure(new IllegalStateException("boom"))).isFalse();
    }
}
