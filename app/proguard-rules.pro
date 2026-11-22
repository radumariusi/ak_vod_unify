# R8 pentru release: optimizează Compose, coroutines și bibliotecile, fără să redenumească nimic.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Codul aplicației rămâne întreg (serializare JSON, Room, Hilt, WorkManager, servicii din manifest).
-keep class ro.ak.vodunify.** { *; }

# kotlinx.serialization (pe lângă regulile din bibliotecă)
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# Avertismente de clase opționale ale bibliotecilor
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
