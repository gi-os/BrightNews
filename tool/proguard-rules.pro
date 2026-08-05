# Keep stack traces readable after shrinking. The line table is what turns a crash report from
# an anonymised APK back into a file and a line number; renaming the source file to "SourceFile"
# drops the original names without losing that.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the camera and barcode stack used by the SDK QR scanner.
#
# ML Kit finds its components reflectively through registrar classes named in the merged
# manifest, so R8 sees them as unreachable and strips them. The result is a release build where
# every screen works except the scanner, which fails inside BarcodeScanning.getClient().
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.firebase.components.ComponentRegistrar { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**

-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-dontwarn androidx.camera.**

# The SDK scanner composable and the screens that host it.
-keep class com.thelightphone.sdk.ui.LightQrCodeScannerKt { *; }
-keep class com.thelightphone.sdk.LightClientUiUtilsKt { *; }

# Room's generated implementation.
#
# Room.databaseBuilder never names RssDatabase_Impl in code — it builds the name from the
# @Database class and loads it with Class.forName, so nothing in the app statically references
# it and R8 sees a dead class. room-runtime ships rules of its own, but under full mode a keep
# on a class no longer keeps its members, and the DAO implementation is reached the same way,
# so both are spelled out here rather than left to the transitive rules.
-keep class com.lightrss.reader.RssDatabase_Impl { *; }
-keep class com.lightrss.reader.RssDao_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class com.lightrss.reader.** { *; }

# The RSS/Atom parser needs no keep rule, and this comment is here so nobody adds one on
# suspicion. SAXParserFactory.newInstance() does resolve an implementation by name, but on
# Android that name is a platform class inside the runtime, not something in this APK, and the
# tool builder forbids META-INF entirely so there is no service file to honour. RssParser's
# DefaultHandler subclasses are allocated directly by our own code, which R8 can see.

# jsoup, used only for newsletter HTML.
#
# jsoup compiles against JSpecify and JSR-305 nullability annotations that it does not ship at
# runtime. R8 treats a missing referenced class as an error, not a warning, so without these the
# release build fails before it shrinks anything. Nothing is kept — the classes genuinely are not
# needed, they only have to stop being fatal.
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**
