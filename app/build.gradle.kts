plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "re.keti.a3004bridge"
	compileSdk = 35

	defaultConfig {
		applicationId = "re.keti.a3004bridge"
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
	implementation("androidx.core:core-ktx:1.13.1")

	// Compose. The screen is a set of continuously changing values, which is
	// what a declarative tree is for: the hand-written ui.post(...) that pushed
	// every status string into a view goes away, and so does the class of layout
	// bug that gave the camera panel zero height.
	implementation(platform("androidx.compose:compose-bom:2024.12.01"))
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-graphics")
	implementation("androidx.compose.foundation:foundation")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.9.3")

	// Structured concurrency, so a stream's lifetime is the screen's lifetime by
	// construction rather than by remembering to call halt() in onPause.
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

	// A plain JVM test, for the one thing in this app that parses a binary
	// format another program wrote. MapFrame.parse touches no Android API, so
	// it runs here rather than needing a device - and a header the router and
	// the tablet disagree about is a map drawn in the wrong place, which is
	// exactly the kind of mistake that hides.
	testImplementation("junit:junit:4.13.2")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
