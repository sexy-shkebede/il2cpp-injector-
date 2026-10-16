# Keep signing / dexlib / BC reflective bits.
-keep class org.bouncycastle.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.jf.dexlib2.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.android.apksig.**
-dontwarn org.jf.**
-dontwarn javax.annotation.**
-dontwarn com.google.common.**
