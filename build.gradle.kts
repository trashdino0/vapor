plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.vapor"
version = "0.2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    implementation("org.ow2.asm:asm-commons:9.11.1")
    implementation("org.ow2.asm:asm-analysis:9.11.1")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("vapor")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "dev.vapor.Main"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
