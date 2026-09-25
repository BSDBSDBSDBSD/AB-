plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.duplicatesongs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.duplicatesongs"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Two versions: one with the big "built by" credit, one without.
    flavorDimensions += "branding"
    productFlavors {
        create("credited") {
            dimension = "branding"
            applicationIdSuffix = ".credited"
            buildConfigField("boolean", "SHOW_CREDIT", "true")
            resValue("string", "app_name", "שירים כפולים — אורי")
        }
        create("plain") {
            dimension = "branding"
            buildConfigField("boolean", "SHOW_CREDIT", "false")
            resValue("string", "app_name", "שירים כפולים")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
