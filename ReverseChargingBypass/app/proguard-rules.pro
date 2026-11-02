# Xposed API 只在编译期使用，运行时由框架提供
-dontwarn de.robv.android.xposed.**
-keep class com.rcb.bypass.** { *; }
-keep class de.robv.android.xposed.** { *; }
