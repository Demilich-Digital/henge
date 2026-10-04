dependencies {
    api(project(":henge-core"))
    // Generates {Interface}Skeleton for any @HengeService interface defined here that has
    // @AddedIn/@DeprecatedSince methods -- needed wherever such interfaces are compiled, not just
    // wherever their @ServiceVersion implementations are.
    annotationProcessor(project(":henge-processor"))
}
