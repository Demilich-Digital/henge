plugins {
    java
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "digital.demilich.henge"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // In CI a Docker-less skip of the Redis tests must fail the build, not pass it.
        systemProperty("henge.requireDocker", System.getenv("CI") != null)
    }
}
