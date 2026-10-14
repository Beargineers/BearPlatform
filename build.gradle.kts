import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // Keep in sync with the versions used by FTC season projects that include this build
    kotlin("jvm") version "2.2.20" apply false
    kotlin("android") version "2.2.20" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.dokka-javadoc") version "2.2.0" apply false
}

// What every published module tells Maven Central about the project; see RELEASING.md
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                url.set("https://github.com/Beargineers/BearPlatform")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("beargineers")
                        name.set("Beargineers, FTC team #27628")
                        url.set("https://beargineers.nl")
                    }
                }
                scm {
                    url.set("https://github.com/Beargineers/BearPlatform")
                    connection.set("scm:git:https://github.com/Beargineers/BearPlatform.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Beargineers/BearPlatform.git")
                }
            }
        }
    }
}
