# Cronet and the AndroidX libraries ship their own consumer rules; the app
# itself uses no reflection.

# The watch setup (P19-06). libadb-android finds Conscrypt by reflection
# (org.conscrypt.OpenSSLProvider, Conscrypt.exportKeyingMaterial); Conscrypt's
# own rules keep all of org.conscrypt. The SPAKE2 library is JNI.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
# BouncyCastle's LDAP certificate store, which nothing here uses.
-dontwarn javax.naming.**

# BUG-41: Glance tells widgets apart by their GlanceAppWidget's class name (its
# receiver-to-widget map, updateAll, getGlanceIds). Unkept, R8 merged
# QuickCaptureWidget into TodayWidget, and Today's refresh drew into the
# Capture widgets. tools/builder/in-container checks the mapping for it.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
