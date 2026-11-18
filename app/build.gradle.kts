plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("de.undercouch.download") version "5.6.0"
}

val modelAssetsDir = layout.projectDirectory.dir("src/main/assets").asFile

val downloadPersonDetector by tasks.registering(
    de.undercouch.gradle.tasks.download.Download::class
) {
    src("https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite2_detection_metadata_1.tflite")
    dest(file("${modelAssetsDir}/efficientdet-lite2.tflite"))
    overwrite(false)
}

tasks.named("preBuild") {
    dependsOn(downloadPersonDetector)
}

dependencies {
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}

android {
    namespace = "com.example.photobatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.photobatch"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += "tflite"
    }
}
