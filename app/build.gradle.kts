import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val androidSdk = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: localProperties.getProperty("sdk.dir")
    ?: error("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
val androidNdk = System.getenv("ANDROID_NDK_HOME")
    ?: localProperties.getProperty("ndk.dir")
    ?: error("Android NDK not found. Set ANDROID_NDK_HOME or ndk.dir in local.properties.")
val generatedUniFfiDir = layout.buildDirectory.dir("generated/uniffi/main")

android {
    namespace = "com.ccarpo.syncbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ccarpo.syncbook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    sourceSets["main"].java.srcDir(generatedUniFfiDir)

    testOptions {
        unitTests.all { test ->
            test.systemProperty("java.library.path", "${projectDir}/src/test/jniLibs")
            test.systemProperty("jna.library.path", "${projectDir}/src/test/jniLibs")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val generateUniFfiKotlin by tasks.registering(Exec::class) {
    workingDir(rootProject.file("rust"))
    commandLine(
        "uniffi-bindgen",
        "generate",
        "src/syncbook.udl",
        "--language",
        "kotlin",
        "--out-dir",
        generatedUniFfiDir.get().asFile.absolutePath,
        "--no-format",
    )
    outputs.dir(generatedUniFfiDir)
}

val buildRustAndroid by tasks.registering(Exec::class) {
    workingDir(rootProject.file("rust"))
    environment("ANDROID_HOME", androidSdk)
    environment("ANDROID_NDK_HOME", androidNdk)
    commandLine(
        "bash",
        "-lc",
        """
        cargo ndk -t arm64-v8a -t x86_64 -o '${projectDir.resolve("src/main/jniLibs").absolutePath}' build --release
        """.trimIndent(),
    )
    outputs.dir(projectDir.resolve("src/main/jniLibs"))
    inputs.dir(rootProject.file("rust/src"))
    inputs.file(rootProject.file("rust/Cargo.toml"))
    inputs.file(rootProject.file("rust/Cargo.lock"))
}

val buildRustHost by tasks.registering(Exec::class) {
    workingDir(rootProject.file("rust"))
    commandLine(
        "bash",
        "-lc",
        """
        cargo build
        mkdir -p '${projectDir.resolve("src/test/jniLibs").absolutePath}'
        cp target/debug/libuniffi_syncbook.so '${projectDir.resolve("src/test/jniLibs").absolutePath}/libuniffi_syncbook.so'
        """.trimIndent(),
    )
    outputs.file(projectDir.resolve("src/test/jniLibs/libuniffi_syncbook.so"))
    inputs.dir(rootProject.file("rust/src"))
    inputs.file(rootProject.file("rust/Cargo.toml"))
    inputs.file(rootProject.file("rust/Cargo.lock"))
}

tasks.named("preBuild") {
    dependsOn(buildRustAndroid, generateUniFfiKotlin)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniFfiKotlin)
}

tasks.withType<Test>().configureEach {
    dependsOn(buildRustHost, generateUniFfiKotlin)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("net.java.dev.jna:jna:5.14.0")
}
