# Proguard rules specific to the Flac extension.

# This prevents the names of native methods from being obfuscated.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Some members of these classes are being accessed from native methods. Keep them unobfuscated.
-keep class androidx.media3.decoder.flac.FlacDecoderJni {
    *;
}
-keep class androidx.media3.extractor.FlacStreamMetadata {
    *;
}
-keep class androidx.media3.extractor.metadata.flac.PictureFrame {
    *;
}

# LibflacAudioRenderer is loaded by name at runtime from
# androidx.media3.exoplayer.DefaultRenderersFactory:
#
#   Class.forName("androidx.media3.decoder.flac.LibflacAudioRenderer")
#       .getConstructor(Handler.class, AudioRendererEventListener.class, AudioSink.class)
#
# media3-exoplayer's own consumer rules only add "-keepclassmembers ... <init>(...)", which
# stops the constructor being removed but does not stop the class being renamed. Renaming breaks
# Class.forName, and today the class survives only because R8 recognises the literal-string
# Class.forName call. Keep the class name and its public constructors explicitly so the renderer
# cannot be silently dropped by a future R8 version or refactor: without it playback falls back
# to the platform FLAC decoder.
-keep class androidx.media3.decoder.flac.LibflacAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
    <init>();
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.common.audio.AudioProcessor[]);
}

# FlacLibrary is the public entry point for the native library: it owns the LibraryLoader that
# calls System.loadLibrary("flacJNI") and exposes isAvailable()/setLibraries(). Consumers use it
# directly by name, so keep its API intact.
-keep class androidx.media3.decoder.flac.FlacLibrary {
    *;
}
