dependencies {
    implementation(project(":modular-core"))

    // Compares @ServiceVersion/@AddedIn/@DeprecatedSince version strings as semantic versions
    // instead of plain integers -- coerce() accepts bare integers ("1" -> 1.0.0) so existing
    // version strings keep working, while also allowing real "1.2.0"-style versions. Pulls in
    // only org.jspecify:jspecify (nullability annotations, no runtime behavior) transitively.
    implementation("org.semver4j:semver4j:6.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
