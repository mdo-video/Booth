plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.mdo.booth.tablet"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.mdo.booth.tablet"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-partial-content:2.3.12")
    implementation("io.ktor:ktor-server-auto-head-response:2.3.12")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
