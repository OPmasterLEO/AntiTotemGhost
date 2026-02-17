plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.7"
}

group = "net.opmasterleo"
version = "1.0.0"
description = "Extreme anti-totem-ghost system for high-intensity crystal PvP"

java {
    // Java 17+ required for Paper 1.20.4+
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // paperweight-userdev provides Mojang-mapped NMS + CraftBukkit at compile time.
    // At build time, reobfJar reobfuscates to Spigot mappings for production.
    paperweight.paperDevBundle("1.20.4-R0.1-SNAPSHOT")

    // --- Test Dependencies ---
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
}

tasks {
    // Ensure the reobfuscated JAR is built on `gradle build`
    assemble {
        dependsOn(reobfJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(17)
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

    // Dev JAR — not reobfuscated, for local Paper/Folia dev server testing
    register<Jar>("devJar") {
        archiveClassifier.set("dev")
        from(sourceSets.main.get().output)
    }

    // Production JAR — reobfuscated for deployment
    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/${rootProject.name}-${project.version}.jar"))
    }
}
