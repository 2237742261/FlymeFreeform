plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val flymeFreeformVersionCode = providers.gradleProperty("flymeFreeformVersionCode").get().toInt()
val flymeFreeformVersionName = providers.gradleProperty("flymeFreeformVersionName").get()

android {
    namespace = "com.eta.flymefreeform"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.eta.flymefreeform"
        minSdk = 35
        targetSdk = 36
        versionCode = flymeFreeformVersionCode
        versionName = flymeFreeformVersionName
    }

    // @author bomo release 版签名用源码根的 release.keystore（内容 = 一直给 debug 版签名的那把
    // 调试密钥，SHA-256 47978138…0337）。**故意与 debug 版同签名**：这样 `pm install -r` 能直接
    // 覆盖安装、不丢配置；换成新密钥就必须先卸载（会清掉固定应用等设置）。
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8：模块类名靠 META-INF/xposed/java_init.list 按字符串查找，
            // 必须显式 keep，否则会被改名/裁掉（LSPosed 找不到入口就完全不注入）。
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }

    lint {
        // release 的 lintVital 会误报 `MainActivity must extend android.app.Activity`
        // （它继承的是 androidx.activity.ComponentActivity；静态分析在 R8 产物上解析不到父类）。
        // 只关这一条，别整体关掉 checkReleaseBuilds。
        disable += "Instantiatable"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { version -> "FlymeFreeform-$version$suffix.apk" },
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.ui)
    testImplementation(libs.junit)
}
