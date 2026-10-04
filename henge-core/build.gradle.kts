dependencies {
    // The only Spring dependency in this module: @ServiceVersion is meta-annotated
    // @Qualifier so Spring's autowiring machinery recognizes it as a qualifier type. Just
    // spring-beans, not the full framework.
    implementation("org.springframework:spring-beans")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
