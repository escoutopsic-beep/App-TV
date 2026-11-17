# Métodos chamados pelo JavaScript da página (window.FrameXTVBridge).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# Remove logs de depuração do APK final.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
