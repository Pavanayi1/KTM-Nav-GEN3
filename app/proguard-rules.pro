# Navigator Gen3 - R8 is enabled for runtime speed (Compose optimisation),
# not for size. Our own code is kept whole: the BLE protocol, settings
# migration and notification parsing were written without shrinking in mind,
# and a sideloaded app gains nothing from obfuscating them.
-keep class com.navigator.app.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# TensorFlow Lite (turn-icon classifier) - JNI + reflection.
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Tink (via androidx.security EncryptedSharedPreferences) references
# compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
