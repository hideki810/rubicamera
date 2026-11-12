package com.hideki810.rubicamera;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 2;
    private static final int REQ_CAMERA = 3;

    private WebView webView;
    private PermissionRequest pendingRequest;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCaptureUri;
    private File pendingCaptureFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Webページからのカメラ/マイク要求を、Androidの実行時許可につなぐ
                List<String> needed = new ArrayList<>();
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                            && checkSelfPermission(Manifest.permission.CAMERA)
                                    != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA);
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (needed.isEmpty()) {
                    request.grant(request.getResources());
                } else {
                    pendingRequest = request;
                    requestPermissions(needed.toArray(new String[0]), 1);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    boolean wantCapture = params != null && params.isCaptureEnabled();
                    if (wantCapture && launchCamera()) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // カメラ起動に失敗したら写真選択にフォールバックする
                }
                try {
                    launchGallery();
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        checkUpdate();
    }

    /**
     * 更新お知らせ：ドライブ上の版数ファイルを見て、新しい版があれば知らせる。
     * お知らせ文には職員向けにふりがな（ルビ）を付けるため、本文はWebViewで表示する。
     * 通信できないときは何もしない（静かに失敗）。
     */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                String url = "https://drive.google.com/uc?export=download&id="
                        + "1-uPJejKx5ZmPKAliO7RESMbmBR-Wlej1";
                String body = fetchText(url);
                if (body == null) {
                    return;
                }
                org.json.JSONObject json = new org.json.JSONObject(body);
                int remoteCode = json.getInt("versionCode");
                String remoteName = json.optString("versionName", "");
                String downloadUrl = json.optString("url", "");
                int currentCode = currentVersionCode();
                if (remoteCode <= currentCode || downloadUrl.isEmpty()) {
                    return;
                }
                // お知らせは1日1回まで
                android.content.SharedPreferences prefs =
                        getSharedPreferences("upd", MODE_PRIVATE);
                String today = new java.text.SimpleDateFormat("yyyyMMdd",
                        java.util.Locale.JAPAN).format(new java.util.Date());
                if (today.equals(prefs.getString("shown_day", ""))) {
                    return;
                }
                prefs.edit().putString("shown_day", today).apply();
                runOnUiThread(() -> showUpdateDialog(remoteName, downloadUrl));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private int currentVersionCode() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String fetchText(String urlStr) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            for (int i = 0; i < 4; i++) {
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) {
                        return null;
                    }
                    url = new java.net.URL(loc);
                    continue;
                }
                if (code != 200) {
                    return null;
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < 8192) {
                    out.write(buf, 0, n);
                    total += n;
                }
                in.close();
                return out.toString("UTF-8");
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void showUpdateDialog(String versionName, String downloadUrl) {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(this);
            wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            int padSide = (int) (24 * getResources().getDisplayMetrics().density);
            int padTop = (int) (16 * getResources().getDisplayMetrics().density);
            wv.setPadding(padSide, padTop, padSide, 0);
            String html = "<html><head><meta charset='utf-8'><style>"
                    + "body{font-family:sans-serif;font-size:17px;color:#1b2a41;"
                    + "line-height:2.1;margin:0}"
                    + "rt{font-size:9px;color:#2b6cb0}"
                    + "</style></head><body>"
                    + "<ruby>新<rt>あたら</rt></ruby>しいバージョン（v" + versionName + "）が"
                    + "<ruby>公開<rt>こうかい</rt></ruby>されています。<br>"
                    + "<ruby>更新<rt>こうしん</rt></ruby>しますか？"
                    + "</body></html>";
            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            new android.app.AlertDialog.Builder(this)
                    .setView(wv)
                    .setPositiveButton("更新する", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(downloadUrl)));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("あとで", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    /**
     * アプリ内蔵の撮影画面を起動する。起動できたら true。
     * 外部カメラアプリへの撮影依頼はGalaxy側の制限で弾かれるため使わない。
     */
    private boolean launchCamera() {
        File dir = new File(getCacheDir(), "captures");
        dir.mkdirs();
        pendingCaptureFile = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
        pendingCaptureUri = new Uri.Builder()
                .scheme("content")
                .authority("com.hideki810.rubicamera.fileprovider")
                .appendPath(pendingCaptureFile.getName())
                .build();
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("path", pendingCaptureFile.getAbsolutePath());
        startActivityForResult(intent, REQ_CAMERA);
        return true;
    }

    private void launchGallery() {
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryIntent.setType("image/*");
        startActivityForResult(
                Intent.createChooser(galleryIntent, "写真を選ぶ"),
                REQ_FILE_CHOOSER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
        if (requestCode == REQ_CAMERA && fileCallback != null) {
            if (resultCode == RESULT_OK
                    && pendingCaptureFile != null
                    && pendingCaptureFile.exists()
                    && pendingCaptureFile.length() > 0) {
                fileCallback.onReceiveValue(new Uri[]{pendingCaptureUri});
                fileCallback = null;
                pendingCaptureUri = null;
                pendingCaptureFile = null;
            } else if (data != null && data.getBooleanExtra("fallback_gallery", false)) {
                // 撮影できなかった場合は写真選択へ切り替える（コールバックは保持）
                try {
                    launchGallery();
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
            } else {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
                pendingCaptureUri = null;
                pendingCaptureFile = null;
            }
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (data != null && data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (pendingCaptureFile != null
                        && pendingCaptureFile.exists()
                        && pendingCaptureFile.length() > 0) {
                    results = new Uri[]{pendingCaptureUri};
                }
            }
            if (resultCode == RESULT_OK && results == null) {
                android.widget.Toast.makeText(this,
                        "写真を取得できませんでした", android.widget.Toast.LENGTH_SHORT).show();
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            pendingCaptureUri = null;
            pendingCaptureFile = null;
        }
        } catch (Exception e) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingRequest != null) {
            boolean allGranted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            if (allGranted) {
                pendingRequest.grant(pendingRequest.getResources());
            } else {
                pendingRequest.deny();
            }
            pendingRequest = null;
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
}
