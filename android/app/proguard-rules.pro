# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.telecall.app.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.telecall.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# androidx.security's EncryptedSharedPreferences pulls in google-crypto-tink,
# which references error-prone's build-time-only annotations (never present
# at runtime, only used to guide the compiler) -- R8 fails the release build
# outright ("missing classes") without this, even though nothing is actually
# broken. Never surfaced before because debug builds (isMinifyEnabled=false)
# don't run R8 at all -- first release build with android/app/build.gradle.kts's
# release signing config actually exercised is what caught it.
-dontwarn com.google.errorprone.annotations.**
