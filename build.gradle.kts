plugins {
    java
}

group = "com.xzh"
version = "0.1.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.195-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("VillagerWorkstation")
    archiveVersion.set(project.version.toString())
}
