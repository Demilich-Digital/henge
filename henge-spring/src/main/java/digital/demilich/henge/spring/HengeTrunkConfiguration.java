package digital.demilich.henge.spring;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Serves the trunk, {@code {henge.server.path-prefix}/_trunk}, that frontends open channels over. Registered
 * by {@link HengeServiceRegistrar}, and only when {@code spring-websocket} is present and a service this
 * process can host has a channel method, so a node that uses no channels carries none of it. Needs a
 * servlet web application, and is off with {@code henge.server.enabled=false}, like {@code /_henge}.
 */
@Configuration
@EnableWebSocket
@Conditional(HengeTrunkConfiguration.Serves.class)
class HengeTrunkConfiguration implements WebSocketConfigurer {

    private final HengeServiceRegistry registry;
    private final HengeProperties properties;
    private final TrunkServer trunkServer;

    HengeTrunkConfiguration(HengeServiceRegistry registry, Environment environment, ObjectProvider<SystemMetrics> metrics) {
        this.registry = registry;
        this.properties = new HengeProperties(environment);
        this.trunkServer = new TrunkServer(registry, HengeTransportSupport.objectMapper(), properties.getChannelSettings(),
                metrics.getIfAvailable(() -> SystemMetrics.NONE));
    }

    /** The trunk's handler, as a bean so the number of trunks it holds can be read. */
    @Bean
    TrunkServer hengeTrunkServer() {
        return trunkServer;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry handlers) {
        handlers.addHandler(trunkServer, properties.getServerPathPrefix() + TrunkFrame.PATH)
                .addInterceptors(new Handshake(registry, SharedSecret.from(properties)));
    }

    /**
     * Refuses a handshake before the upgrade: {@code 403} without the secret (when one is configured),
     * {@code 503} while this node isn't ready. The latter is also what {@code HengeNotReadyFilter} answers
     * under Boot; it is here for an application that doesn't have it.
     */
    private record Handshake(HengeServiceRegistry registry, SharedSecret secret) implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                Map<String, Object> attributes) {
            if (!secret.accepts(request.getHeaders().getFirst(HengeDispatcherController.SECRET_HEADER))) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            if (!registry.isReady()) {
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                Exception exception) {
        }
    }

    static final class Serves implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment() instanceof StandardServletEnvironment
                    && !"false".equalsIgnoreCase(context.getEnvironment().getProperty("henge.server.enabled"));
        }
    }
}
