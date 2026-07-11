dependencies {
    api(project(":modular-core"))
    // Generates {Interface}Skeleton for any @ModularService interface defined here that has
    // @AddedIn/@DeprecatedSince methods -- needed wherever such interfaces are compiled, not just
    // wherever their @ServiceVersion implementations are.
    annotationProcessor(project(":modular-processor"))
}
