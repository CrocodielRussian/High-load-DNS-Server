import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    application
}

group = "com.snail"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.netty:netty-bom:4.2.16.Final"))
    implementation("io.netty:netty-buffer")
    implementation("io.netty:netty-transport")
    implementation("io.netty:netty-transport-classes-epoll")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("org.slf4j:slf4j-api:2.0.18")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.28")
    runtimeOnly("io.netty:netty-transport-native-epoll:4.2.16.Final:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:4.2.16.Final:linux-aarch_64")

    testImplementation(platform("org.junit:junit-bom:5.13.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.snail.dnslb4j.DnsServer"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds an executable JAR containing all runtime dependencies."
    archiveClassifier = "jar-with-dependencies"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

val packageZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds a distributable ZIP with the executable JAR and configuration."
    archiveClassifier = "package"
    dependsOn(fatJar)

    into("${project.name}-${project.version}") {
        from(fatJar)
        from("config") {
            into("config")
        }
    }
}

tasks.build {
    dependsOn(fatJar, packageZip)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("io.netty.leakDetection.level", "paranoid")
}
