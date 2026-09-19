plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.flac"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.androidx.media3.decoder)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.extractor)
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
