# Keep kotlinx-serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ailab.rtkrouter.** {
    *** Companion;
}
-keepclasseswithmembers class com.ailab.rtkrouter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
