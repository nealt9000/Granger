# Release shrinking (Plan F1). The native libraries below ship no R8 rules of their own, and their
# C++ looks Java classes, fields and methods up by name, so their Java side is kept whole.

# libkiwix and libzim (ZIM reading and search)
-keep class org.kiwix.** { *; }
# sherpa-onnx (the voice); its JNI reads config fields by name
-keep class com.k2fsa.sherpa.onnx.** { *; }
# LiteRT-LM (the on-phone model)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep line numbers so the crash log stays readable; Play gets the mapping file with each bundle.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PdfBox-Android (text PDFs to ebooks) loads fonts and encodings by name; its JPEG 2000 codec is optional.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Navigation's typed routes find enum arguments by class name and valueOf (CatalogScope crashed launch).
-keep enum app.granger.** { *; }
