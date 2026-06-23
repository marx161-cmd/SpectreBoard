# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class com.termux.spectreboard.latin.dictionary.Dictionary
-keep class com.termux.spectreboard.latin.NgramContext
-keep class com.termux.spectreboard.latin.makedict.ProbabilityInfo

# ONNX Runtime — native JNI looks up TensorInfo and other classes by name at runtime
-keep class ai.onnxruntime.** { *; }

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
