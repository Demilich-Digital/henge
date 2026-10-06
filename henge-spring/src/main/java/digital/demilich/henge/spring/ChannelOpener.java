package digital.demilich.henge.spring;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.ServiceInvocation;

/**
 * Opens a channel method on a service that is not in this process. A {@link ServiceBinding} whose target is
 * remote uses it, when its transport is one, instead of {@code ServiceTransport.invoke}, which carries one
 * request and one response and has nothing to do with a connection that stays open.
 */
interface ChannelOpener {

    /**
     * Opens the channel and returns the handler for what the caller sends on it. Frames the service sends,
     * and its closing the channel, arrive at {@code toClient}.
     */
    ChannelHandler open(ServiceInvocation invocation, Channel toClient);
}
