plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wifeassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wisal.app"
        minSdk = 26
        targetSdk = 35
        // رقم الإصدار بيزيد تلقائياً من رقم بناء الـ CI (VERSION_CODE) عشان
        // التحديثات تتثبّت فوق بعضها. محلياً بيرجع لـ 1.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        // libsignal-android بتحمل مكتبة native (~74MB) لكل ABI. مفيش تقسيم APK
        // لكل ABI في التوزيع الحالي (ملف APK واحد)، فلو سبنا كل الـ ABIs
        // الحجم بيقفز لأكتر من 600MB. arm64-v8a هو الغالبية الساحقة من
        // أجهزة أندرويد الحقيقية بعد minSdk=26 (2017+) — قرار واعي وموثّق،
        // مش تقصير: التطبيق بقى 64-bit بس على أجهزة ARM.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // نسخة الاختبار من مكتبة libsignal (libsignal_jni_testing.so) بنفس
            // حجم النسخة الحقيقية تقريبًا ومالهاش أي داعي في تطبيق مُثبَّت.
            excludes += "**/libsignal_jni_testing.so"
        }
    }

    // توقيع نسخة الـ release. بيقرأ من متغيّرات البيئة (بتتحط في الـ CI من
    // GitHub Secrets). A missing release key leaves release output unsigned;
    // never create a replacement identity or present a debug key as an upgrade.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libsignal-android بيطلب desugaring حتى مع minSdk=26 (متطلب معلن في
        // aar metadata بتاعته، مش مرتبط بمستوى API الفعلي).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // R8: يشيل الكود والموارد غير المستخدمة (منها آلاف أيقونات
            // material-icons-extended) — تصغير كبير للـ APK. قواعد الحفظ في
            // proguard-rules.pro بتحمي تخزين kotlinx.serialization المحلي.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ksPath = System.getenv("KEYSTORE_FILE")
            signingConfig = if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric محتاج موارد أندرويد عشان يشغّل اختبارات طبقة البيانات على الـ JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // تشفير مفتاح Groq على الجهاز (Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // بروتوكول Signal الحقيقي (ADR-002 §signal variant، AGPL-3.0). AAR جاهز
    // بمكتبة native مبنية مسبقًا لكل ABI — مفيش NDK/Rust توليف محلي مطلوب.
    implementation("org.signal:libsignal-android:0.86.5")
    // مطلوبة من libsignal-android (aar metadata) — راجع compileOptions فوق.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    // سيرفر HTTP وهمي محلي — بيختبر DirectRelayClient ضد طلبات/ردود حقيقية بدون شبكة فعلية.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // اختبارات طبقة البيانات على الـ JVM (بدون emulator) — بتحرس دوران JSON اللي R8 ممكن يكسره.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    // اختبار Compose UI على الـ JVM عبر Robolectric (smoke) — بيتأكد إن الـ UI بيركّب ويتفاعل.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
