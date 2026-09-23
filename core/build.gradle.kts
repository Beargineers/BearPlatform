import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no FTC SDK or Android dependencies, so everything here is unit-testable on a laptop.
plugins {
    kotlin("jvm")
    `java-library`
}

group = "org.beargineers.platform"

// Control Hub runs Android 7 (API 24); keep bytecode at Java 8 like the FTC SDK modules do.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
