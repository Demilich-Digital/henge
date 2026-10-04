dependencies {
    api(project(":modular-core"))

    // Lettuce is Spring Boot's own Redis client, so a Boot application already has it; this module
    // doesn't need Spring itself.
    implementation("io.lettuce:lettuce-core")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
