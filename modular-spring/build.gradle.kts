dependencies {
    api(project(":modular-core"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework:spring-webmvc")
    // Puts an XML message converter ahead of JSON in plain Spring MVC: the dispatch tests then prove
    // /_modular's responses don't depend on the application's converters.
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
