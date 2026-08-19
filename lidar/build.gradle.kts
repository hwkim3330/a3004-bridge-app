/*
 * The lidar console: the same app without a camera, plus a 3D view.
 *
 * A separate module rather than a second flavour of the first one, because the two
 * are different consoles for different hardware - the camera is a USB device and
 * that port is wanted for the CAN adapter, so the machine either has a camera and
 * a microphone or it has neither.
 *
 * The shared source is *referenced*, not copied. Everything that talks to the
 * router, draws a panel or joins the access point lives in ../app/src/main/java and
 * both modules compile it: the bugs fixed there stay fixed in both, which a copy
 * would not manage for long. Only this module's own activity is local.
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

	sourceSets {
		getByName("main") {
			java.srcDirs("src/main/java", "../app/src/main/java")
			res.srcDirs("src/main/res", "../app/src/main/res")
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
	implementation("androidx.core:core-ktx:1.13.1")
	implementation(platform("androidx.compose:compose-bom:2024.12.01"))
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-graphics")
	implementation("androidx.compose.foundation:foundation")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.9.3")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
