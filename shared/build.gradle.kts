/*
 * Everything both consoles are made of.
 *
 * There are two apps - one for the configuration with a camera and a microphone,
 * one for the configuration where that USB port carries CAN instead - and almost
 * all of the code is the same: the transports, the panels, the colours, the wire
 * format, joining the router's access point. It lives here once.
 *
 * It was briefly arranged the other way round, with the lidar module compiling the
 * camera app's source directory. That works and reads as a mistake: an application
 * reaching into another application for its shared parts leaves no place that is
 * obviously the shared part, and the dependency points the wrong way. A library the
 * two apps both depend on says the same thing in the structure.
 */
plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "re.keti.a3004bridge"
	compileSdk = 35
	defaultConfig { minSdk = 26 }
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	kotlinOptions { jvmTarget = "17" }
	buildFeatures { compose = true }
}

dependencies {
	// api, not implementation: the panels and the theme are the apps' own
	// vocabulary, so both of them compile against Compose directly and declaring
	// it twice more would be three places to bump a version.
	api("androidx.core:core-ktx:1.13.1")
	api(platform("androidx.compose:compose-bom:2024.12.01"))
	api("androidx.compose.ui:ui")
	api("androidx.compose.ui:ui-graphics")
	api("androidx.compose.foundation:foundation")
	api("androidx.compose.material3:material3")
	api("androidx.activity:activity-compose:1.9.3")
	api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
	api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

	// The one thing here that parses a binary format another program wrote runs
	// as a plain JVM test: a header the router and the tablet disagree about is a
	// map drawn in the wrong place, which is exactly the kind of mistake that
	// hides until somebody drives into a wall.
	testImplementation("junit:junit:4.13.2")
}
