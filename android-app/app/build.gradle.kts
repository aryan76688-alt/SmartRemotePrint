plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.smartremote.print"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartremote.print"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.2.0"
        manifestPlaceholders["appLabel"] = "Marg Cloud"
    }

    flavorDimensions += "device"
    productFlavors {
        create("tablet") {
            dimension = "device"
            manifestPlaceholders["appLabel"] = "Marg Cloud Tablet"
            buildConfigField("String", "DEVICE_TYPE", "\"tablet\"")
        }
        create("mobile") {
            dimension = "device"
            applicationIdSuffix = ".mobile"
            manifestPlaceholders["appLabel"] = "Marg Cloud Mobile"
            buildConfigField("String", "DEVICE_TYPE", "\"mobile\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = false
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
