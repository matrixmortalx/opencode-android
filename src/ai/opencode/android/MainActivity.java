package ai.opencode.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

    /** Uygulamanın "başlat" düğmesinin Termux'ta çalıştırdığı betik. */
    private static final String RUN_SCRIPT =
            "/data/data/com.termux/files/home/.opencode/serve-android.sh";
    private static final String TERMUX_HOME =
            "/data/data/com.termux/files/home";

    /** Sunucu kapalıyken otomatik yeniden deneme aralığı / üst sınırı. */
    private static final long RETRY_INTERVAL_MS = 2500L;
    private static final int MAX_RETRY_ATTEMPTS = 48;

    private WebView web;
    private ProgressBar progress;
    private LinearLayout errorView;
    private TextView errorTitle;
    private TextView errorMessage;
    private TextView waitingText;
    private Button startButton;
    private Button retryButton;
    private Button fab;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lastMainFrameError = false;
    private boolean waitingForServer = false;
    private int retryAttempts = 0;

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
        web.loadUrl(BASE_URL);
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

        waitingText = new TextView(this);
        waitingText.setTextSize(12);
        waitingText.setTextColor(0xFF2DD4BF);
        waitingText.setGravity(Gravity.CENTER);
        waitingText.setPadding(0, dp(20), 0, 0);
        box.addView(waitingText);

        showError(null);
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
        return button;
    }

    // ----------------------------------------------------------------------
    // Durum geçişleri
    // ----------------------------------------------------------------------

    private void showWaiting() {
        errorTitle.setText(R.string.waiting_title);
        errorMessage.setText(R.string.waiting_message);
        waitingText.setText(getString(R.string.waiting_counter, 0));
        startButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
    }

    private void showError(String message) {
        errorTitle.setText(R.string.error_title);
        errorMessage.setText(message != null
                ? message
                : getString(R.string.error_message));
        waitingText.setText("");
        startButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
    }

    private void hideError() {
        waitingForServer = false;
        retryAttempts = 0;
        handler.removeCallbacks(retryRunnable);
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
    }

    // ----------------------------------------------------------------------
    // Yaşam döngüsü
    // ----------------------------------------------------------------------

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
