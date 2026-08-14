plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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

val buildRustAndroid by tasks.registering(Exec::class) {
    workingDir(rootProject.file("rust"))
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "/home/ubuntu/android-sdk")
    environment(
        "ANDROID_NDK_HOME",
        System.getenv("ANDROID_NDK_HOME")
            ?: "${System.getenv("ANDROID_HOME") ?: "/home/ubuntu/android-sdk"}/ndk/27.2.12479018",
    )
    commandLine(
        "bash",
        "-lc",
        """
        cargo ndk -t arm64-v8a -t x86_64 -o '${projectDir.resolve("src/main/jniLibs").absolutePath}' build --release
        for abi in arm64-v8a x86_64; do
          cp '${projectDir.resolve("src/main/jniLibs").absolutePath}'/${'$'}{abi}/libsyncbook.so \
             '${projectDir.resolve("src/main/jniLibs").absolutePath}'/${'$'}{abi}/libuniffi_syncbook.so
        done
        """.trimIndent(),
    )
    outputs.dir(projectDir.resolve("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn(buildRustAndroid)
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
