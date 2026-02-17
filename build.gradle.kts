plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

group = "net.opmasterleo"
version = "1.0.0"
description = "Extreme anti-totem-ghost system for high-intensity crystal PvP"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.21.0")
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "name" to rootProject.name,
            "description" to (project.description ?: "")
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    register<Jar>("devJar") {
        archiveClassifier.set("dev")
        from(sourceSets.main.get().output)
    }

}