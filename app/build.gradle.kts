plugins {
    alias(libs.plugins.android.application)
}

// The release workflow passes the version with -PappVersionName=X.Y.Z (from the git tag).
// The version code is derived from it, so it always grows with the version.
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.1.0")
val appVersionCode = appVersionName.split(".").let { parts ->
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "appVersionName must be MAJOR.MINOR.PATCH, was '$appVersionName'"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor < 1000 && patch < 1000) { "minor and patch must be below 1000" }
    major * 1_000_000 + minor * 1_000 + patch
}

// Release signing from environment variables (set by the release workflow). Without them the
// release APK stays unsigned, as before.
val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "org.hellos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.hellos.app"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
