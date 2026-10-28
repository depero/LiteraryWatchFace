import literary.GenerateLiteratureResources

plugins {
    alias(libs.plugins.android.application)
}

val generateLiteratureResources = tasks.register<GenerateLiteratureResources>("generateLiteratureResources") {
    literatureFile.set(rootProject.layout.projectDirectory.file("literature/litclock_annotated.csv"))
    templateFile.set(layout.projectDirectory.file("src/main/watchface-template.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/literature/res"))
    reportFile.set(layout.buildDirectory.file("reports/literature/generation.json"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateLiteratureResources,
            GenerateLiteratureResources::getOutputDirectory
        )
    }
}

android {
    namespace = "com.example.literarywatchface"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.literarywatchface"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    enableKotlin = false
}

dependencies {
}
