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
