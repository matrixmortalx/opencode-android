package ai.opencode.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode for Android
 *
 * Termux içinde çalışan {@code opencode serve} sunucusunun web arayüzünü
 * tam ekran gösteren sarmalayıcı uygulama. Hiçbir harici bağımlılık yoktur,
 * yalnızca Android çatısı (framework) API'leri kullanılır.
 */
public class MainActivity extends Activity {

    /** OpenCode sunucusunun adresi (opencode serve varsayılan portu: 4096). */
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 4096;
    private static final String BASE_URL = "http://" + HOST + ":" + PORT + "/";

    /**
     * Uygulama içi yerel proxy'nin tercih ettiği port. Sabit tutulur ki
     * arayüzün localStorage'ı her açılışta aynı origin'i görsün; port
     * meşgulse otomatik olarak geçici bir port kullanılır.
     */
    private static final int PROXY_PORT = 4097;

    /** Uygulamanın "başlat" düğmesinin Termux'ta çalıştırdığı betik. */
    private static final String RUN_SCRIPT =
            "/data/data/com.termux/files/home/.opencode/serve-android.sh";
    private static final String TERMUX_HOME =
            "/data/data/com.termux/files/home";

    /** HTTP Basic kimlik doğrulaması: kullanıcı adı her zaman sabittir. */
    private static final String AUTH_USER = "opencode";
    /** serve-android.sh betiğinin kullandığı varsayılan sunucu şifresi. */
    private static final String DEFAULT_PASSWORD = "opencode-local";
    private static final String PREFS_NAME = "opencode";
    private static final String PREF_PASSWORD = "server_password";

    /** Sunucu kapalıyken otomatik yeniden deneme aralığı / üst sınırı. */
    private static final long RETRY_INTERVAL_MS = 2500L;
    private static final int MAX_RETRY_ATTEMPTS = 48;

    /** WebView dosya seçicisi (sohbet'e dosya ekleme) için istek kodu. */
    private static final int REQUEST_FILE_CHOOSER = 4242;

    private WebView web;
    private ProgressBar progress;
    private LinearLayout errorView;
    private TextView errorTitle;
    private TextView errorMessage;
    private TextView waitingText;
    private TextView crashView;
    private Button startButton;
    private Button retryButton;
    private Button authButton;
    private Button fab;

    private SharedPreferences prefs;
    private boolean authAttempted = false;

    /**
     * Yerel proxy: WebView'ın TÜM isteklerine (fetch/XHR ve SSE akışları
     * dahil) Basic kimlik bilgisini ekleyip 4096'daki sunucuya iletir.
     * WebView'in onReceivedHttpAuthRequest geri çağrısı sayfanın kendi
     * fetch isteklerini kapsamadığı için gereklidir; proxy sayesinde
     * OpenCode arayüzünün kendi giriş ekranı hiç belirmez.
     */
    private ServerSocket proxyServer;
    private int proxyPort = -1;
    private volatile boolean proxyAuthFailureNotified = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lastMainFrameError = false;
    private boolean waitingForServer = false;
    private int retryAttempts = 0;

    /** Bekleyen dosya seçici sonucu (WebView'a iletilecek). */
    private ValueCallback<Uri[]> fileChooserCallback;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!waitingForServer) {
                return;
            }
            retryAttempts++;
            if (retryAttempts > MAX_RETRY_ATTEMPTS) {
                waitingForServer = false;
                showError(getString(R.string.timeout_message));
                return;
            }
            waitingText.setText(getString(R.string.waiting_counter, retryAttempts));
            web.reload();
            handler.postDelayed(this, RETRY_INTERVAL_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashReporter();
        try {
            setupUi();
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    private void setupUi() {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Window window = getWindow();
        window.setStatusBarColor(0xFF0B0D10);
        window.setNavigationBarColor(0xFF0B0D10);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D10);

        // ------------------------------------------------------------------
        // WebView — OpenCode web arayüzü
        // ------------------------------------------------------------------
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D10);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        WebView.setWebContentsDebuggingEnabled(true);

        web.setWebViewClient(new OpenCodeWebViewClient());
        web.setWebChromeClient(new OpenCodeChromeClient());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    // Tarayıcı yoksa sessizce yoksay.
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ------------------------------------------------------------------
        // Üstte ince yükleme çubuğu
        // ------------------------------------------------------------------
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.getProgressDrawable().setColorFilter(
                new android.graphics.PorterDuffColorFilter(
                        0xFF2DD4BF, android.graphics.PorterDuff.Mode.SRC_IN));
        progress.setVisibility(View.GONE);
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));

        // ------------------------------------------------------------------
        // Sunucuya ulaşılamadığında gösterilen ekran
        // ------------------------------------------------------------------
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ------------------------------------------------------------------
        // Sağ altta yüzen yenileme düğmesi
        // ------------------------------------------------------------------
        fab = buildFab();
        fab.setVisibility(View.GONE);
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(
                dp(52), dp(52), Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(0, 0, dp(16), dp(24));
        root.addView(fab, fabParams);

        setContentView(root);
        startProxy();
        web.loadUrl(getAppUrl());
    }

    // ----------------------------------------------------------------------
    // Çökme raporlayıcı — hata olursa uygulama kapanmak yerine ekranda
    // hatanın tam metnini gösterir.
    // ----------------------------------------------------------------------

    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, final Throwable ex) {
                try {
                    showCrash(ex);
                } catch (Throwable ignored) {
                    // Raporlayıcı kendisi hata verirse sessizce geç.
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    // Ana iş parçacığı çöktü: döngüyü yeniden başlatarak
                    // uygulamanın anında kapanmasını engelle; hata ekranda kalsın.
                    Looper.loop();
                } else if (previous != null) {
                    previous.uncaughtException(thread, ex);
                }
            }
        });
    }

    private void showCrash(Throwable t) {
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        final String report = "v1.0.7\n"
                + t.getClass().getName() + ": " + t.getMessage()
                + "\n\n" + trace.toString();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF140505);

        TextView title = new TextView(this);
        title.setText("⚠ Uygulama bir hatayla karşılaştı");
        title.setTextColor(0xFFFF7B72);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        title.setPadding(dp(12), dp(16), dp(12), dp(4));
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Hata raporu sunucuya gönderiliyor… (bu metni kopyalayıp da iletebilirsin)");
        hint.setTextColor(0xFFB08585);
        hint.setTextSize(12);
        hint.setPadding(dp(12), 0, dp(12), dp(8));
        box.addView(hint);

        Button openBrowser = new Button(this, null, android.R.attr.borderlessButtonStyle);
        openBrowser.setText(R.string.open_in_browser);
        openBrowser.setAllCaps(false);
        openBrowser.setTextColor(0xFF2DD4BF);
        openBrowser.setPadding(dp(12), dp(6), dp(12), dp(6));
        openBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BASE_URL)));
                } catch (Throwable ignored) {
                    // Tarayıcı yoksa sessizce yoksay.
                }
            }
        });
        box.addView(openBrowser);

        crashView = new TextView(this);
        crashView.setTextColor(0xFFFF7B72);
        crashView.setTypeface(Typeface.MONOSPACE);
        crashView.setTextSize(10);
        crashView.setText(report);
        crashView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(crashView);
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(box);
        sendCrashReport(report);
    }

    /**
     * Çökme raporunu yerel OpenCode sunucusuna gönderir. Sunucu bu isteği
     * ~/.local/share/opencode/log/opencode.log dosyasına kaydeder; rapor
     * URL yoluna base64 olarak gömülür.
     */
    private void sendCrashReport(final String report) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    String payload = Base64.encodeToString(
                            report.getBytes("UTF-8"),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    if (payload.length() > 1800) {
                        payload = payload.substring(0, 1800);
                    }
                    URL url = new URL(getAppUrl() + "api/android-crash-report/" + payload);
                    HttpURLConnection connection =
                            (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("Authorization", basicAuthorization());
                    connection.setConnectTimeout(2000);
                    connection.setReadTimeout(2000);
                    connection.connect();
                    connection.getResponseCode(); // 404/401 olsa bile istek günlüğe düşer
                    connection.disconnect();
                } catch (Throwable sendFailure) {
                    failure = sendFailure.getClass().getSimpleName()
                            + (sendFailure.getMessage() != null
                                    ? ": " + sendFailure.getMessage()
                                    : "");
                }
                markReportSent(failure);
            }
        }, "crash-reporter").start();
    }

    private void markReportSent(final String failure) {
        final TextView view = crashView;
        if (view == null) {
            return;
        }
        view.post(new Runnable() {
            @Override
            public void run() {
                view.append(failure == null
                        ? "\n\n✔ Rapor sunucuya gönderildi."
                        : "\n\n✖ Rapor gönderilemedi: " + failure);
            }
        });
    }

    // ----------------------------------------------------------------------
    // Yerel kimlik doğrulama proxy'si
    //
    // WebView, sayfanın kendi fetch/XHR isteklerinde HTTP Basic kimlik
    // doğrulamasını sormaz; bu yüzden arayüz 401 alıp kendi giriş ekranını
    // gösteriyordu. Çözüm: uygulama 127.0.0.1 üzerinde minik bir HTTP
    // proxy açar (tercihen 4097), WebView bu proxy'ye bağlanır, proxy her
    // isteğe "Authorization: Basic ..." başlığı ekleyip gerçek sunucuya
    // (4096) iletir. Böylece tarayıcı tarafındaki TÜM trafik — arayüzün
    // API çağrıları, dosya yüklemeleri ve /api/event SSE akışı dahil —
    // şeffaf biçimde kimlik doğrulanmış olur.
    // ----------------------------------------------------------------------

    /** WebView'ın yükleyeceği adres: proxy çalışıyorsa proxy, yoksa sunucu. */
    private String getAppUrl() {
        return proxyPort > 0
                ? "http://" + HOST + ":" + proxyPort + "/"
                : BASE_URL;
    }

    private String currentPassword() {
        return prefs != null
                ? prefs.getString(PREF_PASSWORD, DEFAULT_PASSWORD)
                : DEFAULT_PASSWORD;
    }

    private String basicAuthorization() {
        String raw = AUTH_USER + ":" + currentPassword();
        byte[] bytes;
        try {
            bytes = raw.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = raw.getBytes();
        }
        return "Basic " + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private void startProxy() {
        if (proxyServer != null) {
            return;
        }
        try {
            try {
                proxyServer = new ServerSocket(PROXY_PORT, 64,
                        InetAddress.getByName(HOST));
            } catch (IOException busy) {
                // Port meşgulse (ör. eski bir uygulama örneği) geçici port.
                proxyServer = new ServerSocket(0, 64, InetAddress.getByName(HOST));
            }
            proxyPort = proxyServer.getLocalPort();
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (proxyServer != null && !proxyServer.isClosed()) {
                        try {
                            final Socket client = proxyServer.accept();
                            Thread worker = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handleProxyClient(client);
                                }
                            }, "oc-proxy-client");
                            worker.setDaemon(true);
                            worker.start();
                        } catch (IOException e) {
                            break; // Proxy kapatıldı.
                        }
                    }
                }
            }, "oc-proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (Throwable t) {
            proxyServer = null;
            proxyPort = -1; // Doğrudan bağlantıya geri dön (v1.0.4 davranışı).
        }
    }

    private void stopProxy() {
        ServerSocket server = proxyServer;
        proxyServer = null;
        proxyPort = -1;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Proxy 401 gördüğünde (şifre yanlış) şifre ekranını bir kez gösterir. */
    private void notifyProxyAuthFailure() {
        if (proxyAuthFailureNotified) {
            return;
        }
        proxyAuthFailureNotified = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                showAuthError();
            }
        });
    }

    private void handleProxyClient(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(60000); // İstek fazı için makul üst sınır.
            BufferedInputStream clientIn =
                    new BufferedInputStream(client.getInputStream());

            String requestLine = readHttpLine(clientIn);
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                return;
            }
            String method = parts[0];
            String target = parts[1];
            // Nadir: mutlak biçimli (proxy stili) istek satırını kök biçimine indir.
            if (target.startsWith("http://") || target.startsWith("https://")) {
                int pathStart = target.indexOf('/', target.indexOf("//") + 2);
                target = pathStart >= 0 ? target.substring(pathStart) : "/";
            }

            List<String> requestHeaders = new ArrayList<>();
            String contentLengthHeader = null;
            String headerLine;
            while ((headerLine = readHttpLine(clientIn)) != null
                    && headerLine.length() > 0) {
                requestHeaders.add(headerLine);
                int colon = headerLine.indexOf(':');
                if (colon > 0 && headerLine.substring(0, colon).trim()
                        .equalsIgnoreCase("content-length")) {
                    contentLengthHeader = headerLine.substring(colon + 1).trim();
                }
            }

            long bodyLength = 0L;
            if (contentLengthHeader != null) {
                try {
                    bodyLength = Long.parseLong(contentLengthHeader);
                } catch (NumberFormatException ignored) {
                    bodyLength = 0L;
                }
            }

            // Upstream kurulamazsa istemciye yanıt yazmadan soketi kapat:
            // WebView bunu bağlantı hatası sayar, mevcut "sunucu kapalı"
            // hata/bekleme ekranları aynen çalışmaya devam eder.
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(HOST, PORT), 5000);
            upstream.setTcpNoDelay(true);
            OutputStream upstreamOut = upstream.getOutputStream();

            StringBuilder out = new StringBuilder(512);
            out.append(method).append(' ').append(target).append(' ')
                    .append(parts[parts.length - 1]).append("\r\n");
            out.append("Host: ").append(HOST).append(':').append(PORT).append("\r\n");
            out.append("Authorization: ").append(basicAuthorization()).append("\r\n");
            out.append("Connection: close\r\n");
            for (String header : requestHeaders) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = header.substring(0, colon).trim();
                if (name.equalsIgnoreCase("host")
                        || name.equalsIgnoreCase("connection")
                        || name.equalsIgnoreCase("keep-alive")
                        || name.equalsIgnoreCase("proxy-connection")
                        || name.equalsIgnoreCase("proxy-authorization")
                        || name.equalsIgnoreCase("authorization")
                        || name.equalsIgnoreCase("te")
                        || name.equalsIgnoreCase("trailer")
                        || name.equalsIgnoreCase("upgrade")) {
                    continue; // Bu başlıkları biz kendi değerlerimizle gönderiyoruz.
                }
                out.append(header).append("\r\n");
            }
            out.append("\r\n");
            upstreamOut.write(out.toString().getBytes("UTF-8"));

            if (bodyLength > 0L) {
                copyExactly(clientIn, upstreamOut, bodyLength);
            }
            upstreamOut.flush();

            BufferedInputStream upstreamIn =
                    new BufferedInputStream(upstream.getInputStream());
            String statusLine = readHttpLine(upstreamIn);
            if (statusLine == null || statusLine.length() == 0) {
                return;
            }
            List<String> responseHeaders = new ArrayList<>();
            while ((headerLine = readHttpLine(upstreamIn)) != null
                    && headerLine.length() > 0) {
                responseHeaders.add(headerLine);
            }

            // Şifre yanlışsa sunucu 401 döner: şifre ekranını göster.
            String[] statusParts = statusLine.split(" ");
            if (statusParts.length >= 2 && "401".equals(statusParts[1])) {
                notifyProxyAuthFailure();
            }

            OutputStream clientOut = client.getOutputStream();
            StringBuilder response = new StringBuilder(512);
            response.append(statusLine).append("\r\n");
            response.append("Connection: close\r\n");
            for (String header : responseHeaders) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = header.substring(0, colon).trim();
                if (name.equalsIgnoreCase("connection")
                        || name.equalsIgnoreCase("keep-alive")
                        || name.equalsIgnoreCase("proxy-connection")
                        || name.equalsIgnoreCase("proxy-authenticate")) {
                    continue;
                }
                response.append(header).append("\r\n");
            }
            response.append("\r\n");
            clientOut.write(response.toString().getBytes("UTF-8"));
            clientOut.flush();

            // Gövdeyi (SSE akışları dahil) gerçek zamanlı aktar.
            pump(upstreamIn, clientOut);
        } catch (IOException ignored) {
            // Bağlantı koptu ya da istemci vazgeçti: yapacak bir şey yok.
        } catch (Throwable t) {
            // Beklenmeyen hata: proxy asla uygulamayı çökertmemeli.
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** CRLF ile sonlanan tek HTTP başlık satırı okur. */
    private String readHttpLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(96);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() > 16384) {
                throw new IOException("HTTP header line too long");
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    /** Kaynaktan tam olarak {@code count} bayt okuyup karşıya yazar. */
    private void copyExactly(InputStream in, OutputStream out, long count)
            throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("early EOF reading request body");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** Kaynak kapanana dek baytları karşıya akıtır (SSE için gerekli). */
    private void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }

    // ----------------------------------------------------------------------
    // Görünümler
    // ----------------------------------------------------------------------

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setBackgroundColor(0xFF0B0D10);

        TextView glyph = new TextView(this);
        glyph.setText("</>");
        glyph.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        glyph.setTextSize(38);
        glyph.setTextColor(0xFF2DD4BF);
        glyph.setGravity(Gravity.CENTER);
        box.addView(glyph);

        errorTitle = new TextView(this);
        errorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        errorTitle.setTextSize(20);
        errorTitle.setTextColor(0xFFF0F6FC);
        errorTitle.setGravity(Gravity.CENTER);
        errorTitle.setPadding(0, dp(16), 0, dp(8));
        box.addView(errorTitle);

        errorMessage = new TextView(this);
        errorMessage.setTextSize(14);
        errorMessage.setTextColor(0xFF9BA8B7);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setLineSpacing(dp(2), 1f);
        box.addView(errorMessage);

        startButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        startButton.setText(R.string.start_server);
        startButton.setAllCaps(false);
        startButton.setTypeface(Typeface.DEFAULT_BOLD);
        startButton.setTextSize(15);
        startButton.setTextColor(0xFF06281F);
        startButton.setPadding(dp(28), dp(12), dp(28), dp(12));
        startButton.setBackgroundResource(R.drawable.btn_primary);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServerInTermux();
            }
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startParams.topMargin = dp(28);
        box.addView(startButton, startParams);

        retryButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        retryButton.setText(R.string.retry);
        retryButton.setAllCaps(false);
        retryButton.setTextSize(15);
        retryButton.setTextColor(0xFF2DD4BF);
        retryButton.setPadding(dp(28), dp(10), dp(28), dp(10));
        retryButton.setBackgroundResource(R.drawable.btn_secondary);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showError(null);
                web.reload();
            }
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(10);
        box.addView(retryButton, retryParams);

        authButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        authButton.setText(R.string.auth_button);
        authButton.setAllCaps(false);
        authButton.setTextSize(14);
        authButton.setTextColor(0xFF9BA8B7);
        authButton.setPadding(dp(20), dp(8), dp(20), dp(8));
        authButton.setVisibility(View.GONE);
        authButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPasswordDialog();
            }
        });
        LinearLayout.LayoutParams authParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authParams.topMargin = dp(4);
        box.addView(authButton, authParams);

        waitingText = new TextView(this);
        waitingText.setTextSize(12);
        waitingText.setTextColor(0xFF2DD4BF);
        waitingText.setGravity(Gravity.CENTER);
        waitingText.setPadding(0, dp(20), 0, 0);
        box.addView(waitingText);

        // NOT: Burada showError() çağırılamaz! errorView alanı bu metod
        // dönünce atanır; showError içinde errorView.setVisibility(...)
        // yapıldığından null referans hatası (NPE) oluşur. Başlangıç
        // metinleri doğrudan ayarlanır.
        errorTitle.setText(R.string.error_title);
        errorMessage.setText(R.string.error_message);
        return box;
    }

    private Button buildFab() {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText("↻");
        button.setTextSize(20);
        button.setAllCaps(false);
        button.setTextColor(0xFFF0F6FC);
        button.setBackgroundResource(R.drawable.fab_bg);
        button.setElevation(dp(6));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.reload();
            }
        });
        button.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showQuickMenu();
                return true;
            }
        });
        return button;
    }

    /** Yenileme düğmesine uzun basınca açılan hızlı menü. */
    private void showQuickMenu() {
        final CharSequence[] items = new CharSequence[]{
                getString(R.string.menu_reload),
                getString(R.string.menu_change_model),
                getString(R.string.menu_password),
                getString(R.string.menu_open_browser)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (web != null) web.reload();
                        } else if (which == 1) {
                            showModelPicker();
                        } else if (which == 2) {
                            showPasswordDialog();
                        } else if (which == 3) {
                            openInBrowser();
                        }
                    }
                })
                .show();
    }

    private void openInBrowser() {
        String url = (proxyPort > 0)
                ? "http://" + HOST + ":" + proxyPort + "/"
                : BASE_URL;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.menu_open_browser_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------------
    // Durum geçişleri
    // ----------------------------------------------------------------------

    private void showWaiting() {
        if (errorTitle == null || errorView == null) {
            return;
        }
        errorTitle.setText(R.string.waiting_title);
        errorMessage.setText(R.string.waiting_message);
        waitingText.setText(getString(R.string.waiting_counter, 0));
        startButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (errorTitle == null || errorView == null) {
            return;
        }
        errorTitle.setText(R.string.error_title);
        errorMessage.setText(message != null
                ? message
                : getString(R.string.error_message));
        waitingText.setText("");
        startButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        authButton.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
    }

    /** Sunucu 401 döndürdüğünde gösterilen, şifre düzenlemeye izin veren ekran. */
    private void showAuthError() {
        if (errorTitle == null || errorView == null) {
            return;
        }
        waitingForServer = false;
        errorTitle.setText(R.string.auth_error_title);
        errorMessage.setText(R.string.auth_error_message);
        waitingText.setText("");
        startButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
        authButton.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
    }

    /**
     * Sunucu şifresini düzenleme penceresi. Şifre kaydedilince sayfa
     * yeniden yüklenir ve yeni şifreyle otomatik giriş denenir.
     */
    private void showPasswordDialog() {
        if (prefs == null) {
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String current = prefs.getString(PREF_PASSWORD, DEFAULT_PASSWORD);
        input.setText(current);
        input.setSelection(current.length());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.auth_title)
                .setMessage(R.string.auth_message)
                .setView(input)
                .setPositiveButton(R.string.auth_save,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                String password = input.getText().toString();
                                prefs.edit().putString(PREF_PASSWORD, password).apply();
                                authAttempted = false;
                                proxyAuthFailureNotified = false;
                                hideError();
                                web.reload();
                            }
                        })
                .setNegativeButton(R.string.auth_cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                authAttempted = false;
                                showAuthError();
                            }
                        })
                .setCancelable(false)
                .create();
        dialog.show();
    }

    private void hideError() {
        waitingForServer = false;
        retryAttempts = 0;
        handler.removeCallbacks(retryRunnable);
        if (errorView == null) {
            return;
        }
        errorView.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
    }

    /**
     * Termux'un RUN_COMMAND arayüzüyle sunucu başlatma betiğini çağırır.
     * Gereksinim: ~/.termux/termux.properties içinde allow-external-apps = true
     * ve ~/.opencode/serve-android.sh betiğinin kurulu olması.
     */
    private void startServerInTermux() {
        Intent command = new Intent("com.termux.RUN_COMMAND");
        command.setComponent(new ComponentName(
                "com.termux", "com.termux.app.RunCommandService"));
        command.putExtra("com.termux.RUN_COMMAND_PATH", RUN_SCRIPT);
        command.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        try {
            startService(command);
            Toast.makeText(this, R.string.termux_started, Toast.LENGTH_SHORT).show();
            waitingForServer = true;
            retryAttempts = 0;
            handler.removeCallbacks(retryRunnable);
            showWaiting();
            handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
        } catch (Exception e) {
            waitingForServer = false;
            showError(getString(R.string.start_failed)
                    + "\n(" + e.getClass().getSimpleName() + ")"
                    + "\n\n" + getString(R.string.manual_hint));
        }
    }

    // ----------------------------------------------------------------------
    // WebView istemcileri
    // ----------------------------------------------------------------------

    private class OpenCodeWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            String host = url.getHost();
            // Yerel sunucu sayfaları WebView içinde açılır; dış bağlantılar tarayıcıya.
            if ("http".equals(url.getScheme())
                    && (HOST.equals(host) || "localhost".equals(host))) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {
                // Tarayıcı yoksa sessizce yoksay.
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            lastMainFrameError = false;
            authAttempted = false;
            proxyAuthFailureNotified = false;
        }

        /**
         * Sunucu HTTP Basic kimlik doğrulaması ister (kullanıcı adı: opencode).
         * İlk denemede kayıtlı şifre otomatik gönderilir; şifre yanlışsa
         * istek iptal edilir ve şifre giriş ekranı gösterilir.
         */
        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                String host, String realm) {
            if (!authAttempted) {
                authAttempted = true;
                String password = prefs != null
                        ? prefs.getString(PREF_PASSWORD, DEFAULT_PASSWORD)
                        : DEFAULT_PASSWORD;
                handler.proceed(AUTH_USER, password);
            } else {
                // Otomatik deneme başarısız oldu: isteği iptal et ve kullanıcıdan
                // şifre iste. Böylece üst üste şifre pencereleri açılmaz.
                handler.cancel();
                showAuthError();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() == 401) {
                // onPageFinished bu ekranı hemen gizlemesin.
                lastMainFrameError = true;
                showAuthError();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (!lastMainFrameError) {
                hideError();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request.isForMainFrame()) {
                lastMainFrameError = true;
                if (waitingForServer) {
                    showWaiting();
                } else {
                    showError(null);
                }
            }
        }
    }

    private class OpenCodeChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        /**
         * Sohbet'e dosya ekleme: arayüzdeki ekle düğmesi bir
         * input[type=file] açar; WebView bunu ancak burada ele alırsak
         * çalıştırır. Seçilen dosya(lar) arayüze URI olarak iletilir ve
         * yükleme proxy üzerinden (kimlik bilgisi eklenmiş) gider.
         */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (fileChooserCallback != null) {
                // Askıda kalan önceki seçiciyi iptal et.
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;

            Intent intent = null;
            try {
                intent = params.createIntent();
            } catch (Throwable ignored) {
                intent = null;
            }
            if (intent == null
                    || intent.resolveActivity(getPackageManager()) == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }
            try {
                startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                return true;
            } catch (Throwable t) {
                fileChooserCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this,
                        R.string.file_picker_failed, Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    // ----------------------------------------------------------------------
    // Model değiştirme (sunucu API'si üzerinden)
    // ----------------------------------------------------------------------

    /** Sunucuya doğrudan (4096) kimlik bilgisiyle HTTP isteği yapar. */
    private String apiCall(String method, String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://" + HOST + ":" + PORT + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Authorization", basicAuthorization());
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        return sb.toString();
    }

    /** Modelleri listeler ve seçileni geçerli oturuma uygular. */
    private void showModelPicker() {
        Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String sessionId = findTargetSessionId();
                    if (sessionId == null) {
                        postToast(getString(R.string.model_no_session));
                        return;
                    }
                    String raw = apiCall("GET", "/api/model", null);
                    org.json.JSONObject root = new org.json.JSONObject(raw);
                    org.json.JSONArray data = root.optJSONArray("data");
                    if (data == null || data.length() == 0) {
                        postToast(getString(R.string.model_none));
                        return;
                    }

                    final ArrayList<String> labels = new ArrayList<String>();
                    final ArrayList<String> modelIds = new ArrayList<String>();
                    final ArrayList<String> providerIds = new ArrayList<String>();
                    final ArrayList<String> variants = new ArrayList<String>();

                    for (int i = 0; i < data.length(); i++) {
                        org.json.JSONObject m = data.optJSONObject(i);
                        if (m == null) continue;
                        if (!m.optBoolean("enabled", true)) continue;
                        String status = m.optString("status", "active");
                        if (!"active".equals(status) && !"beta".equals(status)) continue;
                        String id = m.optString("id", m.optString("modelID", ""));
                        String pid = m.optString("providerID", "opencode");
                        String name = m.optString("name", id);
                        if (id.length() == 0) continue;

                        org.json.JSONArray vs = m.optJSONArray("variants");
                        if (vs != null && vs.length() > 0) {
                            for (int j = 0; j < vs.length(); j++) {
                                org.json.JSONObject v = vs.optJSONObject(j);
                                String vid = (v != null) ? v.optString("id", "") : "";
                                if (vid.length() == 0) continue;
                                labels.add(name + " (" + vid + ")");
                                modelIds.add(id);
                                providerIds.add(pid);
                                variants.add(vid);
                            }
                        } else {
                            labels.add(name);
                            modelIds.add(id);
                            providerIds.add(pid);
                            variants.add("");
                        }
                    }

                    if (labels.isEmpty()) {
                        postToast(getString(R.string.model_none));
                        return;
                    }

                    final String targetSession = sessionId;
                    final CharSequence[] items = labels.toArray(new CharSequence[labels.size()]);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.model_pick_title)
                                    .setItems(items, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            applyModel(targetSession,
                                                    modelIds.get(which),
                                                    providerIds.get(which),
                                                    variants.get(which),
                                                    String.valueOf(items[which]));
                                        }
                                    })
                                    .setNegativeButton(R.string.auth_cancel, null)
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    postToast(getString(R.string.model_fetch_failed));
                }
            }
        }).start();
    }

    /** En güncel oturumun kimliğini döndürür (yoksa null). */
    private String findTargetSessionId() throws Exception {
        String raw = apiCall("GET", "/api/session", null);
        org.json.JSONObject root = new org.json.JSONObject(raw);
        org.json.JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        String bestId = null;
        long bestTime = -1;
        for (int i = 0; i < data.length(); i++) {
            org.json.JSONObject s = data.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id", "");
            if (id.length() == 0) continue;
            org.json.JSONObject t = s.optJSONObject("time");
            long u = 0;
            if (t != null) {
                u = t.optLong("updated", t.optLong("viewed", t.optLong("created", 0)));
            }
            if (bestId == null || u >= bestTime) {
                bestTime = u;
                bestId = id;
            }
        }
        return bestId;
    }

    /** Seçilen modeli belirtilen oturuma uygular. */
    private void applyModel(final String sessionId, final String modelId,
            final String providerId, final String variant, final String label) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONObject model = new org.json.JSONObject();
                    model.put("id", modelId);
                    model.put("providerID", providerId);
                    if (variant != null && variant.length() > 0) {
                        model.put("variant", variant);
                    }
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("model", model);
                    apiCall("POST", "/api/session/" + sessionId + "/model", body.toString());
                    postToast(getString(R.string.model_changed, label));
                } catch (Exception e) {
                    postToast(getString(R.string.model_change_failed));
                }
            }
        }).start();
    }

    private void postToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ----------------------------------------------------------------------
    // Yaşam döngüsü
    // ----------------------------------------------------------------------

    /** Dosya seçici sonucunu WebView'a iletir (iptal edilse bile). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = fileChooserCallback;
            fileChooserCallback = null;
            if (callback == null) {
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[] { data.getData() };
                } else if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    int count = clip.getItemCount();
                    if (count > 0) {
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = clip.getItemAt(i).getUri();
                        }
                    }
                }
            }
            // Sonuç null olursa arayüz "iptal edildi" sayar; null göndermek
            // zorunludur, yoksa bir daha dosya seçici açılamaz.
            callback.onReceiveValue(results);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopProxy();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
