package br.com.cardapiofc;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Map;

/** Player nativo para os streams encontrados na lista autorizada do usuário. */
public final class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long LIVE_EDGE_FALLBACK_MS = 5000;
    private static final long SEEK_OFFSET_MS = 15_000;

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout loadingOverlay;
    private LinearLayout centerControls;
    private FrameLayout centerActionButton;
    private CenterActionGlyph centerActionGlyph;
    private FrameLayout seekBackButton;
    private FrameLayout seekForwardButton;
    private Handler handler;
    private boolean controllerVisible;
    private int retryAttempts;
    private boolean liveEdgeFallbackAttempted;
    private boolean alternateFormatTried;
    private String currentStreamUrl = "";
    private String lastPlaybackFailure = "";
    private TextView backButton;
    private TextView liveButton;
    private TextView channelLabel;
    private OrientationEventListener orientationListener;
    private int sensorRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                int requested = sensorRequestedOrientation;
                if (orientation >= 60 && orientation <= 120) requested = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                else if (orientation >= 240 && orientation <= 300) requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                if (requested != sensorRequestedOrientation) {
                    sensorRequestedOrientation = requested;
                    setRequestedOrientation(requested);
                }
            }
        };
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        handler = new Handler(getMainLooper());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowPreviousButton(false);
        playerView.setShowRewindButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowNextButton(false);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setKeepScreenOn(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        // Esconde resíduos nativos
        hideNativeControllerResiduals();

        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setClickable(false);
        loadingOverlay.setFocusable(false);
        ProgressBar loadingSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        loadingSpinner.setIndeterminate(true);
        loadingSpinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        loadingOverlay.addView(loadingSpinner, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        loadingOverlay.setVisibility(View.GONE);
        loadingOverlay.setElevation(dp(3));
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));

        // Controles centrais: -15 | Play/Pause | +15
        centerControls = new LinearLayout(this);
        centerControls.setOrientation(LinearLayout.HORIZONTAL);
        centerControls.setGravity(Gravity.CENTER_VERTICAL);
        centerControls.setVisibility(View.GONE);

        seekBackButton = createSeekButton("-15", v -> seekBy(-SEEK_OFFSET_MS));
        centerActionButton = createCenterPlayButton();
        seekForwardButton = createSeekButton("+15", v -> seekBy(SEEK_OFFSET_MS));

        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(52), dp(48));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(56), dp(50));
        playLp.leftMargin = dp(14);
        playLp.rightMargin = dp(14);

        centerControls.addView(seekBackButton, sideLp);
        centerControls.addView(centerActionButton, playLp);
        centerControls.addView(seekForwardButton, sideLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(centerControls, centerLp);

        backButton = overlayButton("‹", 32);
        backButton.setContentDescription("Voltar para o Cardápio FC");
        backButton.setIncludeFontPadding(false);
        backButton.setGravity(Gravity.CENTER);
        backButton.setPadding(0, 0, 0, dp(4));
        backButton.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(52), dp(48), Gravity.TOP | Gravity.START);
        backLp.leftMargin = dp(18);
        backLp.topMargin = dp(14);
        root.addView(backButton, backLp);

        liveButton = overlayButton("AO VIVO", 12);
        liveButton.setContentDescription("Ir para o segmento mais recente");
        liveButton.setOnClickListener(view -> jumpToLiveEdge());
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(dp(112), dp(42), Gravity.BOTTOM | Gravity.END);
        liveLp.rightMargin = dp(22);
        liveLp.bottomMargin = dp(100);
        root.addView(liveButton, liveLp);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            channelLabel = new TextView(this);
            channelLabel.setText(title);
            channelLabel.setTextColor(Color.WHITE);
            channelLabel.setTextSize(15);
            channelLabel.setGravity(Gravity.CENTER_VERTICAL);
            channelLabel.setIncludeFontPadding(false);
            channelLabel.setSingleLine(true);
            channelLabel.setEllipsize(TextUtils.TruncateAt.END);
            channelLabel.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(124));
            channelLabel.setPadding(dp(16), 0, dp(16), 0);
            channelLabel.setBackground(new ChannelBadgeDrawable(dp(13), dp(9), dp(1)));
            channelLabel.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, 48, Gravity.TOP | Gravity.START);
            lp.leftMargin = dp(84);
            lp.topMargin = dp(14);
            root.addView(channelLabel, lp);
        }

        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        setOverlayControlsVisible(visibility == View.VISIBLE));
        setContentView(root);
    }

    private void hideNativeControllerResiduals() {
        // Remove buffering nativo
        View nativeBuffering = playerView.findViewById(androidx.media3.ui.R.id.exo_buffering);
        if (nativeBuffering != null) {
            nativeBuffering.setVisibility(View.GONE);
            if (nativeBuffering.getParent() instanceof ViewGroup) {
                ((ViewGroup) nativeBuffering.getParent()).removeView(nativeBuffering);
            }
        }
        // Esconde play/pause nativo
        View nativePlayPause = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause);
        if (nativePlayPause != null) {
            nativePlayPause.setVisibility(View.GONE);
            nativePlayPause.setAlpha(0f);
            if (nativePlayPause.getParent() instanceof ViewGroup) {
                // só esconde, não remove, pra não quebrar o controller
            }
        }
        // Outros possíveis resíduos
        int[] extraIds = {
                androidx.media3.ui.R.id.exo_ffwd,
                androidx.media3.ui.R.id.exo_rew,
                androidx.media3.ui.R.id.exo_prev,
                androidx.media3.ui.R.id.exo_next
        };
        for (int id : extraIds) {
            View v = playerView.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    private FrameLayout createCenterPlayButton() {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(roundBackground(0xB8000000, 0x66FFFFFF, dp(11)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(4));
        centerActionGlyph = new CenterActionGlyph();
        button.addView(centerActionGlyph, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        button.setOnClickListener(view -> togglePlayback());
        return button;
    }

    private FrameLayout createSeekButton(String label, View.OnClickListener listener) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(roundBackground(0xB8000000, 0x66FFFFFF, dp(11)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(4));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        button.addView(text, new FrameLayout.LayoutParams(-1, -1));
        button.setOnClickListener(listener);
        button.setContentDescription(label.equals("-15") ? "Voltar 15 segundos" : "Avançar 15 segundos");
        return button;
    }

    private void setOverlayControlsVisible(boolean visible) {
        controllerVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (backButton != null) backButton.setVisibility(visibility);
        if (liveButton != null) liveButton.setVisibility(visibility);
        if (channelLabel != null) channelLabel.setVisibility(visibility);
        updateLoadingIndicator();
    }

    private void updateLoadingIndicator() {
        if (loadingOverlay == null) return;

        boolean loading = player != null
                && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING;

        // Spinner sempre aparece durante buffering, independente do controller
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);

        if (centerControls != null) {
            // Controles centrais só quando o controller está visível e não está carregando
            centerControls.setVisibility(controllerVisible && !loading ? View.VISIBLE : View.GONE);

            boolean pauseIcon = player != null
                    && player.getPlayWhenReady()
                    && player.getPlaybackState() != Player.STATE_IDLE
                    && player.getPlaybackState() != Player.STATE_ENDED;
            if (centerActionGlyph != null) {
                centerActionGlyph.setPlaying(pauseIcon);
            }
            if (centerActionButton != null) {
                centerActionButton.setContentDescription(pauseIcon ? "Pausar" : "Reproduzir");
            }
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED) {
            player.pause();
        } else {
            if (handler != null) handler.removeCallbacksAndMessages(null);
            retryAttempts = 0;
            liveEdgeFallbackAttempted = false;
            playerView.setCustomErrorMessage(null);
            if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekToDefaultPosition();
                player.prepare();
            }
            player.setPlayWhenReady(true);
            player.play();
        }
        updateLoadingIndicator();
    }

    private void seekBy(long offsetMs) {
        if (player == null) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        long newPosition = position + offsetMs;

        if (duration != C.TIME_UNSET && duration > 0) {
            newPosition = Math.max(0, Math.min(newPosition, duration));
        } else {
            // Live / window desconhecida – deixa o ExoPlayer resolver os limites
            newPosition = Math.max(0, newPosition);
        }
        player.seekTo(newPosition);
    }

    private TextView overlayButton(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setBackground(roundBackground(0xB8000000, 0x66FFFFFF, dp(11)));
        view.setClickable(true);
        view.setFocusable(true);
        view.setElevation(dp(4));
        return view;
    }

    private GradientDrawable roundBackground(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private final class ChannelBadgeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float radius, cut, strokeWidth;

        ChannelBadgeDrawable(float radius, float cut, float strokeWidth) {
            this.radius = radius;
            this.cut = cut;
            this.strokeWidth = strokeWidth;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float left = b.left, top = b.top, right = b.right, bottom = b.bottom;
            float r = Math.min(radius, Math.min((right - left) / 3f, (bottom - top) / 3f));
            float c = Math.min(cut, Math.min((right - left) / 5f, (bottom - top) / 3f));
            path.reset();
            path.moveTo(left + c + r, top);
            path.lineTo(right - r, top);
            path.quadTo(right, top, right, top + r);
            path.lineTo(right, bottom - r);
            path.quadTo(right, bottom, right - r, bottom);
            path.lineTo(left + r, bottom);
            path.quadTo(left, bottom, left, bottom - r);
            path.lineTo(left, top + c + r);
            path.lineTo(left + c + r, top);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xB8000000);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setColor(0x35FFFFFF);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private final class CenterActionGlyph extends View {
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean playing;

        CenterActionGlyph() {
            super(PlayerActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setPlaying(boolean playing) {
            if (this.playing != playing) {
                this.playing = playing;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            iconPaint.setColor(Color.WHITE);
            iconPaint.setStyle(Paint.Style.FILL);
            float width = getWidth();
            float height = getHeight();
            if (playing) {
                float barWidth = dp(5);
                float gap = dp(6);
                float barHeight = dp(22);
                float left = (width - (barWidth * 2 + gap)) / 2f;
                float top = (height - barHeight) / 2f;
                canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, dp(1), dp(1), iconPaint);
                float rightBar = left + barWidth + gap;
                canvas.drawRoundRect(rightBar, top, rightBar + barWidth, top + barHeight, dp(1), dp(1), iconPaint);
            } else {
                Path triangle = new Path();
                triangle.moveTo(width * 0.36f, height * 0.22f);
                triangle.lineTo(width * 0.36f, height * 0.78f);
                triangle.lineTo(width * 0.79f, height * 0.50f);
                triangle.close();
                canvas.drawPath(triangle, iconPaint);
            }
        }
    }

    private void jumpToLiveEdge() {
        if (player == null) return;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        retryAttempts = 0;
        liveEdgeFallbackAttempted = false;
        playerView.setCustomErrorMessage("");
        player.seekToDefaultPosition();
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        player.setPlayWhenReady(true);
        player.play();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        startPlayer();
    }

    @Override
    protected void onStop() {
        if (orientationListener != null) orientationListener.disable();
        releasePlayer();
        super.onStop();
    }

    private void startPlayer() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            finish();
            return;
        }
        currentStreamUrl = url.trim();
        alternateFormatTried = false;

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                .setDefaultRequestProperties(playbackHeaders(url))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000);

        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(httpFactory);
        mediaFactory.setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(5));

        // Buffers mais agressivos para live (menor latência, ainda estável)
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
        .setBufferDurationsMs(
                22000,   // minBuffer
                55000,   // maxBuffer
                2500,    // bufferForPlayback
                5000     // bufferForPlaybackAfterRebuffer
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(15000, true)
        .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaFactory)
                .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);

        retryAttempts = 0;
        liveEdgeFallbackAttempted = false;
        playerView.setCustomErrorMessage(null);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updateLoadingIndicator();
                if (playbackState == Player.STATE_READY) {
                    if (handler != null) handler.removeCallbacksAndMessages(null);
                    playerView.setCustomErrorMessage(null);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateLoadingIndicator();
                if (isPlaying) {
                    if (handler != null) handler.removeCallbacksAndMessages(null);
                    retryAttempts = 0;
                    liveEdgeFallbackAttempted = false;
                    lastPlaybackFailure = "";
                    playerView.setCustomErrorMessage(null);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updateLoadingIndicator();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                updateLoadingIndicator();
                ExoPlayer failedPlayer = player;
                if (failedPlayer == null) return;

                lastPlaybackFailure = safePlaybackFailure(error);

                // Em erros HTTP 4xx tenta o formato alternativo mais cedo
                boolean isHttpClientError = false;
                Throwable cause = error;
                while (cause != null) {
                    if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                        if (code >= 400 && code < 500) {
                            isHttpClientError = true;
                        }
                        break;
                    }
                    cause = cause.getCause();
                }

                if (isHttpClientError && !alternateFormatTried) {
                    String alternateUrl = alternateStreamUrl(currentStreamUrl);
                    if (!alternateUrl.isEmpty()) {
                        alternateFormatTried = true;
                        currentStreamUrl = alternateUrl;
                        retryAttempts = 0;
                        liveEdgeFallbackAttempted = false;
                        playerView.setCustomErrorMessage("");
                        handler.postDelayed(() -> setStreamAndRetry(failedPlayer, alternateUrl), 400);
                        return;
                    }
                }

                if (retryAttempts >= MAX_RETRY_ATTEMPTS) {
                    String alternateUrl = alternateStreamUrl(currentStreamUrl);
                    if (!alternateFormatTried && !alternateUrl.isEmpty()) {
                        alternateFormatTried = true;
                        currentStreamUrl = alternateUrl;
                        retryAttempts = 0;
                        liveEdgeFallbackAttempted = false;
                        playerView.setCustomErrorMessage("");
                        handler.postDelayed(() -> setStreamAndRetry(failedPlayer, alternateUrl), 500);
                        return;
                    }
                    if (!liveEdgeFallbackAttempted) {
                        liveEdgeFallbackAttempted = true;
                        playerView.setCustomErrorMessage("");
                        handler.postDelayed(() -> retryPlayback(failedPlayer, true), 500);
                        return;
                    }
                    playerView.setCustomErrorMessage(
                            "Não foi possível reproduzir este canal (" + lastPlaybackFailure + "). Toque em AO VIVO para tentar novamente.");
                    return;
                }

                int attempt = ++retryAttempts;
                playerView.setCustomErrorMessage("");
                handler.postDelayed(() -> retryPlayback(failedPlayer, false), 600L + attempt * 500L);
            }
        });

        player.setMediaItem(liveMediaItem(currentStreamUrl));
        player.setPlayWhenReady(true);
        player.prepare();
        updateLoadingIndicator();
    }

    private MediaItem liveMediaItem(String url) {
        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("format=m3u8")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.contains(".ts") || lower.contains("/live/")) {
            itemBuilder.setMimeType(MimeTypes.VIDEO_MP2T);
        } else if (lower.contains(".mp4")) {
            itemBuilder.setMimeType(MimeTypes.VIDEO_MP4);
        }
        return itemBuilder.setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(7000)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build()
        ).build();
    }

    private String alternateStreamUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getEncodedPath();
            if (path == null) return "";
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            String suffix, replacement;
            if (lower.endsWith(".m3u8")) {
                suffix = ".m3u8";
                replacement = ".ts";
            } else if (lower.endsWith(".ts")) {
                suffix = ".ts";
                replacement = ".m3u8";
            } else {
                return "";
            }
            String alternatePath = path.substring(0, path.length() - suffix.length()) + replacement;
            return uri.buildUpon().encodedPath(alternatePath).build().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void setStreamAndRetry(ExoPlayer target, String url) {
        if (player != target) return;
        target.setMediaItem(liveMediaItem(url));
        target.setPlayWhenReady(true);
        target.prepare();
        target.play();
    }

    private String safePlaybackFailure(PlaybackException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return "HTTP " + ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            }
            cause = cause.getCause();
        }
        return error.getErrorCodeName();
    }

    private Map<String, String> playbackHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Icy-MetaData", "1");
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (uri.getScheme() != null && host != null && !host.isEmpty()) {
                String authority = host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
                headers.put("Referer", uri.getScheme() + "://" + authority + "/");
            }
        } catch (Throwable ignored) {
        }
        return headers;
    }

    private void retryPlayback(ExoPlayer failedPlayer, boolean forceLiveEdge) {
        if (player != failedPlayer) return;
        if (forceLiveEdge || failedPlayer.getTotalBufferedDuration() <= LIVE_EDGE_FALLBACK_MS) {
            liveEdgeFallbackAttempted = true;
            failedPlayer.seekToDefaultPosition();
        }
        failedPlayer.prepare();
        failedPlayer.setPlayWhenReady(true);
        failedPlayer.play();
    }

    private void releasePlayer() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
        if (centerControls != null) centerControls.setVisibility(View.GONE);
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
    }
}