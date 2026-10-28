plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(gradleApi())
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("literature.projectRoot", layout.projectDirectory.dir("..").asFile.absolutePath)
}
