plugins {
    kotlin("jvm") version "2.3.10"
    application
}

group = "org.eltech"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val vertxVersion = "5.1.2"

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-web-client")
    implementation("io.vertx:vertx-pg-client")
    implementation("io.vertx:vertx-lang-kotlin")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

kotlin {
    jvmToolchain(23)
}

application {
    mainClass.set("org.eltech.banking.MainKt")
}

tasks.register("stage") {
    dependsOn("installDist")
}
