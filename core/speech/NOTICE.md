# `:core:speech` — vendored sherpa-onnx

`libs/sherpa-onnx-1.13.8-static-arm64-x86_64.aar` is sherpa-onnx **1.13.8** (k2-fsa, Apache-2.0; `LICENSE-sherpa-onnx` beside this file), taken from the project's GitHub release on 2026-10-21 and trimmed. sherpa-onnx publishes no Maven artifact, so the AAR is checked in.

- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8, asset `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (38,691,998 bytes), sha256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`. The static-link build has onnxruntime linked into `libsherpa-onnx-jni.so`, so there is one library per ABI; the plain `sherpa-onnx-1.13.8.aar` (sha256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`) ships four, and 7 MB more per ABI.
- Trimmed with `zip -d` to `jni/arm64-v8a/` and `jni/x86_64/` (the app's ABIs; `armeabi-v7a` and `x86` removed). Nothing else was changed: `classes.jar` (the Kotlin API, `com.k2fsa.sherpa.onnx.*`), `AndroidManifest.xml`, `R.txt`, `proguard.txt` and `META-INF/` are as released.
- Result: 19,196,128 bytes zipped; `libsherpa-onnx-jni.so` is 24,169,352 bytes for arm64-v8a and 27,359,872 for x86_64. Each library's `NEEDED` list is only `libandroid`, `liblog`, `libm`, `libdl`, `libc`, and every `LOAD` segment is 0x4000-aligned (the 16 KB page-size requirement), checked with the NDK's `llvm-readelf` on 2026-10-21.

To update: download the new `static-link-onnxruntime` AAR, record its sha256 here, strip the two ABIs the same way, re-run the `NEEDED` and alignment checks, and change the file name's version.
