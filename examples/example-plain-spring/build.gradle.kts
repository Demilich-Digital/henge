plugins {
    application
}

dependencies {
    implementation(project(":examples:example-services"))
    implementation(project(":modular-spring"))
    implementation("org.springframework:spring-context")
}

application {
    mainClass.set("digital.demilich.henge.examples.plainspring.ExamplePlainSpringApp")
}
