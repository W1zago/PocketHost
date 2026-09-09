-keep class com.anserver.** { *; }
-keepclassmembers class com.anserver.** { *; }

-keep class com.anserver.app.jvm.PocketJvm { *; }
-keepclassmembers class com.anserver.app.jvm.PocketJvm { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
