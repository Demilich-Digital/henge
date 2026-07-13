dependencies {
    api(project(":modular-spring"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testAnnotationProcessor(project(":modular-processor"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
