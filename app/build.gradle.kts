import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 署名情報はリポジトリに含めない。次の順で探し、無ければ未署名でビルドする。
//  1) 環境変数 MEDIADL_STORE_FILE / MEDIADL_STORE_PASSWORD / MEDIADL_KEY_ALIAS / MEDIADL_KEY_PASSWORD（CI のシークレット用）
//  2) 環境変数 MEDIADL_KEYSTORE_PROPS が指す keystore.properties
//  3) プロジェクト直下の keystore.properties（.gitignore 済み）
val keystoreProps = Properties().apply {
    val f = System.getenv("MEDIADL_KEYSTORE_PROPS")?.let { file(it) } ?: rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val signStoreFile = signingValue("MEDIADL_STORE_FILE", "storeFile")
val signStorePassword = signingValue("MEDIADL_STORE_PASSWORD", "storePassword")
val signKeyAlias = signingValue("MEDIADL_KEY_ALIAS", "keyAlias")
val signKeyPassword = signingValue("MEDIADL_KEY_PASSWORD", "keyPassword")
val hasSigning = listOf(signStoreFile, signStorePassword, signKeyAlias, signKeyPassword).all { it != null } &&
    file(signStoreFile!!).exists()
if (!hasSigning) logger.warn("MediaDL: 署名情報が見つからないため、release は未署名でビルドされます")

android {
    namespace = "jp.personal.mediadl"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.personal.mediadl"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // youtubedl-android は Python / ffmpeg のネイティブライブラリを展開して使うため必須
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
