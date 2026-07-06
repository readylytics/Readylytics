# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Protobuf Lite
-dontwarn com.google.protobuf.**
-assumevalues class com.google.protobuf.Android {
    static boolean ASSUME_ANDROID return true;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

