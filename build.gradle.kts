plugins {
    java
}

group = "de.raindancer"
version = "1.0.0"

val paperApiVersion = "26.1.2.build.74-stable"

// Minimum server version APM declares in paper-plugin.yml. Paper expects a Minecraft
// version here (major.minor), not the full artifact coordinate.
val paperApiDeclaration = "26.1"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper provides the API plus gson, snakeyaml, adventure and brigadier at runtime,
    // so APM ships without a single shaded dependency.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to paperApiDeclaration)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    archiveBaseName = "apm"
    archiveClassifier = ""
    manifest {
        attributes(
            "Implementation-Title" to "Rain's Awesome Plugin Manager",
            "Implementation-Version" to project.version,
        )
    }
}
