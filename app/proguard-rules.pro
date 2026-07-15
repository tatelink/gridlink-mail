# Add project-specific ProGuard rules here.

# --- kotlinx.serialization ---------------------------------------------------
# The serialization artifacts ship consumer rules, but the JMAP protocol leans
# heavily on @Serializable models (core:jmap) where a stripped/renamed generated
# serializer surfaces only at runtime as a sync failure. Keep them explicitly as
# belt-and-suspenders (the official kotlinx.serialization R8 rules).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the generated serializers and the companions that expose serializer().
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Sterna's own @Serializable models (JMAP wire types, settings) — keep wholesale
# so no protocol field is ever dropped by shrinking.
-keep,includedescriptorclasses class app.sterna.**$$serializer { *; }
-keepclassmembers class app.sterna.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# The OpenPGP provider (OpenKeychain) sends its result Parcelables across the binder under
# their original class names, and unmarshalling looks them up BY NAME in our classloader —
# so the vendored openpgp-api classes must keep their names verbatim. Without this, every
# minified release crashed with BadParcelableException/ClassNotFoundException
# (OpenPgpSignatureResult) the moment a signed or encrypted mail was verified (Codeberg #14).
-keep class org.openintents.openpgp.** { *; }
