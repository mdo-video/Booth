plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.mdo.booth.camera"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.mdo.booth.camera"
        minSdk = 29
        targetSdk = 34
        versionCode = (project.findProperty("appVersionCode") as String).toInt()
        versionName = project.findProperty("appVersionName") as String
        buildConfigField("String", "UPDATE_TOKEN", "\"${project.findProperty("updateToken") ?: ""}\"")
    }
    signingConfigs {
        create("booth") {
            storeFile = rootProject.file("booth.keystore")
            storePassword = "android"
            keyAlias = "booth"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("booth") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("io.ktor:ktor-server-partial-content:2.3.12")
    implementation("io.ktor:ktor-server-auto-head-response:2.3.12")
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
