plugins {
    application
}

dependencies {
    implementation(project(":examples:example-services"))
    implementation(project(":modular-spring"))
    implementation("org.springframework:spring-context")
}

application {
    mainClass.set("com.demilich.horde.examples.plainspring.ExamplePlainSpringApp")
}
