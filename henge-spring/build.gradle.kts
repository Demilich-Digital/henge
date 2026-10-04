dependencies {
    api(project(":henge-core"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // Only to deserialize Guava's immutable collections when an application already has Guava;
    // never forced on one (see HengeCollectionsModule).
    compileOnly(libs.guava)
    // Only for the observation of calls and dispatches (ObservationServiceCallInterceptor and
    // ObservationServiceDispatchObserver) and the system's own meters (MicrometerSystemMetrics); an
    // application without it never loads any of them.
    compileOnly("io.micrometer:micrometer-core")
    testImplementation("io.micrometer:micrometer-core")

    // The end-to-end test that selects Redis with henge.store.type, against a real one.
    testImplementation(project(":henge-redis"))
    testImplementation("org.testcontainers:junit-jupiter")
    // Testcontainers brings slf4j-api, which makes Spring's logging shim route to SLF4J (no binding: it
    // drops everything). The logging tests capture java.util.logging, so bind SLF4J back to it.
    testRuntimeOnly("org.slf4j:slf4j-jdk14")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation(libs.guava)
    // Puts an XML message converter ahead of JSON in plain Spring MVC: the dispatch tests then prove
    // /_henge's responses don't depend on the application's converters.
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
