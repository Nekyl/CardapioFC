package br.com.cardapiofc;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
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
import java.util.HashMap;
import java.util.Map;

/** Activity autocontida: app.py executado dentro do próprio APK via Chaquopy. */
public final class MainActivity extends Activity {
    private static final String TAG = "CardapioFC";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/";
    private PyObject bridge;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startPython();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 11, 14));
        WebView webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setBackgroundColor(Color.rgb(11, 11, 14));
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new Client());
        webView.loadUrl(START_URL);
    }

    private void startPython() {
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            bridge = Python.getInstance().getModule("cardapio_android");
            Log.i(TAG, "Python embutido iniciado");
        } catch (Throwable error) {
            bridge = null;
            Log.e(TAG, "Falha ao iniciar Python embutido", error);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!HOST.equals(uri.getHost())) return null;
            String path = uri.getPath() == null ? "/" : uri.getPath();
            if (path.startsWith("/api/")) return api(uri, path);
            return asset(path);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost());
        }
    }

    private WebResourceResponse api(Uri uri, String path) {
        if (bridge == null) return json(503, "{\"detail\":\"Python não iniciou\"}");
        try {
            String date = uri.getQueryParameter("date");
            if (date == null || date.isEmpty()) date = "";
            String league = uri.getQueryParameter("league");
            String body;
            if (path.equals("/api/games/stream")) {
                String payload = callPython("live_json", date, league);
                body = "data: " + payload + "\n\n";
                return response("text/event-stream", new ByteArrayInputStream(body.getBytes()), 200);
            } else if (path.equals("/api/games/live")) {
                body = callPython("live_json", date, league);
            } else if (path.equals("/api/games")) {
                body = callPython("games_json", date, league);
            } else {
                return json(404, "{\"detail\":\"Not Found\"}");
            }
            return json(200, body);
        } catch (Throwable error) {
            Log.e(TAG, "Falha na rota Python " + path, error);
            return json(500, "{\"detail\":\"Erro Python\"}");
        }
    }

    private String callPython(String function, String date, String league) {
        PyObject result = (league == null || league.isEmpty())
                ? bridge.callAttr(function, date)
                : bridge.callAttr(function, date, league);
        return result.toJava(String.class);
    }

    private WebResourceResponse asset(String path) {
        String name;
        if ("/".equals(path) || "/index.html".equals(path)) name = "static/index.html";
        else if (path.startsWith("/static/") && !path.contains("..")) name = path.substring(1);
        else if ((path.equals("/styles.css") || path.equals("/app.js")) && !path.contains("..")) name = "static" + path;
        else return response("text/plain", new ByteArrayInputStream(new byte[0]), 404);
        try {
            return response(mime(name), getAssets().open(name), 200);
        } catch (IOException error) {
            return response("text/plain", new ByteArrayInputStream(new byte[0]), 404);
        }
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static WebResourceResponse json(int status, String body) {
        return response("application/json", new ByteArrayInputStream(body.getBytes()), status);
    }

    private static WebResourceResponse response(String mime, InputStream data, int status) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", status,
                status == 200 ? "OK" : "Error", headers, data);
    }
}
