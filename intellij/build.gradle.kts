plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.momostifler96"
version = "0.2.1"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugin("Git4Idea")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "252.*"
        }
    }
}
