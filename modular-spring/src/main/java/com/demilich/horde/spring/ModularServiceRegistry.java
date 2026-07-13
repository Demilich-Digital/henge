package com.demilich.horde.spring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds a {@link ModularServiceDescriptor} for every {@code @ModularService} interface that
 * THIS process hosts an {@link ModularMode#EMBEDDED} implementation of. Consulted by
 * {@link ModularDispatcherController} to decide whether an incoming dispatch request is
 * actually servable here.
 */
public class ModularServiceRegistry {

    private final Map<String, ModularServiceDescriptor> byKey;

    ModularServiceRegistry(List<ModularServiceDescriptor> descriptors) {
        this.byKey = descriptors.stream().collect(Collectors.toMap(ModularServiceRegistry::key, Function.identity()));
    }

    public Optional<ModularServiceDescriptor> find(String name, String version) {
        return Optional.ofNullable(byKey.get(key(name, version)));
    }

    private static String key(ModularServiceDescriptor descriptor) {
        return key(descriptor.name(), descriptor.version());
    }

    private static String key(String name, String version) {
        return name + "/" + version;
    }
}
