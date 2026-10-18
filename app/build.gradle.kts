plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/** Reads a value from -Pname=… / gradle.properties (never commit real values) or an env var. */
fun secret(name: String, env: String): String =
    (project.findProperty(name) as String?) ?: System.getenv(env) ?: ""

android {
    namespace = "com.financeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.financeapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Google Pay — see README "Google Pay". Empty values keep the feature disabled
        // (fails closed). PRODUCTION is used only when explicitly requested AND configured.
        buildConfigField("boolean", "GOOGLE_PAY_PRODUCTION", "false")
        buildConfigField("String", "GOOGLE_PAY_MERCHANT_ID", "\"${secret("googlePay.merchantId", "GOOGLE_PAY_MERCHANT_ID")}\"")
        buildConfigField("String", "GOOGLE_PAY_MERCHANT_NAME", "\"FinanceApp\"")
        buildConfigField("String", "GOOGLE_PAY_GATEWAY", "\"${secret("googlePay.gateway", "GOOGLE_PAY_GATEWAY")}\"")
        buildConfigField("String", "GOOGLE_PAY_GATEWAY_MERCHANT_ID", "\"${secret("googlePay.gatewayMerchantId", "GOOGLE_PAY_GATEWAY_MERCHANT_ID")}\"")
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: project.findProperty("keystoreFile")?.toString()
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("keystorePassword")?.toString()
            val keyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("keyAlias")?.toString()
            val keyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("keyPassword")?.toString()
            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // play-services-wallet merges its own activities; the classes aren't on the compile classpath.
        disable += "MissingClass"
        // AGP 8.7 lint engine vs lifecycle-lint 2.9 incompatibility (crashes the detector).
        // Safe: this project uses StateFlow, never LiveData.
        disable += "NullSafeMutableLiveData"
        htmlReport = true
        textReport = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) signingConfig = releaseConfig
            // Opt-in only, and ignored at runtime unless gateway + merchant ids are also set.
            val production = (project.findProperty("googlePay.production") as String?)?.toBoolean() ?: false
            buildConfigField("boolean", "GOOGLE_PAY_PRODUCTION", production.toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
    // Lets MigrationTestHelper read the exported schema JSON files.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // BiometricPrompt requires a FragmentActivity host.
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.splashscreen)
    // XML window themes (Theme.Material3.DayNight) for the splash/quick-add windows.
    implementation(libs.material)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines)
    implementation(libs.datastore.preferences)
    implementation(libs.biometric)
    implementation(libs.google.pay)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
