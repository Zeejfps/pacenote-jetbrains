import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.pacenote"
version = "0.1.10"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
    // Implementing a platform interface otherwise compiles a bridge for each of its default methods,
    // which the Plugin Verifier reports as overriding the deprecated ones.
    compilerOptions {
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    intellijPlatform {
        val local = providers.gradleProperty("localIdePath").orNull
        if (local != null && file(local).exists()) local(local) else webstorm("2026.2.3")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.libraries.markdown")
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
        changeNotes = """
            <h3>0.1.10</h3>
            <ul>
              <li>Each agent preset can start its adapter with its own command, such as a different Node or a pinned adapter version.</li>
              <li>Presets can set environment variables on top of the IDE's. <code>${'$'}NAME</code> refers to an existing variable, and the command is looked up on the PATH the preset sets.</li>
              <li>Pacenote asks before running the shell commands you list (by default <code>git push</code>, <code>npm publish</code> and <code>rm -rf</code>), or before every command if you choose.</li>
              <li>An MCP server you allow from an approval card stays allowed after a restart. You can remove it in Settings.</li>
              <li>The settings page has a new layout: the preset list sits beside a single form, and each field has its label above and its hint below.</li>
              <li>Restoring the built-in presets is now on the list's toolbar and asks before replacing presets you made.</li>
              <li>Hints match the selected agent. Errors show on the field they belong to, and Apply takes you to the first one.</li>
              <li>The page fits the width of the settings dialog.</li>
              <li>The plugin no longer uses internal or deprecated IntelliJ Platform APIs.</li>
              <li>Markdown is rendered with the IDE's own library instead of a bundled copy, which makes the download about a third smaller.</li>
            </ul>
        """.trimIndent()
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
