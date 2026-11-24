plugins {
    java
}

val paperVersion: String by project
val apiVersion: String by project
val javaVersion: String by project
val pluginVersion: String by project

group = "cz.rounds"
version = pluginVersion

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
}

tasks.processResources {
    val props = mapOf("version" to pluginVersion, "apiVersion" to apiVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("Rounds.jar")
}
