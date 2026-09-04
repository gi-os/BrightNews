plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }

        // Sideload signing key, supplied by CI through the environment. Never committed.
        // Android only accepts an update signed by the same key that installed the app, so this
        // keystore must stay the same for the life of the install.
        create("sideload") {
            val keystore = System.getenv("LIGHTRSS_KEYSTORE_FILE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("LIGHTRSS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIGHTRSS_KEY_ALIAS") ?: "lightrss"
                keyPassword = System.getenv("LIGHTRSS_KEY_PASSWORD")
                    ?: System.getenv("LIGHTRSS_KEYSTORE_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Release builds use the sideload key when CI provides one, and the SDK development key
    // otherwise, so a local ./gradlew :tool:assembleRelease still works with no setup.
    val hasSideloadKey = !System.getenv("LIGHTRSS_KEYSTORE_FILE").isNullOrBlank()

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        // Crash reports file themselves to light-reports, as the other Bright* apps do. The
        // token is issues:write on that one repo and ships inside a sideloadable APK, so it is
        // public by construction; blank in a local build, and the report screen then only shows.
        buildConfigField("String", "REPORT_TOKEN", "\"${System.getenv("REPORT_TOKEN") ?: ""}\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasSideloadKey) "sideload" else "lightsdkDev")
        }
    }

    // The Settings screen shows BuildConfig.VERSION_NAME, so the version on screen is the one
    // the plugin read from lighttool.toml rather than a string somebody has to remember to edit.
    buildFeatures {
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    // QR scanning. The SDK composable does the work, but declaring the stack here keeps the
    // classes on the tool's compile and runtime classpath and out of R8's reach.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Newsletter rendering. Also on the unit-test classpath: NewsletterHtml is the one piece of
    // the Gmail path that can be tested without a device or a mailbox.
    implementation(libs.jsoup)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.jsoup)
    ksp(libs.androidx.room.compiler)
}
