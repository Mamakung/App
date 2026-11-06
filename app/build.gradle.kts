plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services") apply false // applied conditionally below
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.room")
    kotlin("kapt")
}

// Apply the Firebase plugin only once google-services.json is present in this folder.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.smartscheduler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smartscheduler"
        minSdk = 26
        targetSdk = 34
        // เลขเวอร์ชัน = จำนวนนาทีตั้งแต่ 1 ม.ค. 2026 (UTC) ตอน build
        // build ทีหลังจึงใหม่กว่าเสมอ ไม่ว่าจะ build ใน VS Code หรือบน GitHub
        // GitHub Actions / สคริปต์ release ส่ง VERSION_CODE และ VERSION_NAME มาให้ตรงกับชื่อ Release
        val nowUtc = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        val autoCode = ((nowUtc.toEpochSecond() - 1767225600L) / 60).toInt()
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: autoCode
        versionName = System.getenv("VERSION_NAME") ?: nowUtc
            .withZoneSameInstant(java.time.ZoneId.of("Asia/Bangkok"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd-HHmm"))

        // repo ที่แอปไปเช็กอัปเดต (GitHub Releases) ต้องเป็น public
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("UPDATE_REPO") ?: "Mamakung/App"}\"")
    }

    // ทุกเวอร์ชันต้องเซ็นด้วยกุญแจเดียวกัน Android ถึงจะยอมให้ติดตั้งทับ (อัปเดต) ได้
    // GitHub Actions เขียนไฟล์ release.keystore จาก secret SIGNING_KEYSTORE_B64 ก่อน build
    val releaseKeystore = file("release.keystore")
    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "smartscheduler"
            keyAlias = "smartscheduler"
            keyPassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "smartscheduler"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    room {
        schemaDirectory("$projectDir/schemas")
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Hilt (DI)
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager + AlarmManager support
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Firebase Auth
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
