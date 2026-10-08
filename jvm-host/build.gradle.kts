plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

group = "com.pockethost"
version = "1.0.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.pockethost.jvmhost.MainKt")
}

tasks.withType<Test> {
    useJUnit()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
