import java.util.Properties

plugins { id("com.android.application") }

// Release signing: keystore.properties in the project root (not in git) names the keystore and
// its passwords. Without it, release builds are left unsigned and `releaseApk` stops with a note.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val canSign = signingProps.getProperty("storeFile") != null

android {
    namespace = "com.example.spotifywrapper"
    compileSdk = 37
    compileSdkMinor = 2   // GeckoView 158 is built against 37.2

    defaultConfig {
        applicationId = "io.github.pragyan.aftertone"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.1"
        // GeckoView ships native code for every ABI; phones are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Not debuggable: no remote debugging, no page console in logcat (see MainActivity).
            isDebuggable = false
            // Code shrinking stays off: GeckoView is most of the app and is already optimised.
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }
}

dependencies {
    // Firefox's engine. The page runs as Firefox in desktop-site mode, exactly like the browser;
    // the mobile layout is a built-in extension (assets/wrap/).
    implementation("org.mozilla.geckoview:geckoview:158.0.20261021001945")
}

// `./gradlew releaseApk`: builds the signed release and copies it to dist/Aftertone-<version>.apk,
// the file to upload to the GitHub release.
tasks.register<Copy>("releaseApk") {
    dependsOn("assembleRelease")
    doFirst {
        check(canSign) { "No keystore.properties in the project root: release builds need a signing key." }
    }
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Aftertone-${android.defaultConfig.versionName}.apk" }
}
