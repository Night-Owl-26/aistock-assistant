# R8 rules for the Follower release build.

# Vosk ("Hey Stocky" offline wake word) talks to its native library through JNA, which finds classes,
# methods and fields by name at run time: keep them exactly as they are.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Crash reports stay readable with the mapping file; generic signatures are needed by Kotlin and Room.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# zxing (QR code) only uses plain classes; nothing to keep. OkHttp, Room, Hilt and Compose ship their own rules.
-dontwarn org.slf4j.**
