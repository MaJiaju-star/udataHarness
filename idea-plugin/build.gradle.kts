plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.udata"
version = "0.1.6"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.6")
        pluginVerifier()
    }
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        id = "com.udata.harness.idea"
        name = "UData Harness"
        version = project.version.toString()
        description = "UData Harness coding agent: connect to a local Solon Web backend, chat, approve tools and send editor context."
        ideaVersion { sinceBuild = "243" }
        vendor { name = "UData" }
    }
    buildSearchableOptions = false
}
