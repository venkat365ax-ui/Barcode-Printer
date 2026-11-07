package com.venkat.labelprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Label Printer: shows the label printer web page (bundled in assets/index.html)
 * and adds native classic-Bluetooth printing to Zebra printers (ZR328 / ZQ320).
 */
public class MainActivity extends Activity {

    // The page is served from this https address so camera access works (secure origin).
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_BLUETOOTH = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingCameraRequest;
    private String pendingBluetoothCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF1F3A5F);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new PrinterBridge(), "AndroidPrinter");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!HOST.equals(url.getHost())) return null; // CDN libraries etc. load normally
                String path = url.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeFor(path), "UTF-8", in);
                } catch (IOException e) {
                    WebResourceResponse notFound = new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                    notFound.setStatusCodeAndReasonPhrase(404, "Not Found");
                    return notFound;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url)); // other links open outside the app
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                ui.post(() -> {
                    boolean wantsCamera = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                    }
                    if (!wantsCamera) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCameraRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA && pendingCameraRequest != null) {
            if (granted) pendingCameraRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCameraRequest.deny();
            pendingCameraRequest = null;
        } else if (requestCode == REQ_BLUETOOTH && pendingBluetoothCallback != null) {
            callJs(pendingBluetoothCallback, granted, granted ? "" : "permission-denied");
            pendingBluetoothCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".css")) return "text/css";
        return "application/octet-stream";
    }

    private void callJs(final String callback, final boolean ok, final String message) {
        if (callback == null || !callback.matches("[A-Za-z0-9_$]+")) return;
        final String script = "window." + callback + " && window." + callback + "(" + ok + "," + JSONObject.quote(message) + ")";
        ui.post(() -> web.evaluateJavascript(script, null));
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Methods the web page can call as window.AndroidPrinter.xxx() */
    private class PrinterBridge {

        @JavascriptInterface
        public boolean isNative() { return true; }

        @JavascriptInterface
        public boolean hasPermission() { return hasBluetoothPermission(); }

        @JavascriptInterface
        public void requestPermission(final String callback) {
            ui.post(() -> {
                if (hasBluetoothPermission()) { callJs(callback, true, ""); return; }
                pendingBluetoothCallback = callback;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            });
        }

        /** Paired (bonded) Bluetooth devices as JSON: {ok, error, devices:[{name,address}]} */
        @JavascriptInterface
        public String listPrinters() {
            JSONObject out = new JSONObject();
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) return out.put("ok", false).put("error", "no-bluetooth").toString();
                if (!hasBluetoothPermission()) return out.put("ok", false).put("error", "permission").toString();
                if (!adapter.isEnabled()) return out.put("ok", false).put("error", "bluetooth-off").toString();
                JSONArray list = new JSONArray();
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String name = d.getName();
                        list.put(new JSONObject().put("name", name == null ? "" : name).put("address", d.getAddress()));
                    }
                }
                return out.put("ok", true).put("devices", list).toString();
            } catch (SecurityException e) {
                return "{\"ok\":false,\"error\":\"permission\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** Send raw printer commands (ZPL) to the printer with this Bluetooth address. */
        @JavascriptInterface
        public void print(final String address, final String data, final String callback) {
            new Thread(() -> {
                BluetoothSocket socket = null;
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) throw new IOException("This phone has no Bluetooth");
                    if (!adapter.isEnabled()) throw new IOException("Bluetooth is off");
                    try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    try {
                        socket.connect();
                    } catch (IOException first) {
                        try { socket.close(); } catch (IOException ignored) { }
                        socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    }
                    OutputStream os = socket.getOutputStream();
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    int offset = 0;
                    while (offset < bytes.length) {
                        int n = Math.min(4096, bytes.length - offset);
                        os.write(bytes, offset, n);
                        offset += n;
                    }
                    os.flush();
                    // Give the printer time to receive everything before the connection closes.
                    Thread.sleep(Math.min(6000, 800 + bytes.length / 15));
                    callJs(callback, true, "");
                } catch (SecurityException e) {
                    callJs(callback, false, "Bluetooth permission is missing");
                } catch (Exception e) {
                    callJs(callback, false, String.valueOf(e.getMessage()));
                } finally {
                    if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                }
            }).start();
        }
    }
}
