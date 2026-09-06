// Every plugin is declared here with `apply false` so that AGP and the
// Kotlin Gradle plugin resolve into the *same* buildscript classloader.
// Declaring AGP only in :app while KGP sits here splits them across parent
// and child scopes, and KGP then fails to load AGP's BaseVariant class:
//   Could not create an instance of type ...KotlinAndroidTarget
//   > NoClassDefFoundError: com/android/build/gradle/api/BaseVariant
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
