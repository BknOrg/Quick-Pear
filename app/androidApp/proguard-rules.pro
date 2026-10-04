# QuickPear ProGuard & R8 Keep Rules

# kotlinx.serialization rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
    @kotlinx.serialization.SerialName *;
}
-keepclassmembers class *$serializer {
    public static final ** INSTANCE;
}

# Okio rules
-dontwarn okio.**

# Ktor Network Sockets
-dontwarn io.ktor.**

# QuickPear Domain Models
-keep class com.app.quickpear.domain.** { *; }
