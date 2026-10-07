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

// The five library modules are published; the examples are not.
val published = setOf("henge-core", "henge-processor", "henge-spring", "henge-spring-boot-starter", "henge-redis")

configure(subprojects.filter { it.name in published }) {
    apply(plugin = "maven-publish")

    java {
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                // Publish the versions the Spring Boot BOM resolved, not a bare BOM import.
                versionMapping {
                    usage("java-api") { fromResolutionOf("runtimeClasspath") }
                    usage("java-runtime") { fromResolutionResult() }
                }
                pom {
                    name = project.name
                    description = "Henge: ${project.name}"
                    url = "https://github.com/Demilich-Digital/henge"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "https://opensource.org/licenses/MIT"
                        }
                    }
                    developers {
                        developer {
                            name = "Brendan Benshoof"
                            organization = "Demilich Digital"
                        }
                    }
                    scm {
                        connection = "scm:git:https://github.com/Demilich-Digital/henge.git"
                        developerConnection = "scm:git:ssh://git@github.com/Demilich-Digital/henge.git"
                        url = "https://github.com/Demilich-Digital/henge"
                    }
                }
            }
        }
    }
}
