# sherpa-onnx reaches its Kotlin classes from JNI by name; R8 full mode must not rename or
# strip them. Same rule BrightThumb carries.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# OkHttp's optional platform integrations, none of which exist on the phone.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
