package com.venkat.labelprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    // Zebra Bluetooth LE "parser" service: the same one the web page uses in Chrome
    private static final UUID ZEBRA_SVC = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b");
    private static final UUID ZEBRA_WRITE = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b");

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_BLUETOOTH = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_CAMERA_SHOT = 4;
    private static final int REQ_CAMERA_FILE = 5;
    private Uri cameraUri;
    private WebChromeClient.FileChooserParams pendingFileParams;
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
                if (params.isCaptureEnabled() && wantsImage(params)) {   // <input capture>: open the camera directly
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(params);
                    else { pendingFileParams = params; requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_FILE); }
                    return true;
                }
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

    private boolean wantsImage(WebChromeClient.FileChooserParams params) {
        String[] t = params.getAcceptTypes();
        if (t == null || t.length == 0) return true;
        for (String a : t) if (a == null || a.isEmpty() || a.startsWith("image")) return true;
        return false;
    }

    /** Take a full-size photo with the phone camera; saved in Pictures/StockTag. */
    private void launchCamera(WebChromeClient.FileChooserParams params) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "StockTag_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StockTag");
                cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (cameraUri != null) {
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(i, REQ_CAMERA_SHOT);
                    return;
                }
            }
            startActivityForResult(params.createIntent(), REQ_FILE);   // older phones: normal picker
        } catch (Exception e) {
            if (cameraUri != null) { try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { } cameraUri = null; }
            try { startActivityForResult(params.createIntent(), REQ_FILE); }
            catch (Exception e2) { if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; } }
        }
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
        } else if (requestCode == REQ_CAMERA_SHOT) {
            if (resultCode == RESULT_OK && cameraUri != null) {
                if (fileCallback != null) fileCallback.onReceiveValue(new Uri[]{cameraUri});
            } else {
                if (cameraUri != null) { try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { } }
                if (fileCallback != null) fileCallback.onReceiveValue(null);
            }
            fileCallback = null;
            cameraUri = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA_FILE && pendingFileParams != null) {
            WebChromeClient.FileChooserParams fp = pendingFileParams;
            pendingFileParams = null;
            if (granted) launchCamera(fp);
            else {
                try { startActivityForResult(fp.createIntent(), REQ_FILE); }
                catch (Exception e) { if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; } }
            }
        } else if (requestCode == REQ_CAMERA && pendingCameraRequest != null) {
            if (granted) pendingCameraRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCameraRequest.deny();
            pendingCameraRequest = null;
        } else if (requestCode == REQ_BLUETOOTH && pendingBluetoothCallback != null) {
            boolean ok = hasBluetoothPermission();   // nearby search is optional; printing needs "connect"
            callJs(pendingBluetoothCallback, ok, ok ? "" : "permission-denied");
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

    private String[] bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};   // needed to search nearby on Android 11 and older
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasScanPermission() {
        for (String p : bluetoothPermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /* ---------- Bluetooth LE printing (Zebra parser service) ---------- */
    private final Object bleLock = new Object();
    private final Semaphore bleSig = new Semaphore(0);
    private BluetoothGatt bleGatt;
    private String bleAddr;
    private BluetoothGattCharacteristic bleChar;
    private volatile boolean bleConnected;
    private volatile int bleMtu = 23;
    private volatile int bleLastStatus = 0;

    private final BluetoothGattCallback bleCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            bleLastStatus = status;
            bleConnected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED;
            if (!bleConnected) bleChar = null;
            bleSig.release();
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) bleMtu = mtu;
            bleSig.release();
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) { bleSig.release(); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { bleSig.release(); }
    };

    private boolean bleWait(long ms) throws InterruptedException { return bleSig.tryAcquire(ms, TimeUnit.MILLISECONDS); }

    @SuppressLint("MissingPermission")
    private void bleClose() {
        if (bleGatt != null) { try { bleGatt.disconnect(); bleGatt.close(); } catch (Exception ignored) { } }
        bleGatt = null; bleChar = null; bleConnected = false; bleAddr = null; bleMtu = 23;
    }

    @SuppressLint("MissingPermission")
    private void bleConnect(String address) throws Exception {
        if (bleGatt != null && address.equals(bleAddr) && bleConnected && bleChar != null) return;
        bleClose();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("This phone has no Bluetooth");
        if (!adapter.isEnabled()) throw new IOException("Bluetooth is off");
        final BluetoothDevice device = adapter.getRemoteDevice(address);
        try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
        String why = "";
        for (int attempt = 1; attempt <= 3 && !bleConnected; attempt++) {
            bleSig.drainPermits();
            bleLastStatus = 0;
            final BluetoothGatt[] holder = new BluetoothGatt[1];
            final Semaphore made = new Semaphore(0);
            ui.post(() -> {   // some phones only connect reliably when started from the main thread
                try { holder[0] = device.connectGatt(MainActivity.this, false, bleCb, BluetoothDevice.TRANSPORT_LE); }
                catch (SecurityException e) { holder[0] = null; }
                made.release();
            });
            made.tryAcquire(3000, TimeUnit.MILLISECONDS);
            bleGatt = holder[0];
            bleAddr = address;
            if (bleGatt == null) { why = "connect not started"; break; }
            boolean answered = bleWait(12000);
            if (bleConnected) break;
            why = answered ? "status " + bleLastStatus : "no answer";
            try { bleGatt.disconnect(); bleGatt.close(); } catch (Exception ignored) { }
            bleGatt = null;
            Thread.sleep(700);
        }
        if (!bleConnected) { bleClose(); throw new IOException("Couldn't connect over Bluetooth LE (" + why + ")"); }
        bleSig.drainPermits();
        if (bleGatt.requestMtu(512)) bleWait(5000);
        bleSig.drainPermits();
        if (!bleGatt.discoverServices() || !bleWait(10000)) { bleClose(); throw new IOException("The printer didn't answer"); }
        BluetoothGattService svc = bleGatt.getService(ZEBRA_SVC);
        BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(ZEBRA_WRITE);
        if (c == null) { bleClose(); throw new IOException("This device isn't a Zebra printer"); }
        bleChar = c;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void bleWrite(byte[] bytes) throws Exception {
        boolean noResp = (bleChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        bleChar.setWriteType(noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        int chunk = Math.max(20, Math.min(bleMtu - 3, 509));
        for (int off = 0; off < bytes.length; off += chunk) {
            int n = Math.min(chunk, bytes.length - off);
            byte[] part = new byte[n];
            System.arraycopy(bytes, off, part, 0, n);
            bleChar.setValue(part);
            bleSig.drainPermits();
            boolean started = false;
            for (int tries = 0; tries < 20 && !started; tries++) {
                started = bleGatt.writeCharacteristic(bleChar);
                if (!started) Thread.sleep(25);
            }
            if (!started) throw new IOException("Couldn't send to the printer");
            if (!bleWait(5000)) throw new IOException("The printer stopped answering");
            if (!bleConnected) throw new IOException("Printer disconnected");
        }
    }

    /** Methods the web page can call as window.AndroidPrinter.xxx() */
    private class PrinterBridge {

        @JavascriptInterface
        public boolean isNative() { return true; }

        @JavascriptInterface
        public boolean hasPermission() { return hasBluetoothPermission() && hasScanPermission(); }

        @JavascriptInterface
        public void requestPermission(final String callback) {
            ui.post(() -> {
                if (hasBluetoothPermission() && hasScanPermission()) { callJs(callback, true, ""); return; }
                pendingBluetoothCallback = callback;
                requestPermissions(bluetoothPermissions(), REQ_BLUETOOTH);
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

        /** Search nearby Bluetooth LE devices for ~6 s. Callback gets a JSON array [{name,address,zebra}]. */
        @JavascriptInterface
        @SuppressLint("MissingPermission")
        public void scanBle(final String callback) {
            new Thread(() -> {
                try {
                    if (!hasScanPermission()) throw new IOException("Nearby devices permission not allowed");
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth is off");
                    BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
                    if (sc == null) throw new IOException("Bluetooth LE search not available");
                    final Map<String, JSONObject> found = new LinkedHashMap<>();
                    ScanCallback cb = new ScanCallback() {
                        @Override public void onScanResult(int type, ScanResult r) { add(r); }
                        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) add(r); }
                        private void add(ScanResult r) {
                            try {
                                BluetoothDevice d = r.getDevice();
                                ScanRecord rec = r.getScanRecord();
                                String name = rec != null ? rec.getDeviceName() : null;
                                if (name == null) { try { name = d.getName(); } catch (SecurityException ignored) { } }
                                boolean zebra = false;
                                if (rec != null && rec.getServiceUuids() != null) {
                                    for (ParcelUuid u : rec.getServiceUuids()) if (ZEBRA_SVC.equals(u.getUuid())) zebra = true;
                                }
                                if ((name == null || name.isEmpty()) && !zebra) return;   // skip unnamed devices
                                synchronized (found) {
                                    found.put(d.getAddress(), new JSONObject().put("name", name == null ? "Zebra printer" : name)
                                            .put("address", d.getAddress()).put("zebra", zebra));
                                }
                            } catch (Exception ignored) { }
                        }
                    };
                    ScanSettings set = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    sc.startScan(null, set, cb);
                    Thread.sleep(6000);
                    try { sc.stopScan(cb); } catch (Exception ignored) { }
                    JSONArray arr = new JSONArray();
                    synchronized (found) { for (JSONObject o : found.values()) arr.put(o); }
                    callJs(callback, true, arr.toString());
                } catch (SecurityException e) {
                    callJs(callback, false, "Nearby devices permission not allowed");
                } catch (Exception e) {
                    callJs(callback, false, String.valueOf(e.getMessage()));
                }
            }).start();
        }

        /** Classic Bluetooth (SPP) send; also used as fallback when Bluetooth LE fails. */
        private void classicSend(String address, byte[] bytes) throws Exception {
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
                for (int off = 0; off < bytes.length; off += 4096) os.write(bytes, off, Math.min(4096, bytes.length - off));
                os.flush();
                Thread.sleep(Math.min(6000, 800 + bytes.length / 15));
            } finally {
                if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            }
        }

        /** Send ZPL to a Zebra printer over Bluetooth LE (no pairing needed). */
        @JavascriptInterface
        public void printBle(final String address, final String data, final String callback) {
            new Thread(() -> {
                synchronized (bleLock) {
                    try {
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                        try {
                            try {
                                bleConnect(address);
                                bleWrite(bytes);
                            } catch (IOException first) {
                                bleClose();             // one more try with a fresh connection
                                bleConnect(address);
                                bleWrite(bytes);
                            }
                            Thread.sleep(300);
                        } catch (IOException bleErr) {
                            bleClose();
                            try {
                                classicSend(address, bytes);   // same printer over classic Bluetooth (may ask to pair once)
                            } catch (Exception classicErr) {
                                throw new IOException(bleErr.getMessage() + "; classic Bluetooth: " + classicErr.getMessage());
                            }
                        }
                        callJs(callback, true, "");
                    } catch (SecurityException e) {
                        bleClose();
                        callJs(callback, false, "Bluetooth permission is missing");
                    } catch (Exception e) {
                        bleClose();
                        callJs(callback, false, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        /** POST JSON to the WeCom Smart Sheet webhook (only that address is allowed). Callback gets the response body. */
        @JavascriptInterface
        public void httpPost(final String url, final String body, final String callback) {
            new Thread(() -> {
                HttpURLConnection con = null;
                try {
                    if (url == null || !url.startsWith("https://qyapi.weixin.qq.com/cgi-bin/wedoc/smartsheet/webhook")) {
                        throw new IOException("Only the WeCom Smart Sheet webhook address is allowed");
                    }
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    con.setFixedLengthStreamingMode(out.length);
                    try (OutputStream os = con.getOutputStream()) { os.write(out); }
                    int code = con.getResponseCode();
                    InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] b = new byte[8192]; int n;
                        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                        in.close();
                    }
                    String text = buf.toString("UTF-8");
                    if (code >= 400 && text.isEmpty()) throw new IOException("HTTP " + code);
                    callJs(callback, true, text);
                } catch (java.net.UnknownHostException e) {
                    callJs(callback, false, "No internet");
                } catch (Exception e) {
                    callJs(callback, false, String.valueOf(e.getMessage()));
                } finally {
                    if (con != null) con.disconnect();
                }
            }).start();
        }

        /** Save a file (base64) to Downloads. Callback message is where it was saved. */
        @JavascriptInterface
        public void saveFile(final String name, final String base64, final String mime, final String callback) {
            new Thread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                        v.put(MediaStore.Downloads.MIME_TYPE, mime);
                        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new IOException("Couldn't create the file");
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                        callJs(callback, true, "Downloads");
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir == null) dir = getFilesDir();
                        File f = new File(dir, safe);
                        try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                        callJs(callback, true, f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    callJs(callback, false, String.valueOf(e.getMessage()));
                }
            }).start();
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
