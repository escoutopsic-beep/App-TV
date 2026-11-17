plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de assinatura fixa (vem dos segredos do GitHub no workflow). Sem ela, usa a chave de debug.
val releaseKeystore: File? = System.getenv("FXTV_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "cc.framextv.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.framextv.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 12
        versionName = "0.7.5"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("FXTV_KEYSTORE_PASSWORD")
                keyAlias = "framextv"
                keyPassword = System.getenv("FXTV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/androidx.*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    lint {
        // O build de CI não deve falhar por avisos de loja (o app é instalado manualmente na TV).
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
