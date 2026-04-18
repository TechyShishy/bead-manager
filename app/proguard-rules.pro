# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
}
# Jsoup HTML parser (FMG scraper)
-keep public class org.jsoup.** { public *; }
# Firestore — keep data classes used for document deserialization
-keep class com.techyshishy.beadmanager.data.firestore.** { *; }
# PdfBox-Android — com.gemalto.jp2.JP2Decoder is an optional JPEG2000 runtime
# dependency that is not shipped with the AAR. R8 sees the reference in
# JPXFilter and fails without this suppression. We don't use JPX decoding.
-dontwarn com.gemalto.jp2.**

# kotlinx.serialization
-keepattributes InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** *; }
-keep class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable *;
}
