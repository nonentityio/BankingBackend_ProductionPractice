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

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

sourceSets {
    create("loadTest") {
        kotlin.srcDir("src/loadTest/kotlin")
        compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("loadTest") {
    group = "verification"
    description = "Runs payment load test and prints RPS metrics"
    classpath = sourceSets["loadTest"].runtimeClasspath
    mainClass.set("org.eltech.banking.LoadTestKt")
}

application {
    mainClass.set("org.eltech.banking.MainKt")
}

tasks.register("stage") {
    dependsOn("installDist")
}
