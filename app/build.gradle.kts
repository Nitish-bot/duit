import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val DevelopmentApplicationId = "com.gaylord.duit.dev"

data class DevelopmentFirebaseConfiguration(
    val applicationId: String = "",
    val apiKey: String = "",
    val projectId: String = "",
    val gcmSenderId: String = "",
    val storageBucket: String = "",
    val googleServerClientId: String = "",
) {
    val isConfigured: Boolean
        get() =
            applicationId.isNotBlank() &&
                apiKey.isNotBlank() &&
                projectId.isNotBlank() &&
                googleServerClientId.isNotBlank()
}

private fun Map<*, *>.mapValue(key: String): Map<*, *>? = this[key] as? Map<*, *>

private fun Map<*, *>.listValue(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any>()

private fun Map<*, *>.stringValue(key: String): String = this[key] as? String ?: ""

private fun loadDevelopmentFirebaseConfiguration(
    configurationFile: File,
): DevelopmentFirebaseConfiguration {
    if (!configurationFile.isFile) return DevelopmentFirebaseConfiguration()

    val root = JsonSlurper().parse(configurationFile) as? Map<*, *>
        ?: error("Development Firebase configuration must contain a JSON object")
    val project = root.mapValue("project_info")
        ?: error("Development Firebase configuration is missing project_info")
    val client = root.listValue("client")
        .filterIsInstance<Map<*, *>>()
        .firstOrNull { candidate ->
            candidate
                .mapValue("client_info")
                ?.mapValue("android_client_info")
                ?.stringValue("package_name") == DevelopmentApplicationId
        }
        ?: error(
            "Development Firebase configuration does not contain an Android client for " +
                DevelopmentApplicationId,
        )
    val clientInfo = client.mapValue("client_info")
        ?: error("Development Firebase Android client is missing client_info")
    val apiKey = client.listValue("api_key")
        .filterIsInstance<Map<*, *>>()
        .firstNotNullOfOrNull { it.stringValue("current_key").takeIf(String::isNotBlank) }
        ?: error("Development Firebase Android client is missing an API key")
    val googleServerClientId = client.listValue("oauth_client")
        .filterIsInstance<Map<*, *>>()
        .firstOrNull { it["client_type"].toString() == "3" }
        ?.stringValue("client_id")
        ?.takeIf(String::isNotBlank)
        ?: error("Development Firebase Android client is missing a web OAuth client")

    return DevelopmentFirebaseConfiguration(
        applicationId = clientInfo.stringValue("mobilesdk_app_id"),
        apiKey = apiKey,
        projectId = project.stringValue("project_id"),
        gcmSenderId = project.stringValue("project_number"),
        storageBucket = project.stringValue("storage_bucket"),
        googleServerClientId = googleServerClientId,
    ).also { configuration ->
        check(configuration.isConfigured) {
            "Development Firebase configuration is missing a required Firebase value"
        }
    }
}

// Optional Pairing backend (a Supabase Edge Function). Read from the ignored
// local.properties as `duit.pairingBackendUrl`, or from -Pduit.pairingBackendUrl. Empty means
// Pairing reports an unavailable backend. The value is never printed.
private val PairingBackendPathSuffix = "/functions/v1/pairing"
private val LocalPairingBackendHosts = listOf("10.0.2.2", "127.0.0.1", "localhost")

private fun validatedPairingBackendUrl(configured: String): String {
    val url = configured.trim().trimEnd('/')
    if (url.isEmpty()) return ""
    val secure = url.startsWith("https://") && url.removePrefix("https://").isNotBlank()
    val local =
        LocalPairingBackendHosts.any { host ->
            url == "http://$host" ||
                url.startsWith("http://$host/") ||
                url.startsWith("http://$host:")
        }
    // The App Check-free `pairing-local` function is allowed only on a local address.
    val pathMatches =
        url.endsWith(PairingBackendPathSuffix) || (local && url.endsWith("$PairingBackendPathSuffix-local"))
    check((secure || local) && pathMatches) {
        "duit.pairingBackendUrl must be https://<host>$PairingBackendPathSuffix " +
            "(or http://10.0.2.2, 127.0.0.1 or localhost for a local serve); " +
            "the configured value is not shown. Fix or remove it in local.properties."
    }
    return url
}

private fun String.asBuildConfigString(): String =
    buildString {
        append('"')
        this@asBuildConfigString.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val developmentFirebaseConfigurationFile =
    providers.gradleProperty("duit.devFirebaseConfig")
        .map(::file)
        .getOrElse(file("src/dev/google-services.json"))
val developmentFirebaseConfiguration =
    loadDevelopmentFirebaseConfiguration(developmentFirebaseConfigurationFile)
val developmentPairingBackendUrl =
    validatedPairingBackendUrl(
        providers.gradleProperty("duit.pairingBackendUrl").orNull
            ?: file("../local.properties")
                .takeIf(File::isFile)
                ?.let { propertiesFile ->
                    Properties()
                        .also { properties ->
                            propertiesFile.bufferedReader().use(properties::load)
                        }.getProperty("duit.pairingBackendUrl")
                }
            ?: "",
    )

android {
    namespace = "com.gaylord.duit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gaylord.duit"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField(
                "boolean",
                "FIREBASE_CONFIGURED",
                developmentFirebaseConfiguration.isConfigured.toString(),
            )
            buildConfigField(
                "String",
                "FIREBASE_APPLICATION_ID",
                developmentFirebaseConfiguration.applicationId.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "FIREBASE_API_KEY",
                developmentFirebaseConfiguration.apiKey.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "FIREBASE_PROJECT_ID",
                developmentFirebaseConfiguration.projectId.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "FIREBASE_GCM_SENDER_ID",
                developmentFirebaseConfiguration.gcmSenderId.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "FIREBASE_STORAGE_BUCKET",
                developmentFirebaseConfiguration.storageBucket.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "GOOGLE_SERVER_CLIENT_ID",
                developmentFirebaseConfiguration.googleServerClientId.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "PAIRING_BACKEND_URL",
                developmentPairingBackendUrl.asBuildConfigString(),
            )
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
            buildConfigField("String", "FIREBASE_APPLICATION_ID", "".asBuildConfigString())
            buildConfigField("String", "FIREBASE_API_KEY", "".asBuildConfigString())
            buildConfigField("String", "FIREBASE_PROJECT_ID", "".asBuildConfigString())
            buildConfigField("String", "FIREBASE_GCM_SENDER_ID", "".asBuildConfigString())
            buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "".asBuildConfigString())
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "".asBuildConfigString())
            buildConfigField("String", "PAIRING_BACKEND_URL", "".asBuildConfigString())
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    add("devImplementation", platform(libs.firebase.bom))
    add("devImplementation", libs.firebase.auth)
    add("devImplementation", libs.firebase.appcheck.debug)
    add("devImplementation", libs.firebase.firestore)
    add("devImplementation", libs.firebase.functions)
    add("devImplementation", libs.androidx.credentials)
    add("devImplementation", libs.androidx.credentials.play.services.auth)
    add("devImplementation", libs.androidx.datastore.preferences)
    add("devImplementation", libs.googleid)
    add("devImplementation", libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
