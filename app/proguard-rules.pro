# Add project specific ProGuard rules here.

# ── JNI / Rust bridge ──────────────────────────────────────────────────────
# Keep the RustBridge class and all native methods so R8 doesn't strip the
# JNI entry points that the Rust .so resolves by name at runtime.
-keep class com.jizizr.signaldock.RustBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Manifest components and library entry points are retained by AGP and the
# dependencies' consumer rules. Direct Binder/AIDL references are traceable by R8.

# ── Kotlin / Coroutines ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# Release stack traces remain recoverable through the generated mapping file.
