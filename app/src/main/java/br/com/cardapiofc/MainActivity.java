package br.com.cardapiofc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Activity autocontida: Python permanece responsável pelo scraper; IPTV roda nativamente. */
public final class MainActivity extends Activity {
    private static final String TAG = "CardapioFC";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/";
    private PyObject bridge;
    private WebView webView;
    private IptvManager iptv;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11, 11, 14));
        getWindow().setNavigationBarColor(Color.rgb(11, 11, 14));
        getWindow().getDecorView().setSystemUiVisibility(0);
        startPython();
        iptv = new IptvManager(this);
        iptv.warmupDaily();
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.rgb(11, 11, 14));
        root.setFitsSystemWindows(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });
        webView = new WebView(this); root.addView(webView, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        WebSettings settings = webView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setSupportZoom(false); settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setBackgroundColor(Color.rgb(11, 11, 14)); webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER); webView.addJavascriptInterface(new IptvJavascriptBridge(), "CardapioIPTV"); webView.setWebViewClient(new Client()); webView.loadUrl(START_URL);
    }

    private void startPython() {
        try { if (!Python.isStarted()) Python.start(new AndroidPlatform(this)); bridge = Python.getInstance().getModule("cardapio_android"); Log.i(TAG, "Python embutido iniciado"); }
        catch (Throwable error) { bridge = null; Log.e(TAG, "Falha ao iniciar Python embutido", error); }
    }

    @Override protected void onDestroy() { if (iptv != null) iptv.shutdown(); if (webView != null) webView.removeJavascriptInterface("CardapioIPTV"); super.onDestroy(); }
    @Override public void onBackPressed() { super.onBackPressed(); }

    private final class IptvJavascriptBridge {
        @JavascriptInterface public String getStatusJson() { return iptv.statusJson(); }
        @JavascriptInterface public String getChannelsJson() { return iptv.channelsJson(); }
        @JavascriptInterface public String findStream(String name) { return iptv.findStreamJson(name); }
        @JavascriptInterface public String findStreams(String name) { return iptv.findStreamsJson(name); }
        @JavascriptInterface public String findSuggestedStreams(String home, String away) { return iptv.findSuggestedStreamsJson(home, away); }
        @JavascriptInterface public void connectXtream(String server, String user, String password) { iptv.connectXtream(server, user, password); }
        @JavascriptInterface public void connectM3u(String url) { iptv.connectM3u(url); }
        @JavascriptInterface public void refresh() { iptv.refresh(); }
        @JavascriptInterface public void disconnect() { iptv.disconnect(); }
        @JavascriptInterface public void playStream(String url, String title) {
            if (url == null || url.trim().isEmpty()) return;
            runOnUiThread(() -> { Intent intent = new Intent(MainActivity.this, PlayerActivity.class); intent.putExtra(PlayerActivity.EXTRA_URL, url); intent.putExtra(PlayerActivity.EXTRA_TITLE, title == null ? "" : title); startActivity(intent); });
        }
    }

    private final class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl(); if (!HOST.equals(uri.getHost())) return null; String path = uri.getPath() == null ? "/" : uri.getPath(); if (path.startsWith("/api/")) return api(uri, path); return asset(path);
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !HOST.equals(request.getUrl().getHost()); }
    }

    private WebResourceResponse api(Uri uri, String path) {
        if (bridge == null) return json(503, "{\"detail\":\"Python não iniciou\"}");
        try {
            String date = uri.getQueryParameter("date"); if (date == null) date = ""; String league = uri.getQueryParameter("league"); String body;
            if (path.equals("/api/games/stream")) { body = "data: " + callPython("live_json", date, league) + "\n\n"; return response("text/event-stream", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 200); }
            if (path.equals("/api/games/live")) body = callPython("live_json", date, league); else if (path.equals("/api/games")) body = callPython("games_json", date, league); else return json(404, "{\"detail\":\"Not Found\"}");
            return json(200, body);
        } catch (Throwable error) { Log.e(TAG, "Falha na rota Python " + path, error); return json(500, "{\"detail\":\"Erro Python\"}"); }
    }
    private String callPython(String function, String date, String league) { PyObject result = (league == null || league.isEmpty()) ? bridge.callAttr(function, date) : bridge.callAttr(function, date, league); return result.toJava(String.class); }

    private WebResourceResponse asset(String path) {
        String name; if ("/".equals(path) || "/index.html".equals(path)) name = "static/index.html"; else if (path.startsWith("/static/") && !path.contains("..")) name = path.substring(1); else if ((path.equals("/styles.css") || path.equals("/app.js")) && !path.contains("..")) name = "static" + path; else return response("text/plain", new ByteArrayInputStream(new byte[0]), 404);
        try { return response(mime(name), getAssets().open(name), 200); } catch (IOException error) { return response("text/plain", new ByteArrayInputStream(new byte[0]), 404); }
    }
    private static String mime(String path) { if (path.endsWith(".html")) return "text/html"; if (path.endsWith(".js")) return "text/javascript"; if (path.endsWith(".css")) return "text/css"; if (path.endsWith(".png")) return "image/png"; if (path.endsWith(".svg")) return "image/svg+xml"; return "application/octet-stream"; }
    private static WebResourceResponse json(int status, String body) { return response("application/json", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), status); }
    private static WebResourceResponse response(String mime, InputStream data, int status) { Map<String, String> headers = new HashMap<>(); headers.put("Cache-Control", "no-store"); return new WebResourceResponse(mime, "utf-8", status, status == 200 ? "OK" : "Error", headers, data); }
}
