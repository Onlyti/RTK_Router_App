# Keep kotlinx-serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.onlyti.rtkrouter.** {
    *** Companion;
}
-keepclasseswithmembers class com.onlyti.rtkrouter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
