plugins {
    `java-library`
    id("com.diffplug.spotless") version "7.0.2"
}

group = "dev.peekapi"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
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

spotless {
    java {
        googleJavaFormat("1.25.2")
        removeUnusedImports()
    }
}
