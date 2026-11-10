package pt.camac.inventariocc;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Mostra a página de inventário (assets/index.html) e grava as exportações em Download. */
public class MainActivity extends Activity {

    private static final String FOLDER = "InventarioColourCode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // a contagem fica em localStorage
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        /** Grava o ficheiro e devolve onde ficou; devolve "" se falhar. */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(data);
                    }
                    return "Download/" + FOLDER + "/" + safe;
                }
                // Android 9 ou anterior: pasta da própria app, sem pedir permissões
                File dir = new File(getExternalFilesDir(null), FOLDER);
                dir.mkdirs();
                File file = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                return file.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
