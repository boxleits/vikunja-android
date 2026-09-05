plugins {
    // NOTE: com.android.application / com.android.library are intentionally
    // declared only in :app's own build script (not here with apply false).
    // They are published exclusively to Google's Maven repo, and pinning
    // them here would force Gradle to resolve that repo while configuring
    // *every* module, even a `:core:test`-only invocation.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
