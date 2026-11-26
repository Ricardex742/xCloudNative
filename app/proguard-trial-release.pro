# Preserve Java names and members reached through M137 JNI, including the patched codec wrappers.
-keep class org.webrtc.** { *; }

# The embedded shortcut is signed on-device using this entry point.
-keep class com.android.apksig.ApkSigner { *; }
-keep class com.android.apksig.ApkSigner$** { *; }
