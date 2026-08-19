/*
 * The lidar console: the same app without a camera, plus a 3D view.
 *
 * A separate module rather than a second flavour of the first one, because the two
 * are different consoles for different hardware - the camera is a USB device and
 * that port is wanted for the CAN adapter, so the machine either has a camera and
 * a microphone or it has neither.
 *
 * The shared source is a library, :shared, which both apps depend on. It was first
 * arranged as this module compiling the other app's source directory - which works
 * and points the dependency the wrong way. Only this module's own activity and its
 * GL view are local.
 */
plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "re.keti.a3004bridge"
	compileSdk = 35

	defaultConfig {
		// Different from the camera console's, so both install side by side.
		// The Kotlin package stays the same because the shared source is the
		// same source; only the installed identity differs.
		applicationId = "re.keti.a3004lidar"
		minSdk = 26
		targetSdk = 35
		versionCode = 1
		versionName = "1.0"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	kotlinOptions { jvmTarget = "17" }
	buildFeatures { compose = true }
}

dependencies {
	implementation(project(":shared"))
}
