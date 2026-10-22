plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

val asmVersion = "9.8"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // Fabric Loader, for the tests only: patches are tested against its real classes.
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
}

// The jar scanner (tools/ApiScan): checks which Java APIs the game, Fabric and mods use. Not part
// of the agent jar.
val scan: SourceSet by sourceSets.creating

dependencies {
    // ASM is shaded and relocated into the agent jar so it never collides with the ASM that
    // Fabric Loader and Mixin put on the class path.
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")

    "scanImplementation"("org.ow2.asm:asm:$asmVersion")
    "scanImplementation"("org.ow2.asm:asm-tree:$asmVersion")

    testImplementation("net.fabricmc:fabric-loader:0.19.5") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Premain-Class" to "dev.dove.agent.DoveAgent",
            "Agent-Class" to "dev.dove.agent.DoveAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "Dove Agent",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.jar {
    archiveClassifier = "slim"
}

tasks.shadowJar {
    // The launcher looks for exactly this name.
    archiveFileName = "dove-agent.jar"
    relocate("org.objectweb.asm", "dev.dove.agent.shadow.asm")
    minimize()
    exclude("module-info.class", "META-INF/versions/**/module-info.class", "META-INF/maven/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
    // As the launcher starts the game: the Windows hooks call into kernel32 and winmm.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ./gradlew apiScan --args="--jdk21 <JDK 21 home> <jar or folder>..."
tasks.register<JavaExec>("apiScan") {
    group = "verification"
    description = "Lists the Java APIs the given jars use that changed between Java 21 and 25."
    classpath = scan.runtimeClasspath
    mainClass = "dev.dove.agent.tools.ApiScan"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
}
