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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# LitePal loads model classes from assets/litepal.xml and reflects on their
# field names. Keep both the ORM implementation and all persisted model names
# so an optimized release remains compatible with the existing database.
# core:3.2.3 ships no consumer rules. Its internal reflection and ORM inheritance
# need names/members retained; permit optimization rather than guessing reflective roots.
-keep,allowoptimization class org.litepal.** { *; }
# Scope persisted schema to this application's entity package. Never rename fields:
# old message.db column names and assets/litepal.xml are part of the upgrade contract.
-keep,allowoptimization class com.zzf.bluetoothsmp.entity.** extends org.litepal.crud.LitePalSupport { *; }
