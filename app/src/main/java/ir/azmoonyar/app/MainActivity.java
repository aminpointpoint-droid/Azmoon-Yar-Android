package ir.azmoonyar.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 4101;
    private static final int REQUEST_FILE = 4102;
    private static final String HOST = "azmoon.local";
    private static final String BASE_URL = "https://azmoon.local/";
    private static final String PREFS = "azmoon_native_folders";

    private WebView webView;
    private SharedPreferences preferences;
    private String pendingBookId;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final Map<String, Uri> nativeFiles = new ConcurrentHashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(0, 84, 119));
        getWindow().setNavigationBarColor(Color.rgb(0, 63, 88));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.addJavascriptInterface(new NativeFolderBridge(), "AzmoonNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return super.shouldInterceptRequest(view, request);
                return serveLocalRequest(uri);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] accept = params.getAcceptTypes();
                    if (accept != null && accept.length > 0) {
                        java.util.ArrayList<String> types = new java.util.ArrayList<>();
                        for (String t : accept) if (t != null && !t.trim().isEmpty()) types.add(t.trim());
                        if (!types.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                    }
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, REQUEST_FILE);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });

        webView.loadUrl(BASE_URL + "index.html");
    }

    private WebResourceResponse serveLocalRequest(Uri uri) {
        String path = uri.getPath() == null ? "/index.html" : uri.getPath();
        if (path.startsWith("/native-files/")) {
            String token = path.substring("/native-files/".length());
            Uri fileUri = nativeFiles.get(token);
            if (fileUri == null) return textResponse(404, "Not Found", "فایل پیدا نشد");
            try {
                InputStream in = getContentResolver().openInputStream(fileUri);
                if (in == null) return textResponse(404, "Not Found", "فایل قابل خواندن نیست");
                String mime = getContentResolver().getType(fileUri);
                if (TextUtils.isEmpty(mime)) mime = mimeFromName(queryDisplayName(fileUri));
                return new WebResourceResponse(mime, null, in);
            } catch (Exception e) {
                return textResponse(500, "Read Error", "خطا در خواندن فایل");
            }
        }

        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) relative = "index.html";
        try {
            InputStream in = getAssets().open(relative);
            String mime = mimeFromPath(relative);
            String encoding = isTextMime(mime) ? "UTF-8" : null;
            return new WebResourceResponse(mime, encoding, 200, "OK", responseHeaders(), in);
        } catch (IOException e) {
            return textResponse(404, "Not Found", "فایل برنامه پیدا نشد: " + relative);
        }
    }

    private Map<String, String> responseHeaders() {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Access-Control-Allow-Origin", "https://azmoon.local");
        headers.put("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private WebResourceResponse textResponse(int code, String reason, String body) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, responseHeaders(), new java.io.ByteArrayInputStream(bytes));
    }

    private String mimeFromPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private boolean isTextMime(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("manifest");
    }

    private String mimeFromName(String name) {
        if (name == null) return "application/octet-stream";
        String ext = MimeTypeMap.getFileExtensionFromUrl(name);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext == null ? "" : ext.toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "";
    }

    private void sendFolderResult(JSONObject result) {
        if (webView == null) return;
        final String json = result.toString();
        final String quoted = JSONObject.quote(json);
        webView.post(() -> webView.evaluateJavascript(
                "(function deliver(w){"
                + "try{if(typeof w.__azmoonNativeFolderPickerResult==='function')w.__azmoonNativeFolderPickerResult(" + quoted + ");}catch(e){}"
                + "try{for(var i=0;i<w.frames.length;i++)deliver(w.frames[i]);}catch(e){}"
                + "})(window);", null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER) {
            JSONObject result = new JSONObject();
            try {
                if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBookId != null) {
                    Uri treeUri = data.getData();
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try { getContentResolver().takePersistableUriPermission(treeUri, flags); } catch (SecurityException ignored) {}
                    preferences.edit().putString("book:" + pendingBookId, treeUri.toString()).apply();
                    nativeFiles.clear();
                    result.put("ok", true);
                    result.put("name", getTreeDisplayName(treeUri));
                } else {
                    result.put("ok", false);
                    result.put("cancelled", true);
                }
            } catch (Exception e) {
                try { result.put("ok", false); result.put("error", e.getMessage() == null ? "خطای انتخاب پوشه" : e.getMessage()); } catch (Exception ignored) {}
            }
            pendingBookId = null;
            sendFolderResult(result);
            return;
        }

        if (requestCode == REQUEST_FILE && fileChooserCallback != null) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    uris = new Uri[count];
                    for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(uris);
            fileChooserCallback = null;
        }
    }

    private String getTreeDisplayName(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            String name = queryDisplayName(docUri);
            if (!TextUtils.isEmpty(name)) return name;
        } catch (Exception ignored) {}
        return "پوشه انتخابی";
    }

    private JSONArray collectBookFiles(Uri treeUri) throws Exception {
        JSONArray out = new JSONArray();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        collectChildren(treeUri, rootId, "", 0, out);
        return out;
    }

    private void collectChildren(Uri treeUri, String parentDocumentId, String relativePath, int depth, JSONArray out) {
        if (depth > 8 || out.length() >= 12000) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(childrenUri, projection, null, null, null);
            if (cursor == null) return;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext() && out.length() < 12000) {
                String docId = idCol >= 0 ? cursor.getString(idCol) : null;
                String name = nameCol >= 0 ? cursor.getString(nameCol) : "";
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : "";
                if (docId == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectChildren(treeUri, docId, relativePath + name + "/", depth + 1, out);
                } else if (isSupportedBookFile(name)) {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    String token = UUID.randomUUID().toString();
                    nativeFiles.put(token, fileUri);
                    long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : -1L;
                    String type = TextUtils.isEmpty(mime) ? mimeFromName(name) : mime;
                    JSONObject item = new JSONObject();
                    item.put("id", token);
                    item.put("name", name);
                    item.put("relativePath", relativePath);
                    item.put("type", type);
                    item.put("size", size);
                    out.put(item);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean isSupportedBookFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") ||
                n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".pdf");
    }

    public class NativeFolderBridge {
        @JavascriptInterface
        public void pickFolder(String bookId) {
            final String id = bookId == null ? "" : bookId.trim();
            runOnUiThread(() -> {
                if (id.isEmpty()) {
                    JSONObject result = new JSONObject();
                    try { result.put("ok", false); result.put("error", "شناسه کتاب معتبر نیست"); } catch (Exception ignored) {}
                    sendFolderResult(result);
                    return;
                }
                pendingBookId = id;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQUEST_FOLDER);
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    try { result.put("ok", false); result.put("error", "انتخاب‌گر پوشه در این دستگاه در دسترس نیست"); } catch (Exception ignored) {}
                    pendingBookId = null;
                    sendFolderResult(result);
                }
            });
        }

        @JavascriptInterface
        public String listFiles(String bookId) {
            try {
                String raw = preferences.getString("book:" + bookId, null);
                if (raw == null) return new JSONObject().put("error", "برای این کتاب پوشه‌ای انتخاب نشده است").toString();
                Uri treeUri = Uri.parse(raw);
                return collectBookFiles(treeUri).toString();
            } catch (Exception e) {
                try { return new JSONObject().put("error", "خواندن فهرست پوشه ناموفق بود").toString(); }
                catch (Exception ignored) { return "[]"; }
            }
        }

        @JavascriptInterface
        public void openNativeFile(String token) {
            final Uri fileUri = nativeFiles.get(token);
            if (fileUri == null) return;
            runOnUiThread(() -> {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(fileUri, "application/pdf");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(viewIntent, "باز کردن فایل PDF"));
                } catch (Exception e) {
                    // اگر PDFخوان نصب نباشد، برنامه فقط انتخاب پوشه و نمایش تصاویر را ادامه می‌دهد.
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AzmoonNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
