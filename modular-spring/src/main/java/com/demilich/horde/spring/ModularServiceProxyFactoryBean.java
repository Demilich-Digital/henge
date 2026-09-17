package com.demilich.horde.spring;

import com.demilich.horde.core.ServiceTransport;
import java.lang.reflect.Proxy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Produces the JDK dynamic proxy that stands in for a {@code @ModularService} interface whose
 * implementation lives in another process. Registered directly as a bean definition by
 * {@link ModularServiceRegistrar}, so it looks up its collaborators from the context lazily
 * (at {@link #getObject()} time) rather than via constructor injection.
 */
class ModularServiceProxyFactoryBean implements FactoryBean<Object>, ApplicationContextAware {

    private final Class<?> serviceInterface;
    private final String serviceName;
    private final String serviceVersion;

    private ApplicationContext applicationContext;

    ModularServiceProxyFactoryBean(Class<?> serviceInterface, String serviceName, String serviceVersion) {
        this.serviceInterface = serviceInterface;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object getObject() {
        ServiceTransport transport = applicationContext.getBean(ServiceTransport.class);
        return Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] {serviceInterface},
                new ModularServiceInvocationHandler(serviceName, serviceVersion, serviceInterface, transport));
    }

    @Override
    public Class<?> getObjectType() {
        return serviceInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
