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
# Gridlink's own @Serializable models (JMAP wire types, settings) — keep wholesale
# so no protocol field is ever dropped by shrinking.
-keep,includedescriptorclasses class app.gridlink.**$$serializer { *; }
-keepclassmembers class app.gridlink.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- logging ------------------------------------------------------------------
# Drop debug/verbose logging from release builds, and ONLY those. Log.i/w/e stay:
# reporters send logcats, and on hardware I do not own that is the only diagnostic
# there is (a crash was fixed from a reporter's log, and another case still rides on
# one). The real risk was never the existence of the log, it was the content
# interpolated into it, which is handled at the source instead. If a debug line ever
# carries something needed for diagnosis, promote it to Log.i rather than widen this.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# The OpenPGP provider (OpenKeychain) sends its result Parcelables across the binder under
# their original class names, and unmarshalling looks them up BY NAME in our classloader —
# so the vendored openpgp-api classes must keep their names verbatim. Without this, every
# minified release crashed with BadParcelableException/ClassNotFoundException
# (OpenPgpSignatureResult) the moment a signed or encrypted mail was verified (Codeberg #14).
-keep class org.openintents.openpgp.** { *; }

# The avatar composables render nothing in a minified build while debug is fine: R8's optimizer
# inlines these small, non-inline @Composable functions into their callers (the mapping keeps
# only Monogram's synthetic lambda, the function itself is gone), which drops the restartable
# group they need to emit — so neither the contact photo nor the monogram slot draws in the
# recipient-suggestion menu, the message list or the reader. Keeping them whole stops the inline
# and restores the avatar. Same failure family as #14: an optimization that is correct for
# ordinary code but wrong for a compiler-managed calling convention.
-keep class app.gridlink.ui.components.MonogramKt { *; }
-keep class app.gridlink.ui.components.ContactAvatarKt { *; }
