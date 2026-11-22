import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息统一从 local.properties 读取（该文件已被 .gitignore 排除，不会随仓库上传）。
// 缺少这些配置项时（例如 CI 或他人克隆代码后）自动跳过签名，改用默认调试签名。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.trae.androidlyrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trae.lyrics"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.6"
    }

    signingConfigs {
        val storePath = localProps.getProperty("signing.storeFile")
        if (storePath != null && rootProject.file(storePath).exists()) {
            create("fixed") {
                storeFile = rootProject.file(storePath)
                storePassword = localProps.getProperty("signing.storePassword")
                keyAlias = localProps.getProperty("signing.keyAlias")
                keyPassword = localProps.getProperty("signing.keyPassword")
            }
        }
    }

    buildTypes {
        val fixedSigning = signingConfigs.findByName("fixed")
        debug {
            if (fixedSigning != null) signingConfig = fixedSigning
        }
        release {
            if (fixedSigning != null) signingConfig = fixedSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
}

// AGP 9 已移除旧的 unitTestClasses / androidTestClasses 任务名，
// 但 IDE 的构建动作仍按旧名称请求，这里映射到当前的源码编译任务。
tasks.register("unitTestClasses") {
    dependsOn("compileDebugUnitTestSources")
}

tasks.register("androidTestClasses") {
    dependsOn("compileDebugAndroidTestSources")
}
