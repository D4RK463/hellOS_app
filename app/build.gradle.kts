plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.hellos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.hellos.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources {
            // module-info.class of the library and of Jackson are not needed on Android
            excludes += setOf("META-INF/versions/*/module-info.class", "module-info.class")
        }
    }
}

dependencies {
    // hellOS from the GitHub release, see README. The jar has no POM, so its dependency
    // (Jackson) is declared here explicitly with the version the library is built against.
    implementation(files("libs/hellOS-0.1.1.jar"))
    implementation(libs.jackson.databind)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.material)
}
