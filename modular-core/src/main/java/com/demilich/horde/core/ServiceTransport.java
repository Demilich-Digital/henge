package com.demilich.horde.core;

/**
 * Pluggable dispatch mechanism for a remote {@link ModularService} call. {@code internal-rest}
 * is the first implementation; a {@code grpc} transport can be added later behind the same SPI
 * without any change to user-facing annotations or generated proxies.
 */
public interface ServiceTransport {

    Object invoke(ServiceInvocation invocation) throws Exception;
}
