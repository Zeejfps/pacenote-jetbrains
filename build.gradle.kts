import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.diffdino"
version = "0.1.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        val local = providers.gradleProperty("localIdePath").orNull
        if (local != null && file(local).exists()) local(local) else webstorm("2026.2.3")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    implementation("org.jetbrains:markdown:0.7.3")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val webStorm = providers.gradleProperty("localIdePath").orNull
            if (webStorm != null && file(webStorm).exists()) local(webStorm) else create(IntelliJPlatformType.WebStorm, "2026.2.3")
            val rider = providers.gradleProperty("localRiderPath").orNull
            if (rider != null && file(rider).exists()) local(rider) else create(IntelliJPlatformType.Rider, "2026.2.3")
        }
    }
}

// The plugin needs only the platform and Git, so it runs in Rider as well; `runRider` starts it there.
intellijPlatformTesting {
    runIde {
        register("runRider") {
            val rider = providers.gradleProperty("localRiderPath").orNull
            if (rider != null && file(rider).exists()) localPath = file(rider)
            else {
                type = IntelliJPlatformType.Rider
                version = "2026.2.3"
            }
        }
    }
}
