# R8 configuration for release builds.
#
# Obfuscation is on because Google Play measures it ("DEX code optimization is
# below our threshold — Obfuscation 0%") and penalises visibility below 25%.
# The rules below carve out the two things that genuinely cannot be renamed:
# our own code, which crash reports are grouped and read by, and the classes
# native code resolves by name.

# ── Crash reporting must stay readable ──────────────────────────────────────
# DiagnosticsCollector.computeFingerprint() hashes the top app stack frames as
# "class.method" and selects them with className.startsWith("tr.trafy.kamera").
# Renaming our classes would break that filter, so every crash would fall back
# to the generic top-3-frames path, silently regroup every known bug as new, and
# arrive at the admin tool unreadable. -keepnames pins the names while still
# letting R8 shrink unused code, and it blocks the inlining that would otherwise
# drop frames out of the trace.
-keepnames class tr.trafy.kamera.** { *; }

# Line numbers and file names are what make a queued stack trace actionable.
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile

# ── JNI boundaries ──────────────────────────────────────────────────────────
# Native code looks these up by name, so renaming or removing them fails at
# runtime rather than at build time. Neither the ijkplayer AAR nor the ONNX
# Runtime AAR ships consumer ProGuard rules (verified: no proguard.txt in
# either), so nothing else supplies these.

# ijkplayer: native code posts events back through IjkMediaPlayer and resolves
# its listener interfaces reflectively.
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.**

# ONNX Runtime: the whole Java API is a JNI surface in both directions.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Our own native libs (libtrafy_vision.so, libyuv.so) call in through these.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Tink resolves key managers and primitives by name.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# KCP transport (Allwinner relay).
-keep class com.github.l42111996.** { *; }
-dontwarn com.github.l42111996.**

# ── Misc ────────────────────────────────────────────────────────────────────
# org.json is provided by the platform; the bundled copy is only a compile aid.
-dontwarn org.json.**

# OkHttp / Okio carry optional compile-only references.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Netty (via kcp-base) references log4j 1.x and 2.x logger backends that this
# app never bundles; it picks a logger at runtime from whatever is present.
# These are compile-time references only, so warning on them is noise.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
