plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 'kotlin.compose' 플러그인은 최신 Gradle에서 사용하지 않습니다.
    // 'buildFeatures { compose = true }'가 그 역할을 대신합니다.
    alias(libs.plugins.compose.compiler) // 이 줄은 삭제하거나 주석 처리하는 것이 좋습니다.
}

android {
    namespace = "com.trps02.p2pwalkietalkie"

    // --- ▼▼▼ 여기가 핵심 수정 사항 ▼▼▼ ---
    compileSdk = 34 // 36 -> 34 (안정화 버전)
    // --- ▲▲▲ 여기가 핵심 수정 사항 ▲▲▲ ---

    defaultConfig {
        applicationId = "com.trps02.p2pwalkietalkie"
        minSdk = 24
        // --- ▼▼▼ 여기가 핵심 수정 사항 ▼▼▼ ---
        targetSdk = 34 // 36 -> 34 (안정화 버전)
        // --- ▲▲▲ 여기가 핵심 수정 사항 ▲▲▲ ---
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // composeOptions 블록이 필요할 수 있습니다.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1" // (이 버전은 다를 수 있습니다)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 이전에 추가한 의존성들 (모두 올바른 상태)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}