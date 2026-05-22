plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "io.github.stream29.idea.kts.mcp"
version = "0.1.1"

kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.12.0")
    implementation(kotlin("main-kts"))
    implementation(kotlin("scripting-jvm-host"))
}

intellijPlatform {
    pluginConfiguration {
        name.set("IdeaKtsReplMcp")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}
