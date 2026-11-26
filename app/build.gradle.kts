import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val trialReleaseAbi = providers.gradleProperty("xcloud.trialReleaseAbi").orNull
val trialReleaseShrink = providers.gradleProperty("xcloud.trialReleaseShrink").map(String::toBooleanStrict).getOrElse(false)
check(trialReleaseAbi == null || trialReleaseAbi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
    "xcloud.trialReleaseAbi must name one packaged Android ABI"
}

android {
    namespace = "com.xcloud.tclnative"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xcloud.tclnative"
        minSdk = 24
        targetSdk = 34
        versionCode = 149
        versionName = "0.41.36-performance-trials"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = System.getenv("XCLOUD_KEYSTORE_PATH")?.let(::file)
                ?: rootProject.file(".private-signing/xcloud-debug.keystore")
            storeType = System.getenv("XCLOUD_KEYSTORE_TYPE") ?: "JKS"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            trialReleaseAbi?.let { ndk.abiFilters.add(it) }
            isMinifyEnabled = trialReleaseShrink
            if (trialReleaseShrink) {
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-trial-release.pro")
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

    lint {
        if (project.providers.gradleProperty("xcloud.lintAudit").isPresent) {
            abortOnError = false
            xmlOutput = file("${layout.buildDirectory.get()}/reports/lint-audit-debug.xml")
            htmlOutput = file("${layout.buildDirectory.get()}/reports/lint-audit-debug.html")
        } else {
            checkOnly += setOf("NewApi", "InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
        }
    }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            // Bound native graphics/SDK sandboxes across the complete Robolectric suite.
            it.maxHeapSize = "1g"
            it.maxParallelForks = 1
            it.forkEvery = 8
        }
    }
}

val verifyPersistentSigning by tasks.registering {
    doLast {
        val config=android.signingConfigs.getByName("debug")
        val key=config.storeFile?.takeIf { it.isFile }
            ?: error("Prepare a chave fixa XCLOUD_ANDROID_KEYSTORE_BASE64. Builds com chave temporária estão bloqueados.")
        val store=KeyStore.getInstance(config.storeType ?: "JKS")
        key.inputStream().use { store.load(it,"android".toCharArray()) }
        check(store.isKeyEntry("androiddebugkey")) { "A chave privada de atualização não foi encontrada." }
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(store.getCertificate("androiddebugkey").encoded)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        val expected=rootProject.file("signing/certificate-sha256.txt").readText().trim()
        check(fingerprint==expected) { "Certificado diferente da assinatura fixa. APK bloqueado para preservar atualizações." }
    }
}
tasks.matching { it.name in setOf("validateSigningDebug", "validateSigningRelease") }
    .configureEach { dependsOn(verifyPersistentSigning) }

dependencies {
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Keep the pinned M137 runtime used by the native renderer and input pipeline.
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

val shortcutAssets = layout.buildDirectory.dir("generated/shortcutAssets")
val prepareShortcutTemplate by tasks.registering(Copy::class) {
    dependsOn(":shortcut:assembleRelease")
    from(project(":shortcut").layout.buildDirectory.file("outputs/apk/release/shortcut-release-unsigned.apk"))
    into(shortcutAssets.map { it.dir("game-shortcut") })
    rename { "template.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(shortcutAssets)
tasks.named("preBuild").configure { dependsOn(prepareShortcutTemplate) }

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", providers.environmentVariable("XCLOUD_MAVEN_CENTRAL_URL")
        .getOrElse("https://repo.maven.apache.org/maven2"))
    // The native-rendering test's SDK resolver must honor a configured build proxy as Gradle does.
    listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort",
        "javax.net.ssl.trustStore").forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}


tasks.register("verifyRuntimeArtifacts") {
    group = "verification"
    description = "Check the selected WebRTC, networking and signature runtime artifacts."
    val runtime = configurations.named("releaseRuntimeClasspath")
    val checksums = rootProject.file("gradle/runtime-artifacts.sha256")
    inputs.file(checksums)
    inputs.files(runtime)
    doLast {
        val expected = checksums.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (hash, coordinate) = line.trim().split(Regex("\\s+"), limit = 2)
            check(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid checksum for $coordinate" }
            coordinate to hash
        }
        check(expected.isNotEmpty()) { "The runtime checksum inventory is empty" }
        val verified = mutableSetOf<String>()
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@forEach
            val coordinate = "${id.group}:${id.module}:${id.version}"
            val expectedHash = expected[coordinate] ?: return@forEach
            check(verified.add(coordinate)) { "Multiple resolved runtime artifacts for $coordinate" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == expectedHash) { "Runtime checksum mismatch for $coordinate" }
        }
        check(expected.keys == verified) { "Missing runtime artifacts: ${expected.keys - verified}" }
        logger.lifecycle("Verified ${verified.size} pinned runtime artifacts")
    }
}
