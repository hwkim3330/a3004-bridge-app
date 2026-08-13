plugins {
	id("com.android.application") version "8.7.3" apply false
	id("org.jetbrains.kotlin.android") version "2.1.0" apply false
	// Since Kotlin 2.0 the Compose compiler ships as its own plugin and its
	// version must match the Kotlin version exactly.
	id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
