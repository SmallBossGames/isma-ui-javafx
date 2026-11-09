plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.java.modules)
}

group = "ru.isma.next.ui"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(project(":isma-ui:domain"))
    implementation(project(":isma-jvm-lib:exchange-format"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Run tests on the classpath to avoid JPMS split-package conflicts (e.g. MockK)
    (extensions.getByName("moduleOptions") as org.javamodularity.moduleplugin.extensions.TestModuleOptions).runOnClasspath = true
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED"
    )
}
