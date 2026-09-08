# R8 rules for the minified build.
#
# Most of what this app uses ships its own consumer rules — Retrofit, OkHttp,
# kotlinx.serialization, Room and Hilt all bundle them, so they are not
# repeated here. What follows covers the places where this project's own code
# is reached reflectively or by name, which R8 cannot see.

# The API DTOs are only ever constructed by kotlinx.serialization, so nothing
# in the code visibly references their constructors or their generated
# serializers. Keep both, or a minified build decodes into empty objects.
-keepclassmembers class com.boxleits.vikunjaandroid.core.api.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.boxleits.vikunjaandroid.core.api.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.boxleits.vikunjaandroid.core.api.dto.**$$serializer { *; }

# Retrofit reads the annotations on this interface at runtime to build its
# implementation; without the signature attributes the generic return types
# are erased and the call fails at setup rather than at compile time.
-keep,allowobfuscation interface com.boxleits.vikunjaandroid.core.api.VikunjaApi
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault

# Glance builds the widget through this class, which the manifest names only
# indirectly via its receiver.
-keep class com.boxleits.vikunjaandroid.widget.AgendaWidget { *; }
-keep class com.boxleits.vikunjaandroid.widget.AgendaWidgetReceiver { *; }
