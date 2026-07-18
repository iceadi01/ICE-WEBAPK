package com.ice.tools;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends ComponentActivity {
    private static final String PREFS = "tools_ice_user";
    private static final String PREF_SERVER = "server";
    private static final String PREF_USERNAME = "username";
    private static final String DEFAULT_SERVER = "https://accuracy.fwh.is";
    private static final String APP_VERSION = "2.3-user";
    private static final long SESSION_CHECK_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private FrameLayout root;
    private WebView webView;
    private BarcodeScanner barcodeScanner;
    private ActivityResultLauncher<String> imagePicker;
    private boolean scanInProgress;
    private boolean unlocked;
    private String runtimeUsername = "";
    private String runtimeToken = "";
    private long runtimeExpiry;
    private AlertDialog loginDialog;

    private final Runnable sessionWatchdog = new Runnable() {
        @Override public void run() {
            if (unlocked && !runtimeToken.isEmpty()) checkSession(false);
            handler.postDelayed(this, SESSION_CHECK_MS);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 15, 28));
        setContentView(root);

        barcodeScanner = BarcodeScanning.getClient();
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) {
                sendScanError("Pemilihan foto dibatalkan.");
                return;
            }
            scanSelectedImage(uri);
        });

        createToolsWebView(savedInstanceState);
        showLoginDialog(true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!unlocked) {
                    finishAndRemoveTask();
                } else if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Keluar Tools ICE?")
                            .setMessage("Saat aplikasi ditutup, akun otomatis logout dan harus login ulang.")
                            .setPositiveButton("Keluar", (d, w) -> logoutAndExit())
                            .setNegativeButton("Batal", null)
                            .show();
                }
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createToolsWebView(Bundle savedInstanceState) {
        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidScanner");

        if (savedInstanceState == null) webView.loadUrl("file:///android_asset/index.html");
        else webView.restoreState(savedInstanceState);
    }

    private void showLoginDialog(boolean required) {
        if (isFinishing() || isDestroyed()) return;
        if (loginDialog != null && loginDialog.isShowing()) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(4));

        TextView info = new TextView(this);
        info.setText("Login menggunakan akun website ICE. Setelah berhasil, tools dapat dipakai saat berpindah ke Wi-Fi perusahaan selama aplikasi tetap terbuka.");
        info.setTextSize(13);
        info.setTextColor(Color.rgb(71, 85, 105));
        info.setPadding(0, 0, 0, dp(12));
        box.addView(info);

        EditText server = field("URL server", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(preferences.getString(PREF_SERVER, DEFAULT_SERVER));
        box.addView(server);

        EditText username = field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        username.setText(preferences.getString(PREF_USERNAME, ""));
        addWithTopMargin(box, username, 10);

        EditText password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addWithTopMargin(box, password, 10);

        TextView status = new TextView(this);
        status.setText("Internet/data diperlukan saat login.");
        status.setTextSize(12);
        status.setTextColor(Color.rgb(71, 85, 105));
        status.setPadding(0, dp(12), 0, 0);
        box.addView(status);

        loginDialog = new AlertDialog.Builder(this)
                .setTitle("Login Tools ICE")
                .setView(box)
                .setCancelable(!required)
                .setPositiveButton("Login", null)
                .setNegativeButton(required ? "Keluar" : "Batal", (d, w) -> {
                    if (required) finishAndRemoveTask();
                }).create();
        loginDialog.setCanceledOnTouchOutside(!required);
        loginDialog.setOnShowListener(d -> {
            Button login = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            login.setOnClickListener(v -> {
                String serverUrl = normalizeServer(server.getText().toString());
                String user = normalizeUsername(username.getText().toString());
                String pass = password.getText().toString();
                if (serverUrl.isEmpty()) {
                    showStatus(status, "URL server tidak valid.", true); return;
                }
                if (user.length() < 3 || pass.isEmpty()) {
                    showStatus(status, "Username dan password harus diisi.", true); return;
                }
                preferences.edit().putString(PREF_SERVER, serverUrl).putString(PREF_USERNAME, user).apply();
                login.setEnabled(false);
                showStatus(status, "Menghubungkan ke server…", false);
                loginAccount(serverUrl, user, pass, (ok, data, error) -> runOnUiThread(() -> {
                    if (ok) {
                        unlocked = true;
                        webView.setVisibility(View.VISIBLE);
                        try { loginDialog.dismiss(); } catch (Throwable ignored) {}
                        Toast.makeText(this, "Login berhasil", Toast.LENGTH_LONG).show();
                        handler.removeCallbacks(sessionWatchdog);
                        handler.postDelayed(sessionWatchdog, SESSION_CHECK_MS);
                    } else {
                        login.setEnabled(true);
                        showStatus(status, error == null || error.isEmpty() ? "Login gagal." : error, true);
                    }
                }));
            });
        });
        loginDialog.show();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setPadding(dp(14), dp(9), dp(14), dp(9));
        return e;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(marginDp);
        parent.addView(child, p);
    }

    private void showStatus(TextView status, String text, boolean error) {
        status.setText(text);
        status.setTextColor(error ? Color.rgb(185, 28, 28) : Color.rgb(71, 85, 105));
    }

    private void loginAccount(String server, String username, String password, ApiCallback callback) {
        try {
            String url = server + "/login-app.php"
                    + "?username=" + URLEncoder.encode(username, "UTF-8")
                    + "&password=" + URLEncoder.encode(password, "UTF-8")
                    + "&device_id=" + URLEncoder.encode(getDeviceId(), "UTF-8")
                    + "&device_name=" + URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8")
                    + "&app_version=" + URLEncoder.encode(APP_VERSION, "UTF-8")
                    + "&api=1&format=json&_t=" + System.currentTimeMillis();
            requestJsonWithHiddenWebView(url, (ok, data, error) -> {
                if (!ok || data == null || !jsonSaysActive(data)) {
                    callback.done(false, data, data == null ? error : data.optString("message", "Login ditolak."));
                    return;
                }
                String token = data.optString("session_token", data.optString("token", ""));
                long expiry = extractExpiryMillis(data);
                if (token.isEmpty() || expiry <= System.currentTimeMillis()) {
                    callback.done(false, data, "Respons server tidak lengkap: token/expired kosong.");
                    return;
                }
                runtimeUsername = data.optString("username", username);
                runtimeToken = token;
                runtimeExpiry = expiry;
                callback.done(true, data, null);
            });
        } catch (Exception e) {
            callback.done(false, null, "Gagal menyiapkan login: " + e.getMessage());
        }
    }

    private void checkSession(boolean showToast) {
        if (runtimeUsername.isEmpty() || runtimeToken.isEmpty()) {
            lockApp("Sesi login hilang. Silakan login ulang."); return;
        }
        if (runtimeExpiry > 0 && System.currentTimeMillis() >= runtimeExpiry) {
            lockApp("Masa aktif akun sudah berakhir."); return;
        }
        try {
            String server = normalizeServer(preferences.getString(PREF_SERVER, DEFAULT_SERVER));
            String url = server + "/check-session.php"
                    + "?username=" + URLEncoder.encode(runtimeUsername, "UTF-8")
                    + "&session_token=" + URLEncoder.encode(runtimeToken, "UTF-8")
                    + "&device_id=" + URLEncoder.encode(getDeviceId(), "UTF-8")
                    + "&device_name=" + URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8")
                    + "&app_version=" + URLEncoder.encode(APP_VERSION, "UTF-8")
                    + "&api=1&format=json&_t=" + System.currentTimeMillis();
            requestJsonWithHiddenWebView(url, (ok, data, error) -> {
                if (!ok || data == null) {
                    // Gangguan internet tidak langsung mengunci; sesi memori tetap dipakai sampai expired.
                    if (showToast) runOnUiThread(() -> Toast.makeText(this, "Server belum terjangkau. Mode kerja sementara aktif.", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (!jsonSaysActive(data)) {
                    lockApp(data.optString("message", "Akun dinonaktifkan atau sesi dicabut."));
                    return;
                }
                long expiry = extractExpiryMillis(data);
                if (expiry > 0) runtimeExpiry = expiry;
            });
        } catch (Exception ignored) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void requestJsonWithHiddenWebView(String url, ApiCallback callback) {
        runOnUiThread(() -> {
            WebView hidden = new WebView(this);
            AtomicBoolean finished = new AtomicBoolean(false);
            WebSettings s = hidden.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            CookieManager.getInstance().setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(hidden, true);
            hidden.setAlpha(0.01f);
            hidden.setWebChromeClient(new WebChromeClient());
            hidden.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    if (finished.get()) return;
                    handler.postDelayed(() -> view.evaluateJavascript(
                            "(function(){return document.body?document.body.innerText:'';})()",
                            value -> {
                                if (!finished.compareAndSet(false, true)) return;
                                destroyHidden(view);
                                try {
                                    String text = decodeJsString(value);
                                    JSONObject json = jsonFromText(text);
                                    if (json == null) callback.done(false, null, "Server membalas halaman, bukan JSON.");
                                    else callback.done(true, json, null);
                                } catch (Exception e) {
                                    callback.done(false, null, "Respons server tidak dapat dibaca.");
                                }
                            }), 450L);
                }
                @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (!finished.compareAndSet(false, true)) return;
                    destroyHidden(view);
                    callback.done(false, null, description == null ? "Server tidak tersambung." : description);
                }
            });
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(2), dp(2), Gravity.BOTTOM | Gravity.END);
            root.addView(hidden, p);
            hidden.loadUrl(url);
            handler.postDelayed(() -> {
                if (!finished.compareAndSet(false, true)) return;
                destroyHidden(hidden);
                callback.done(false, null, "Timeout. Periksa internet dan alamat server.");
            }, 25_000L);
        });
    }

    private void destroyHidden(WebView view) {
        runOnUiThread(() -> {
            try { root.removeView(view); } catch (Throwable ignored) {}
            try { view.stopLoading(); view.destroy(); } catch (Throwable ignored) {}
        });
    }

    private JSONObject jsonFromText(String text) {
        if (text == null) return null;
        String t = text.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return new JSONObject(t.substring(a, b + 1)); }
        catch (Exception ignored) { return null; }
    }

    private String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        try { return new JSONArray("[" + value + "]").optString(0, ""); }
        catch (Exception ignored) { return value.replace("\\n", "\n").replace("\\\"", "\""); }
    }

    private boolean jsonSaysActive(JSONObject data) {
        if (data == null) return false;
        if (data.optBoolean("ok") || data.optInt("ok") == 1) return true;
        if (data.optBoolean("success") || data.optInt("success") == 1) return true;
        if (data.optBoolean("valid") || data.optInt("valid") == 1) return true;
        if (data.optBoolean("active") || data.optInt("active") == 1) return true;
        return "active".equalsIgnoreCase(data.optString("status"));
    }

    private long extractExpiryMillis(JSONObject data) {
        String[] keys = {"expires_at", "expires", "expiry", "expire_at", "expire_date", "expired_at"};
        for (String key : keys) {
            long v = parseServerMillis(data.optString(key, ""));
            if (v > 0) return v;
        }
        return 0L;
    }

    private long parseServerMillis(String raw) {
        if (raw == null) return 0L;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return 0L;
        try {
            long n = Long.parseLong(s);
            return n < 10_000_000_000L ? n * 1000L : n;
        } catch (Exception ignored) {}
        String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd"};
        for (String format : formats) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(format, Locale.US);
                f.setLenient(false);
                f.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private String normalizeServer(String input) {
        String s = input == null ? "" : input.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("https://") && !s.startsWith("http://")) return "";
        return s;
    }

    private String normalizeUsername(String input) {
        return input == null ? "" : input.trim().replaceAll("\\s+", "");
    }

    private String getDeviceId() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.trim().isEmpty()) id = Build.MANUFACTURER + "-" + Build.MODEL;
        return id;
    }

    private void lockApp(String reason) {
        runOnUiThread(() -> {
            if (!unlocked && loginDialog != null && loginDialog.isShowing()) return;
            unlocked = false;
            runtimeUsername = "";
            runtimeToken = "";
            runtimeExpiry = 0L;
            handler.removeCallbacks(sessionWatchdog);
            if (webView != null) webView.setVisibility(View.INVISIBLE);
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            showLoginDialog(true);
        });
    }

    private void logoutAndExit() {
        unlocked = false;
        runtimeUsername = "";
        runtimeToken = "";
        runtimeExpiry = 0L;
        handler.removeCallbacksAndMessages(null);
        finishAndRemoveTask();
    }

    private final class AndroidBridge {
        @JavascriptInterface public void pickAndScanImage() {
            runOnUiThread(() -> {
                if (!unlocked) return;
                if (scanInProgress) { sendScanError("Pemindaian sebelumnya masih berjalan."); return; }
                imagePicker.launch("image/*");
            });
        }
    }

    private void scanSelectedImage(Uri uri) {
        scanInProgress = true;
        sendScanStatus("Membaca gambar dengan pemindai native...");
        final InputImage image;
        try { image = InputImage.fromFilePath(this, uri); }
        catch (IOException | RuntimeException e) {
            scanInProgress = false; sendScanError("Foto tidak dapat dibuka. Pilih gambar lain."); return;
        }
        barcodeScanner.process(image).addOnSuccessListener(barcodes -> {
            scanInProgress = false;
            try {
                JSONArray values = new JSONArray();
                for (com.google.mlkit.vision.barcode.common.Barcode barcode : barcodes) {
                    String value = barcode.getRawValue();
                    if (value == null || value.trim().isEmpty()) continue;
                    boolean duplicate = false;
                    for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) duplicate = true;
                    if (!duplicate) values.put(value);
                }
                JSONObject result = new JSONObject();
                result.put("ok", values.length() > 0);
                result.put("values", values);
                result.put("message", values.length() > 0 ? "Berhasil membaca " + values.length() + " barcode." : "Barcode belum terbaca. Gunakan foto yang lebih dekat dan terang.");
                result.put("fileName", getDisplayName(uri));
                callJavascript("window.onNativeScanResult(" + JSONObject.quote(result.toString()) + ")");
            } catch (Exception e) { sendScanError("Hasil pemindaian tidak dapat diproses."); }
        }).addOnFailureListener(e -> {
            scanInProgress = false; sendScanError("Gagal membaca barcode dari foto.");
        });
    }

    private String getDisplayName(Uri uri) {
        String result = "Foto dipilih";
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) result = cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void sendScanStatus(String message) { callJavascript("window.onNativeScanStatus(" + JSONObject.quote(message) + ")"); }
    private void sendScanError(String message) {
        try {
            JSONObject r = new JSONObject(); r.put("ok", false); r.put("values", new JSONArray()); r.put("message", message);
            callJavascript("window.onNativeScanResult(" + JSONObject.quote(r.toString()) + ")");
        } catch (Exception ignored) {}
    }
    private void callJavascript(String script) { if (webView != null) webView.post(() -> webView.evaluateJavascript(script, null)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        if (unlocked && !runtimeToken.isEmpty()) checkSession(false);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        runtimeToken = ""; runtimeUsername = ""; runtimeExpiry = 0L; unlocked = false;
        handler.removeCallbacksAndMessages(null);
        if (barcodeScanner != null) barcodeScanner.close();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidScanner");
            webView.stopLoading(); webView.destroy(); webView = null;
        }
        super.onDestroy();
    }

    private interface ApiCallback { void done(boolean ok, JSONObject data, String error); }
}
