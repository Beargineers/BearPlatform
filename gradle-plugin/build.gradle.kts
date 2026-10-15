import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Gradle tasks for a season project that talk to the robot: sending the config, downloading match logs, starting
// commands. Plain Kotlin and Java's HTTP client, so they work wherever Gradle does, Windows included.
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka-javadoc")
}

gradlePlugin {
    plugins {
        create("bearPlatform") {
            id = "nl.beargineers.platform"
            implementationClass = "nl.beargineers.platform.gradle.BearPlatformPlugin"
            displayName = "BearPlatform"
            description = "Tasks that talk to a robot running BearPlatform: sendConfig, downloadMatchLogs, robotCommand."
        }
    }
}

mavenPublishing {
    pom {
        name.set("BearPlatform Gradle plugin")
        description.set("Gradle tasks for robots running BearPlatform, a Kotlin platform for FTC robots: send the " +
            "config, download match logs and start commands, from Android Studio or any terminal.")
    }
}

// Android Studio's Gradle runs on Java 17 or later
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // A season project's Gradle runs the plugin with its own Kotlin. BearPlatform's apps need the Android Gradle
        // plugin 8.13, which needs Gradle 8.13 or later: Kotlin 2.0 or later
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
