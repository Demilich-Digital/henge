plugins {
    // EphemeralDatastoreContract: the store contract's tests, which every store's own tests extend.
    `java-test-fixtures`
}

dependencies {
    // The only Spring dependency in this module: @ServiceVersion is meta-annotated
    // @Qualifier so Spring's autowiring machinery recognizes it as a qualifier type. Just
    // spring-beans, not the full framework.
    implementation("org.springframework:spring-beans")

    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testFixturesImplementation("org.assertj:assertj-core")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
