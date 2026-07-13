dependencies {
    api(project(":modular-core"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
