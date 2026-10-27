package com.zzf.bluetoothsmp.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import androidx.webkit.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All resources are local and integrity checked; privileged messages are main-frame/origin scoped. */
public final class PluginWebView {
    private static final String CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; media-src 'self'; connect-src 'none'; frame-src 'none'; "
            + "worker-src 'none'; child-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'";
    private PluginWebView() { }
    public static String origin(PluginStore.Installed plugin) {
        return "https://p-" + PluginSignatures.sha256(plugin.identity().getBytes(StandardCharsets.UTF_8)).substring(0, 32) + ".plugins.invalid";
    }
    public static boolean allowedOrigin(Uri source, String expected, boolean mainFrame) {
        return mainFrame && source != null && expected.equals(source.toString());
    }
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    public static WebView create(Context context, PluginRuntime runtime, PluginStore.Installed plugin) throws Exception {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new PluginException("WEBVIEW_UPDATE_REQUIRED", context.getString(com.zzf.bluetoothsmp.R.string.plugin_webview_update));
        }
        WebView view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true); settings.setBlockNetworkImage(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true); settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        String origin = origin(plugin);
        Handler main = new Handler(Looper.getMainLooper());
        WebViewCompat.addWebMessageListener(view, "__btNative", Collections.singleton(origin), (web, message, source, mainFrame, reply) -> {
            if (web != view || !allowedOrigin(source, origin, mainFrame) || message.getType() != WebMessageCompat.TYPE_STRING) return;
            String text = message.getData();
            if (text == null || text.length() > 128 * 1024) return;
            try { runtime.message(new JSONObject(text), reply); } catch (Exception ignored) { }
        });
        String sdk;
        try (InputStream source = context.getAssets().open("plugins/sdk.js")) { sdk = new String(PluginStore.read(source, 128 * 1024), StandardCharsets.UTF_8); }
        WebViewCompat.addDocumentStartJavaScript(view, sdk, Collections.singleton(origin));
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String where, GeolocationPermissions.Callback result) { result.invoke(where, false, false); }
            @Override public boolean onShowFileChooser(WebView web, ValueCallback<Uri[]> callback, FileChooserParams params) { callback.onReceiveValue(null); return true; }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                return !request.isForMainFrame() || !local(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView web, String url) { return !local(Uri.parse(url)); }
            private boolean local(Uri uri) {
                return uri.getPath() != null && uri.getPath().startsWith("/") && uri.getPath().length() > 1
                        && "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                        && origin.equals("https://" + uri.getHost()) && PluginManifest.safePath(uri.getPath().substring(1));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                if (!request.getMethod().equals("GET") || !local(request.getUrl())) return denied();
                String path = request.getUrl().getPath().substring(1);
                String expected = plugin.manifest.files.get(path);
                if (expected == null) return denied();
                try {
                    File file = new File(plugin.directory, path);
                    if (!file.getCanonicalPath().startsWith(plugin.directory.getCanonicalPath() + File.separator)) return denied();
                    byte[] bytes;
                    try (InputStream source = new FileInputStream(file)) { bytes = PluginStore.read(source, PluginArchive.MAX_EXPANDED); }
                    if (!expected.equals(PluginSignatures.sha256(bytes))) { main.post(runtime::stopUnresponsive); return denied(); }
                    String mime = mime(path);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Security-Policy", CSP); headers.put("X-Content-Type-Options", "nosniff");
                    headers.put("Cache-Control", "no-store"); headers.put("Referrer-Policy", "no-referrer");
                    headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), usb=(), serial=(), bluetooth=(), payment=()");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
                } catch (Exception error) { return denied(); }
            }
            @Override public void onReceivedSslError(WebView web, android.webkit.SslErrorHandler handler, android.net.http.SslError error) { handler.cancel(); }
            @Override public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail) { main.post(runtime::stopUnresponsive); return true; }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(view, new androidx.webkit.WebViewRenderProcessClient() {
                @Override public void onRenderProcessUnresponsive(WebView web, androidx.webkit.WebViewRenderProcess process) { runtime.stopUnresponsive(); }
                @Override public void onRenderProcessResponsive(WebView web, androidx.webkit.WebViewRenderProcess process) { }
            });
        }
        view.setDownloadListener((url, userAgent, disposition, mimeType, size) -> { });
        view.loadUrl(origin + "/" + plugin.manifest.entry);
        return view;
    }
    private static WebResourceResponse denied() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0])); }
    private static String mime(String path) {
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substring(path.lastIndexOf('.') + 1));
        return type == null ? "application/octet-stream" : type;
    }
}
