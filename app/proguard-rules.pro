# Chaquopy referencia estas anotações apenas como metadados.
-dontwarn org.jetbrains.annotations.**

# A API é chamada diretamente pela Activity e também pode ser localizada pelo runtime.
-keep class com.chaquo.python.** { *; }

-keepclassmembers class br.com.cardapiofc.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class br.com.cardapiofc.PlayerActivity { *; }
-keep class br.com.cardapiofc.IptvManager { *; }
