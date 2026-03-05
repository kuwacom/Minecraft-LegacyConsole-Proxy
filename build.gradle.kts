plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "dev.kuwa"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.netty:netty-codec:4.1.108.Final")
    implementation("io.netty:netty-transport:4.1.108.Final")
    implementation("io.netty:netty-handler:4.1.108.Final")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.slf4j:slf4j-simple:2.0.12")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.kuwa.mlcproxy.MLCProxy")
}

tasks.test {
    useJUnitPlatform()
}
