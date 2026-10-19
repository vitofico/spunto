# Keep the save state machine addressable by the original-profile mapping compiler.
# Merging it into an unprofiled coroutine drops its post-startup profile flag.
-keep,allowobfuscation class io.theficos.spunto.data.local.NotesRepository$save$3 {
    java.lang.Object invokeSuspend(java.lang.Object);
}
