dependencies {
    api(project(":modular-spring"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("io.micrometer:micrometer-core")

    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("io.micrometer:micrometer-core")
    // A real tracer (Brave) with real header propagation, for the test that a trace crosses the transport.
    // Wired by hand there, not through Actuator, which would change what every other test here sees.
    testImplementation("io.micrometer:micrometer-tracing")
    testImplementation("io.micrometer:micrometer-tracing-bridge-brave")
    testImplementation("io.zipkin.brave:brave")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testAnnotationProcessor(project(":modular-processor"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
