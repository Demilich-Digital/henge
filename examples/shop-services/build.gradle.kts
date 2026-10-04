dependencies {
    api(project(":examples:shop-contracts"))
    // Checks each @ServiceVersion implementation against its interface: a method in its version's
    // range must really be implemented, not left to the generated skeleton.
    annotationProcessor(project(":henge-processor"))

    // Inventory keeps its stock in a database, through a pool sized from its lease.
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("com.h2database:h2")
    implementation("org.slf4j:slf4j-api")
}
