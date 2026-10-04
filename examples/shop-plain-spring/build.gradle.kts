plugins {
    application
}

dependencies {
    implementation(project(":examples:shop-contracts"))
    runtimeOnly(project(":examples:shop-services"))
    implementation(project(":henge-spring"))
    implementation("org.springframework:spring-context")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClass.set("digital.demilich.henge.examples.plainspring.ExamplePlainSpringApp")
}
