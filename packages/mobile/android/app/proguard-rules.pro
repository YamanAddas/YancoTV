# React Native + Hermes ship consumer rules via AAR — nothing extra needed
# for the core framework. The keeps below cover third-party libs that don't
# bundle their own or where the consumer rules have gaps on TV builds.

# Sentry — native crash handler reads annotations on HybridData classes.
-keep class io.sentry.** { *; }
-keep class io.sentry.android.** { *; }
-dontwarn io.sentry.**

# op-sqlite — JSI bridge reflects on HybridData.
-keep class com.op.sqlite.** { *; }
-keep class com.reactnativeopsqlite.** { *; }

# react-native-video — ExoPlayer/Media3 uses reflection for track selectors
# and DRM modules. Keeping the whole package is cheaper than debugging
# NoClassDefFoundError at stream-select time.
-keep class com.brentvatne.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# react-native-svg — native component factories registered by name.
-keep class com.horcrux.svg.** { *; }

# Reanimated + Worklets — JSI bindings.
-keep class com.swmansion.reanimated.** { *; }
-keep class com.swmansion.worklets.** { *; }

# Keep annotations, enums, and R classes (standard RN template).
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses
-keepclassmembers enum * { *; }
-keep class **.R$* { *; }

# Line numbers in crash reports — tiny cost, huge debugging win.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
