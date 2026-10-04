plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Compile classpath only sees the contracts -- example-services' impl classes are on the
    // runtime classpath alone, so this build enforces (not just documents) that DemoController
    // can only ever depend on @ModularService interfaces, never their implementations.
    implementation(project(":examples:example-contracts"))
    runtimeOnly(project(":examples:example-services"))
    implementation(project(":modular-spring-boot-starter"))
    // Only used when modular.store.type=redis; see the README's "Finding each other through Redis".
    runtimeOnly(project(":modular-redis"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("digital.demilich.henge.examples.app.ExampleApp")
}
