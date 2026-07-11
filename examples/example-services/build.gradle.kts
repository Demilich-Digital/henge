dependencies {
    api(project(":examples:example-contracts"))
    implementation("org.springframework.boot:spring-boot-starter")
    annotationProcessor(project(":modular-processor"))
}
