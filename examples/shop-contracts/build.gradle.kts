dependencies {
    // Contracts only: the interfaces, and the records and exceptions that cross them. Callers
    // compile against this module alone.
    api(project(":henge-core"))
    // Generates InventoryServiceSkeleton, for InventoryService's @AddedIn(2) method, and checks the
    // boundary rules on every interface here.
    annotationProcessor(project(":henge-processor"))
}
