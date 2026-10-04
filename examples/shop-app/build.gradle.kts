plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    // The app compiles against the contracts alone; the implementations are only on the runtime
    // classpath. ShopController can't reach past an interface, because nothing else is there to reach.
    implementation(project(":examples:shop-contracts"))
    runtimeOnly(project(":examples:shop-services"))
    implementation(project(":henge-spring-boot-starter"))
    // Only used with henge.store.type=redis: the shared ephemeral store.
    runtimeOnly(project(":henge-redis"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("digital.demilich.henge.examples.shop.app.ShopApp")
}
