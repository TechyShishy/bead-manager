# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
}
# Firestore — keep data classes used for document deserialization
-keep class com.techyshishy.beadmanager.data.firestore.** { *; }
# kotlinx.serialization
-keepattributes InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** *; }
-keep class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable *;
}
