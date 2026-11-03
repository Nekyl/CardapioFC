package br.com.cardapiofc;

import android.app.Activity;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

/** Player nativo para os streams encontrados na lista autorizada do usuário. */
public final class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    private static final int RETRY_DELAY_MS = 1800;

    private ExoPlayer player;
    private PlayerView playerView;
    private Handler handler;
    private boolean retried;
    private TextView backButton;
    private TextView liveButton;
    private TextView channelLabel;
    private OrientationEventListener orientationListener;
    private int sensorRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        handler = new Handler(getMainLooper());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        backButton = overlayButton("‹", 32);
        backButton.setContentDescription("Voltar para o Cardápio FC");
        backButton.setIncludeFontPadding(false);
        backButton.setGravity(Gravity.CENTER);
        backButton.setPadding(0, 0, 0, dp(4));
        backButton.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(52), dp(48), Gravity.TOP | Gravity.START);
        backLp.leftMargin = dp(18); backLp.topMargin = dp(14);
        root.addView(backButton, backLp);

        liveButton = overlayButton("AO VIVO", 12);
        liveButton.setContentDescription("Ir para o segmento mais recente");
        liveButton.setOnClickListener(view -> jumpToLiveEdge());
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(dp(112), dp(42), Gravity.BOTTOM | Gravity.END);
        liveLp.rightMargin = dp(22); liveLp.bottomMargin = dp(100);
        root.addView(liveButton, liveLp);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            channelLabel = new TextView(this);
            channelLabel.setText(title); channelLabel.setTextColor(Color.WHITE); channelLabel.setTextSize(15); channelLabel.setGravity(Gravity.CENTER_VERTICAL);
            channelLabel.setIncludeFontPadding(false);
            channelLabel.setSingleLine(true);
            channelLabel.setEllipsize(TextUtils.TruncateAt.END);
            channelLabel.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(124));
            channelLabel.setPadding(dp(16), 0, dp(16), 0);
            channelLabel.setBackground(new ChannelBadgeDrawable(dp(13), dp(9), dp(1)));
            channelLabel.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, 48, Gravity.TOP | Gravity.START);
            lp.leftMargin = dp(84); lp.topMargin = dp(14); root.addView(channelLabel, lp);
        }
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)
                visibility -> setOverlayControlsVisible(visibility == View.VISIBLE));
        setContentView(root);
    }

    private void setOverlayControlsVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (backButton != null) backButton.setVisibility(visibility);
        if (liveButton != null) liveButton.setVisibility(visibility);
        if (channelLabel != null) channelLabel.setVisibility(visibility);
    }

    private TextView overlayButton(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextColor(Color.WHITE); view.setTextSize(size); view.setGravity(Gravity.CENTER); view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setBackground(roundBackground(0xB8000000, 0x66FFFFFF, dp(11)));
        view.setClickable(true); view.setFocusable(true); view.setElevation(dp(4));
        return view;
    }

    private GradientDrawable roundBackground(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(radius); drawable.setStroke(dp(1), stroke); return drawable;
    }

    private final class ChannelBadgeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float radius, cut, strokeWidth;
        ChannelBadgeDrawable(float radius, float cut, float strokeWidth) { this.radius = radius; this.cut = cut; this.strokeWidth = strokeWidth; }
        @Override public void draw(Canvas canvas) {
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
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void jumpToLiveEdge() {
        if (player == null) return;
        player.seekToDefaultPosition();
        player.setPlayWhenReady(true);
        player.play();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
        startPlayer();
    }
    @Override protected void onStop() {
        if (orientationListener != null) orientationListener.disable();
        releasePlayer(); super.onStop();
    }

    private void startPlayer() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) { finish(); return; }
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("CardapioFC/4.1 Android")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000);
        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(httpFactory);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(mediaFactory).build();
        playerView.setPlayer(player);

        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8") || lower.contains("format=m3u8")) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".ts") || lower.contains("/live/")) itemBuilder.setMimeType(MimeTypes.VIDEO_MP2T);
        else if (lower.contains(".mp4")) itemBuilder.setMimeType(MimeTypes.VIDEO_MP4);
        itemBuilder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.03f).build());

        retried = false;
        player.setMediaItem(itemBuilder.build()); player.setPlayWhenReady(true); player.prepare();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                playerView.setCustomErrorMessage("Não foi possível reproduzir este canal.");
                if (!retried && player != null) {
                    retried = true;
                    handler.postDelayed(() -> { if (player != null) { player.prepare(); player.play(); } }, RETRY_DELAY_MS);
                }
            }
        });
    }

    private void releasePlayer() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (playerView != null) playerView.setPlayer(null);
    }
}
