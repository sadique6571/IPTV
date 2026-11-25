# R8 / minify rules for the release build (v2.0.22).
# Media3 and androidx.core ship their own consumer ProGuard rules inside
# their AARs, so R8 already keeps what they need — the rules below are only
# for things R8 cannot see from code: classes named in AndroidManifest.xml.
-keep public class com.sadique.iptv.MainActivity
-keep public class androidx.core.content.FileProvider
# UpdateChecker parses GitHub JSON with org.json (no reflection, no
# serialization frameworks) — nothing to keep there.
# No reflection, no Gson/Moshi, no JNI anywhere in the app: no further
# rules needed. If R8 ever strips something, add a targeted -keep here —
# never a blanket -dontwarn.
