package com.ice.tools;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

public class MainActivity extends ComponentActivity {
    private WebView webView;
    private BarcodeScanner barcodeScanner;
    private ActivityResultLauncher<String> imagePicker;
    private boolean scanInProgress = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        barcodeScanner = BarcodeScanning.getClient();

        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) {
                        sendScanError("Pemilihan foto dibatalkan.");
                        return;
                    }
                    scanSelectedImage(uri);
                }
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidScanner");

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void pickAndScanImage() {
            runOnUiThread(() -> {
                if (scanInProgress) {
                    sendScanError("Pemindaian sebelumnya masih berjalan.");
                    return;
                }
                imagePicker.launch("image/*");
            });
        }
    }

    private void scanSelectedImage(Uri uri) {
        scanInProgress = true;
        sendScanStatus("Membaca gambar dengan pemindai native...");

        final InputImage image;
        try {
            image = InputImage.fromFilePath(this, uri);
        } catch (IOException | RuntimeException error) {
            scanInProgress = false;
            sendScanError("Foto tidak dapat dibuka. Pilih gambar lain.");
            return;
        }

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    scanInProgress = false;
                    try {
                        JSONArray values = new JSONArray();
                        for (com.google.mlkit.vision.barcode.common.Barcode barcode : barcodes) {
                            String value = barcode.getRawValue();
                            if (value != null && !value.trim().isEmpty()) {
                                boolean duplicate = false;
                                for (int i = 0; i < values.length(); i++) {
                                    if (value.equals(values.optString(i))) {
                                        duplicate = true;
                                        break;
                                    }
                                }
                                if (!duplicate) values.put(value);
                            }
                        }

                        JSONObject result = new JSONObject();
                        result.put("ok", values.length() > 0);
                        result.put("values", values);
                        result.put("message", values.length() > 0
                                ? "Berhasil membaca " + values.length() + " barcode."
                                : "Barcode belum terbaca. Gunakan foto yang lebih dekat, terang, dan tidak buram.");
                        result.put("fileName", getDisplayName(uri));
                        callJavascript("window.onNativeScanResult(" + JSONObject.quote(result.toString()) + ")");
                    } catch (Exception error) {
                        sendScanError("Hasil pemindaian tidak dapat diproses.");
                    }
                })
                .addOnFailureListener(error -> {
                    scanInProgress = false;
                    sendScanError("Gagal membaca barcode dari foto.");
                });
    }

    private String getDisplayName(Uri uri) {
        String result = "Foto dipilih";
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) result = cursor.getString(column);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void sendScanStatus(String message) {
        callJavascript("window.onNativeScanStatus(" + JSONObject.quote(message) + ")");
    }

    private void sendScanError(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("values", new JSONArray());
            result.put("message", message);
        } catch (Exception ignored) {
        }
        callJavascript("window.onNativeScanResult(" + JSONObject.quote(result.toString()) + ")");
    }

    private void callJavascript(String script) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (barcodeScanner != null) barcodeScanner.close();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidScanner");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
