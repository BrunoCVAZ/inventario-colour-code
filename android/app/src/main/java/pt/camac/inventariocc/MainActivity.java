package pt.camac.inventariocc;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mostra a página de inventário (assets/index.html), grava as exportações em Download e envia-as para o OneDrive. */
public class MainActivity extends Activity {

    private static final String FOLDER = "InventarioColourCode";

    // Registo de aplicação Entra "Inventario Colour Code": cliente público, sem segredo.
    // O início de sessão é por código de dispositivo (o utilizador escreve o código noutro equipamento).
    private static final String TENANT = "67b0f7e5-69fe-47ef-9e0c-5884c7ec21c7";
    private static final String CLIENT_ID = "02688807-a361-4800-a861-05002b96249b";
    private static final String SCOPE = "Files.ReadWrite offline_access";
    private static final String LOGIN = "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/";
    private static final String ONEDRIVE_FOLDER = "Inventario Colour Code";
    // Excel de necessidades guardado na mesma pasta; a app só escreve na folha do inventário.
    private static final String PLAN_FILE = "Necessidades de taloes.xlsx";
    private static final String PLAN_SHEET = "Inventario";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int signInAttempt = 0;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
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

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("onedrive", MODE_PRIVATE);
    }

    private void saveToken(String refreshToken) {
        if (refreshToken == null) prefs().edit().remove("refresh").apply();
        else prefs().edit().putString("refresh", refreshToken).apply();
    }

    /** Chama window.onOneDrive(evento, ...) na página. */
    private void notifyPage(String... args) {
        StringBuilder js = new StringBuilder("window.onOneDrive&&window.onOneDrive(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) js.append(',');
            js.append(JSONObject.quote(args[i]));
        }
        js.append(')');
        runOnUiThread(() -> web.evaluateJavascript(js.toString(), null));
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "{}";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        try (InputStream s = in) {
            while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    private static JSONObject postForm(String url, String... keyValues) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(URLEncoder.encode(keyValues[i], "UTF-8")).append('=')
                .append(URLEncoder.encode(keyValues[i + 1], "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new JSONObject(readAll(c));
    }

    /** Primeira linha da descrição de erro do início de sessão Microsoft. */
    private static String loginError(JSONObject r) {
        String text = r.optString("error_description", r.optString("error", "?"));
        return text.split("\r?\n")[0];
    }

    private void signIn() {
        final int attempt = ++signInAttempt;
        worker.execute(() -> {
            try {
                JSONObject d = postForm(LOGIN + "devicecode", "client_id", CLIENT_ID, "scope", SCOPE);
                if (!d.has("device_code")) {
                    notifyPage("error", loginError(d));
                    return;
                }
                notifyPage("code", d.getString("user_code"), d.getString("verification_uri"));
                long deadline = System.currentTimeMillis() + d.optInt("expires_in", 900) * 1000L;
                int interval = d.optInt("interval", 5);
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L);
                    if (attempt != signInAttempt) return;   // cancelado ou substituído
                    JSONObject r = postForm(LOGIN + "token",
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id", CLIENT_ID,
                        "device_code", d.getString("device_code"));
                    if (r.has("refresh_token")) {
                        saveToken(r.getString("refresh_token"));
                        notifyPage("signedIn");
                        return;
                    }
                    String err = r.optString("error");
                    if ("slow_down".equals(err)) {
                        interval += 5;
                    } else if (!"authorization_pending".equals(err)) {
                        notifyPage("error", loginError(r));
                        return;
                    }
                }
                notifyPage("error", "timeout");
            } catch (Exception e) {
                notifyPage("error", String.valueOf(e.getMessage()));
            }
        });
    }

    private static String folderUrl() {
        return "https://graph.microsoft.com/v1.0/me/drive/root:/" + Uri.encode(ONEDRIVE_FOLDER) + "/";
    }

    /** Token de acesso a partir da sessão guardada; se não for possível avisa a página e devolve null. */
    private String accessToken() throws Exception {
        String refresh = prefs().getString("refresh", null);
        if (refresh == null) {
            notifyPage("error", "sign-in required");
            return null;
        }
        JSONObject tok = postForm(LOGIN + "token",
            "grant_type", "refresh_token",
            "client_id", CLIENT_ID,
            "refresh_token", refresh,
            "scope", SCOPE);
        if (!tok.has("access_token")) {
            // sessão revogada ou expirada: obriga a novo início de sessão
            if ("invalid_grant".equals(tok.optString("error"))) saveToken(null);
            notifyPage("error", loginError(tok));
            return null;
        }
        if (tok.has("refresh_token")) saveToken(tok.getString("refresh_token"));
        return tok.getString("access_token");
    }

    private static String graphError(int code, String body) {
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) return err.optString("message", "HTTP " + code);
        } catch (Exception ignored) {
            // corpo sem JSON
        }
        return "HTTP " + code;
    }

    private static HttpURLConnection send(String method, String url, String token, String contentType, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try {
            c.setRequestMethod(method);
        } catch (ProtocolException e) {
            // implementações sem PATCH: o Graph aceita o método no cabeçalho
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", method);
        }
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", contentType);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        }
        return c;
    }

    private void upload(String name, String content) {
        worker.execute(() -> {
            try {
                String token = accessToken();
                if (token == null) return;
                String safe = sanitize(name);
                HttpURLConnection c = send("PUT", folderUrl() + Uri.encode(safe) + ":/content", token,
                    "application/octet-stream", Base64.decode(content, Base64.DEFAULT));
                int code = c.getResponseCode();
                String body = readAll(c);
                if (code >= 200 && code < 300) notifyPage("uploaded", "OneDrive/" + ONEDRIVE_FOLDER + "/" + safe);
                else notifyPage("error", graphError(code, body));
            } catch (Exception e) {
                notifyPage("error", String.valueOf(e.getMessage()));
            }
        });
    }

    /**
     * Escreve cada folha do JSON ({"Inventario": linhas, "Nucleos": linhas}) no Excel de necessidades,
     * a partir de A1. A app só toca nessas folhas.
     */
    private void updatePlan(String sheetsJson) {
        worker.execute(() -> {
            try {
                JSONObject sheets = new JSONObject(sheetsJson);
                String token = accessToken();
                if (token == null) return;
                List<String> missing = new ArrayList<>();
                for (Iterator<String> it = sheets.keys(); it.hasNext(); ) {
                    String sheet = it.next();
                    if (!sheet.matches("[A-Za-z]+")) continue;
                    JSONArray rows = sheets.getJSONArray(sheet);
                    String url = folderUrl() + Uri.encode(PLAN_FILE) + ":/workbook/worksheets/" + sheet
                        + "/range(address='A1:C" + rows.length() + "')";
                    byte[] body = new JSONObject().put("values", rows).toString().getBytes(StandardCharsets.UTF_8);
                    HttpURLConnection c = send("PATCH", url, token, "application/json", body);
                    int code = c.getResponseCode();
                    String text = readAll(c);
                    if (code == 404) {
                        missing.add(sheet);
                    } else if (code < 200 || code >= 300) {
                        notifyPage("error", graphError(code, text));
                        return;
                    }
                }
                if (missing.isEmpty()) notifyPage("planUpdated");
                else if (missing.contains(PLAN_SHEET)) notifyPage("planMissing");
                else notifyPage("planNoSheet", String.join(", ", missing));
            } catch (Exception e) {
                notifyPage("error", String.valueOf(e.getMessage()));
            }
        });
    }

    private class Bridge {
        /** Grava o ficheiro (conteúdo em base64) e devolve onde ficou; devolve "" se falhar. */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                String safe = sanitize(name);
                byte[] data = Base64.decode(content, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
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

        @JavascriptInterface
        public boolean oneDriveStatus() {
            return prefs().getString("refresh", null) != null;
        }

        @JavascriptInterface
        public void oneDriveSignIn() {
            signIn();
        }

        @JavascriptInterface
        public void oneDriveCancel() {
            signInAttempt++;
        }

        @JavascriptInterface
        public void oneDriveSignOut() {
            signInAttempt++;
            saveToken(null);
        }

        /** O resultado chega à página por window.onOneDrive("planUpdated" | "planMissing" | "planNoSheet" | "error", texto). */
        @JavascriptInterface
        public void oneDriveUpdatePlan(String sheetsJson) {
            updatePlan(sheetsJson);
        }

        /** O resultado chega à página por window.onOneDrive("uploaded" | "error", texto). */
        @JavascriptInterface
        public void oneDriveUpload(String name, String content) {
            upload(name, content);
        }
    }
}
