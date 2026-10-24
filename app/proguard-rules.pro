# kotlinx.serialization keeps its generated serializers through its own rules; Retrofit services are interfaces.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# libsodium binding (JNA)
-keep class com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
