# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留源文件名与行号，release 崩溃栈可定位（默认会被 R8 剥离）。
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# SuperLyricApi 通过 AIDL/Binder 跨进程传输 Parcelable 数据，混淆会破坏类型名与字段结构。
-keep class com.hchen.superlyricapi.** { *; }
# SuperLyricApi 经反射使用系统隐藏 API android.os.ServiceManager，编译 classpath 中不存在，仅屏蔽警告。
-dontwarn android.os.ServiceManager
