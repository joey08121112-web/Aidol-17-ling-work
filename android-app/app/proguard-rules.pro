# Official SDK 6.7 rules. Release currently preserves the existing minifyEnabled=false policy.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class * implements com.sun.jna.Library { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class com.starot.codec.** { *; }
-keep class com.vl.sbc.** { *; }
-keep class com.vision.** { public *; }
-keepclasseswithmembers class * { native <methods>; }
