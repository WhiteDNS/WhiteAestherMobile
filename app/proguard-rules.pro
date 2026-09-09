-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.whitedns.whiteaesther.core.NativeAetherBridge { *; }
-keep interface com.whitedns.whiteaesther.core.NativeSocketProtector { *; }
-keep interface com.whitedns.whiteaesther.core.NativeEngineListener { *; }

# Tor's native side reaches into TorService by name. `torConfiguration` is a
# long holding a pointer into libtor, looked up with GetFieldID at runtime, and
# R8 has no way to see that. Shrinking it away leaves a build that passes every
# test we run -- because everything we run is unminified -- and then dies the
# moment a user picks Tor:
#
#   NoSuchFieldError: no "J" field "torConfiguration" in class
#   Lorg/torproject/jni/TorService;
#
# That shipped in 1.4.0. Keep the whole class rather than the one field: the
# rest of the native surface is equally invisible to R8, and this AAR is not
# ours to audit field by field.
-keep class org.torproject.jni.** { *; }

# gomobile's own documented rule, and the same hazard as above. Psiphon is
# reached through go.Seq, whose reference-counting fields are read from native
# code by name, and psi.* are the interfaces Go calls back into. These happen to
# survive today under the native-methods rule above; nothing guarantees they
# keep doing so.
-keep class go.** { *; }
-keep class psi.** { *; }
-keep class ca.psiphon.** { *; }
