-keep class com.anserver.** { *; }
-keepclassmembers class com.anserver.** { *; }

-keep class com.anserver.native.NativeBridge { *; }
-keepclassmembers class com.anserver.native.NativeBridge { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
