plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import groovy.json.JsonSlurper
import java.util.Properties

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(::load)
    }
}

val firebaseConfigFile = project.file("google-services.json")
val firebaseConfig = if (firebaseConfigFile.exists()) {
    @Suppress("UNCHECKED_CAST")
    JsonSlurper().parse(firebaseConfigFile) as? Map<String, Any?>
} else {
    null
}

val firebaseProjectInfo = firebaseConfig?.get("project_info") as? Map<*, *>
val firebaseClient = (firebaseConfig?.get("client") as? List<*>)?.firstOrNull() as? Map<*, *>
val firebaseClientInfo = firebaseClient?.get("client_info") as? Map<*, *>
val firebaseApiKeyEntry = (firebaseClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>

fun firebaseConfigValue(localKey: String, fallbackValue: String?): String =
    localProperties.getProperty(localKey)?.takeIf { it.isNotBlank() }
        ?: fallbackValue.orEmpty()

val firebaseApiKey = firebaseConfigValue(
    localKey = "FIREBASE_API_KEY",
    fallbackValue = firebaseApiKeyEntry?.get("current_key") as? String
)
val firebaseAppId = firebaseConfigValue(
    localKey = "FIREBASE_APP_ID",
    fallbackValue = firebaseClientInfo?.get("mobilesdk_app_id") as? String
)
val firebaseProjectId = firebaseConfigValue(
    localKey = "FIREBASE_PROJECT_ID",
    fallbackValue = firebaseProjectInfo?.get("project_id") as? String
)
val firebaseSenderId = firebaseConfigValue(
    localKey = "FIREBASE_GCM_SENDER_ID",
    fallbackValue = firebaseProjectInfo?.get("project_number") as? String
)

android {
    namespace = "com.spshin.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spshin.phone"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"$firebaseApiKey\""
        )
        buildConfigField(
            "String",
            "FIREBASE_APP_ID",
            "\"$firebaseAppId\""
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            "\"$firebaseProjectId\""
        )
        buildConfigField(
            "String",
            "FIREBASE_GCM_SENDER_ID",
            "\"$firebaseSenderId\""
        )
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
