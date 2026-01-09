package com.tv.play;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String MODE_FILE = "mode.txt";
    private static final String BOOT_FILE = "boot_video.txt";
    private static final String ONLINE_FILE = "online_video.txt";
    private static final int TIMEOUT_WIFI_CHECK_MS = 20000;
    private static final int COUNTDOWN_INTERVAL = 1000;

    private enum Mode { OFFLINE, ONLINE }
    private Mode currentMode;

    private WebView webView;
    private TextView countdownText;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int countdownValue = TIMEOUT_WIFI_CHECK_MS / 1000;

    private PlayerView playerView;
    private ExoPlayer player;
    private Uri currentUri;

    private FrameLayout mainLayout;
    private SwitchCompat modeSwitch;
    private Button configButton;
    private Runnable hideSwitchRunnable;

    private boolean switchVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        currentMode = readSavedMode();
        hideSwitchRunnable = () -> {
            if (modeSwitch != null) modeSwitch.setVisibility(View.GONE);
            if (configButton != null) configButton.setVisibility(View.GONE);
            switchVisible = false;
        };

        if (currentMode == Mode.ONLINE) initOnlineMode();
        else initOfflineMode();
    }

    private void initOnlineMode() {
        mainLayout = new FrameLayout(this);
        webView = new WebView(this);
        countdownText = new TextView(this);

        countdownText.setText("Verificando conexão... 20s");
        countdownText.setTextSize(20);
        countdownText.setTextColor(0xFFFFFFFF);
        countdownText.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        countdownText.setPadding(0, 100, 0, 0);

        mainLayout.addView(webView);
        mainLayout.addView(countdownText);

        setupSwitch(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                handler.postDelayed(() -> captureVideoUrl(), 10000);
                handler.postDelayed(() -> captureVideoUrl(), 600000); // verifica a cada 10min
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        setContentView(mainLayout);
        iniciarContagemEVerificacao();
    }

    private void initOfflineMode() {
        mainLayout = new FrameLayout(this);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        mainLayout.addView(playerView);
        setContentView(mainLayout);

        setupSwitch(false);
        String path = readFile(BOOT_FILE);
        if (path != null && new File(path).exists()) playVideo(Uri.fromFile(new File(path)));
        else pickVideo();
    }

    private void setupSwitch(boolean isOnline) {
        modeSwitch = new SwitchCompat(this);
        modeSwitch.setChecked(isOnline);
        modeSwitch.setText(isOnline ? "ON" : "OFF");
        modeSwitch.setTextColor(isOnline ? 0xFF00FF00 : 0xFFFF0000);
        modeSwitch.setVisibility(View.GONE);
        modeSwitch.setFocusable(true);
        modeSwitch.setFocusableInTouchMode(true);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setMargins(20, 50, 150, 0);
        modeSwitch.setLayoutParams(params);
        mainLayout.addView(modeSwitch);

        configButton = new Button(this);
        configButton.setText("CONFIG");
        configButton.setVisibility(View.GONE);
        configButton.setFocusable(true);
        configButton.setFocusableInTouchMode(true);

        FrameLayout.LayoutParams configParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        configParams.gravity = Gravity.TOP | Gravity.END;
        configParams.setMargins(20, 120, 20, 0);
        configButton.setLayoutParams(configParams);
        mainLayout.addView(configButton);

        configButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_SETTINGS)));

        modeSwitch.setOnClickListener(v -> {
            Mode newMode = isOnline ? Mode.OFFLINE : Mode.ONLINE;
            saveMode(newMode);
            if (player != null) player.pause();
            recreate();
        });
    }

    private void captureVideoUrl() {
        webView.evaluateJavascript("(function(){var v=document.querySelector('video');return v?v.src:'';})()",
                value -> {
                    String url = value.replace("\"", "");
                    if (!url.isEmpty()) checkAndDownloadMedia(url);
                    else playLocalOnlineVideo();
                });
    }

    private void checkAndDownloadMedia(String url) {
        String savedUrl = readFile(ONLINE_FILE);
        File output = new File(getFilesDir(), "online_cache.mp4");
        if (!url.equals(savedUrl) || !output.exists()) {
            downloadFile(url, output);
            writeFile(ONLINE_FILE, url);
        }
        playVideo(Uri.fromFile(output));
    }

    private void downloadFile(String fileURL, File destination) {
        new Thread(() -> {
            try {
                URL url = new URL(fileURL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                InputStream input = connection.getInputStream();
                FileOutputStream output = new FileOutputStream(destination);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) output.write(buffer, 0, bytesRead);
                output.close();
                input.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void playLocalOnlineVideo() {
        File file = new File(getFilesDir(), "online_cache.mp4");
        if (file.exists()) playVideo(Uri.fromFile(file));
    }

    private void playVideo(Uri uri) {
        try {
            if (player != null) player.release();
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(uri));
            player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao iniciar vídeo", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                currentUri = uri;
                playVideo(uri);
                new AlertDialog.Builder(this)
                        .setTitle("Salvar vídeo como boot?")
                        .setPositiveButton("Sim", (d, w) -> writeFile(BOOT_FILE, FileUtils.getPath(this, uri)))
                        .setNegativeButton("Não", null)
                        .show();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!switchVisible) {
                if (modeSwitch != null) modeSwitch.setVisibility(View.VISIBLE);
                if (configButton != null) configButton.setVisibility(View.VISIBLE);

                if (modeSwitch != null) modeSwitch.requestFocus();

                switchVisible = true;
                handler.removeCallbacks(hideSwitchRunnable);
                handler.postDelayed(hideSwitchRunnable, 5000);

                if (currentMode == Mode.OFFLINE) {
                    new AlertDialog.Builder(this)
                            .setTitle("Deseja mudar o vídeo de boot?")
                            .setPositiveButton("Sim", (dialog, which) -> pickVideo())
                            .setNegativeButton("Não", null)
                            .show();
                }
            } else {
                Mode newMode = (currentMode == Mode.ONLINE) ? Mode.OFFLINE : Mode.ONLINE;
                saveMode(newMode);
                if (player != null) player.pause();
                recreate();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void iniciarContagemEVerificacao() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                countdownValue--;
                countdownText.setText("Verificando conexão... " + countdownValue + "s");
                if (isNetworkConnected()) {
                    countdownText.setText("");
                    webView.loadUrl("https://tvdsigner.com.br");
                } else if (countdownValue > 0) {
                    handler.postDelayed(this, COUNTDOWN_INTERVAL);
                } else {
                    countdownText.setText("Abrindo configurações de Wi-Fi...");
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }
            }
        });
    }

    private Mode readSavedMode() {
        String content = readFile(MODE_FILE);
        return "ONLINE".equals(content) ? Mode.ONLINE : Mode.OFFLINE;
    }

    private void saveMode(Mode mode) {
        writeFile(MODE_FILE, mode.name());
    }

    private void writeFile(String name, String content) {
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), name))) {
            writer.write(content);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String readFile(String name) {
        try (BufferedReader br = new BufferedReader(new FileReader(new File(getFilesDir(), name)))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
