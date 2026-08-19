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
	// Everything shared lives in :shared, which exposes Compose as api() so this
	// file has nothing to say about versions.
	implementation(project(":shared"))
}
