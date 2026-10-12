# Cronet and the AndroidX libraries ship their own consumer rules; the app
# itself uses no reflection.

# The watch setup (P19-06). libadb-android finds Conscrypt by reflection
# (org.conscrypt.OpenSSLProvider, Conscrypt.exportKeyingMaterial); Conscrypt's
# own rules keep all of org.conscrypt. The SPAKE2 library is JNI.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
# BouncyCastle's LDAP certificate store, which nothing here uses.
-dontwarn javax.naming.**
