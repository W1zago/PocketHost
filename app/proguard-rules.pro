-keep class com.pockethost.** { *; }
-keepclassmembers class com.pockethost.** { *; }

-keep class com.pockethost.app.jvm.PocketJvm { *; }
-keepclassmembers class com.pockethost.app.jvm.PocketJvm { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
