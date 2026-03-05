plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "7.0.2"
}

group = "dev.peekapi"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.2.0")
    compileOnly("org.springframework:spring-web:6.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "PeekAPI Java SDK",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
    }
}

spotless {
    java {
        googleJavaFormat("1.25.2")
        removeUnusedImports()
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "PeekAPI Java SDK"
                description = "Zero-dependency Java SDK for PeekAPI — Jakarta Servlet Filter and Spring Boot auto-configuration for API analytics"
                url = "https://github.com/peekapi-dev/sdk-java"
                inceptionYear = "2025"

                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }

                developers {
                    developer {
                        name = "PeekAPI"
                        email = "support@peekapi.dev"
                        url = "https://peekapi.dev"
                    }
                }

                scm {
                    url = "https://github.com/peekapi-dev/sdk-java"
                    connection = "scm:git:git://github.com/peekapi-dev/sdk-java.git"
                    developerConnection = "scm:git:ssh://github.com:peekapi-dev/sdk-java.git"
                }

                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/peekapi-dev/community/issues"
                }
            }
        }
    }

    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // CI: in-memory PGP key via ORG_GRADLE_PROJECT_signingKey / ORG_GRADLE_PROJECT_signingPassword
    // Local: gpg command (uses gpg agent)
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")
    } else {
        useGpgCmd()
    }
    sign(publishing.publications["mavenJava"])
}

// Only require signing when publishing (not during tests)
tasks.withType<Sign>().configureEach {
    onlyIf {
        gradle.taskGraph.allTasks.any { it.name.startsWith("publish") }
    }
}
