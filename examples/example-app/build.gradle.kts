plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":examples:example-services"))
    implementation(project(":modular-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}

springBoot {
    mainClass.set("com.demilich.horde.examples.app.ExampleApp")
}
