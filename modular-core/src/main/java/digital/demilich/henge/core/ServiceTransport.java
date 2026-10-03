package digital.demilich.henge.core;

/**
 * The seam between a generated proxy and the wire for a remote {@link ModularService} call.
 * {@code internal-rest} is the only implementation, and this is not an extension point today:
 * the serving side and the bean wiring are REST-specific too, and the proxy resolves exactly one
 * {@code ServiceTransport} bean by type.
 */
public interface ServiceTransport {

    Object invoke(ServiceInvocation invocation) throws Exception;
}
