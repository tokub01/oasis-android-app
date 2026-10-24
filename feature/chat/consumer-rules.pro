# libsodium through JNA (lazysodium-android): JNA looks up the native methods and structures by name.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
