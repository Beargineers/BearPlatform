import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The part of the platform that talks to the FTC SDK: opmodes, hardware, the robot's files.
plugins {
    id("com.android.library")
    kotlin("android")
}

group = "org.beargineers.platform"

android {
    namespace = "org.beargineers.platform.ftc"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Unit tests run on the laptop with fake devices; Android calls inside the SDK just return defaults there
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

val ftcSdkVersion = "11.1.0"

dependencies {
    api(project(":core"))

    // The season app brings the FTC SDK, so compile against it without forcing a version on the app
    compileOnly("org.firstinspires.ftc:RobotCore:$ftcSdkVersion")
    compileOnly("org.firstinspires.ftc:Hardware:$ftcSdkVersion")

    testImplementation(kotlin("test-junit"))
    testImplementation("org.firstinspires.ftc:RobotCore:$ftcSdkVersion")
    testImplementation("org.firstinspires.ftc:Hardware:$ftcSdkVersion")
}
