package com.sadique.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff141218;
    private static final int SURFACE = 0xff241e2a;
    private static final int ACCENT = 0xffffca86;
    private static final String TAG_URL = "stream";
    private static final String TAG_PAGE = "page";

    private static final String[][] CHANNELS = {
        {"Geo News", "Pakistan", TAG_PAGE, "https://live.geo.tv/"},
        {"ARY News", "Pakistan", TAG_PAGE, "https://live.arynews.tv/pk/"},
        {"SAMAA TV", "Pakistan", TAG_PAGE, "https://live.samaa.tv/"},
        {"Dawn News", "Pakistan", TAG_PAGE, "https://www.dawnnews.tv/watch-live"},
        {"NDTV India", "India", TAG_URL, "https://ndtvindiaelemarchana.akamaized.net/hls/live/2003679/ndtvindia/master.m3u8"},
        {"NDTV 24x7", "India", TAG_URL, "https://ndtv24x7elemarchana.akamaized.net/hls/live/2003678/ndtv24x7/master.m3u8"},
        {"India Today", "India", TAG_PAGE, "https://www.indiatoday.in/livetv"},
        {"Doordarshan Live", "India", TAG_PAGE, "https://prasarbharati.gov.in/en/live-tv/"},
        {"Tamasha", "Cricket", TAG_PAGE, "https://tamashaweb.com/"},
        {"DD Sports", "Cricket", TAG_PAGE, "https://prasarbharati.gov.in/en/live-tv/"},
        {"ICC.tv", "Cricket", TAG_PAGE, "https://www.icc-cricket.com/Icc-tv"}
    };

    private LinearLayout list;
    private FrameLayout screen;
    private TextView title;
    private TextView status;
    private VideoView video;
    private WebView web;
    private String[] selected;
    private String filter = "All";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.setBackgroundColor(BACKGROUND);
        TextView brand = label("IPTV", 26, ACCENT);
        root.addView(brand);

        LinearLayout split = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        split.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout left = column();
        LinearLayout right = column();
        if (wide) {
            split.addView(left, new LinearLayout.LayoutParams(dp(232), -1));
            right.setPadding(dp(14), 0, 0, 0);
            split.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            split.addView(right, new LinearLayout.LayoutParams(-1, 0, 1));
            split.addView(left, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        LinearLayout filters = new LinearLayout(this);
        left.addView(filters);
        for (String name : new String[]{"All", "PK", "IN", "Cricket"}) {
            filters.addView(button(name, () -> {
                filter = name;
                populate();
            }), new LinearLayout.LayoutParams(0, -2, 1));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = column();
        scroll.addView(list);
        left.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        left.addView(button("Open HTTPS stream", this::promptForStream));

        title = label("Select a channel", 23, Color.WHITE);
        right.addView(title);
        status = label("Choose Pakistan, India or Cricket.", 14, ACCENT);
        right.addView(status);
        screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        right.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        populate();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void populate() {
        list.removeAllViews();
        for (String[] channel : CHANNELS) {
            String category = channel[1];
            if (!filter.equals("All") && !filter.equals(category)
                    && !(filter.equals("PK") && category.equals("Pakistan"))
                    && !(filter.equals("IN") && category.equals("India"))
                    && !(filter.equals("PK") && channel[0].equals("Tamasha"))
                    && !(filter.equals("IN") && channel[0].equals("DD Sports"))) continue;
            Button item = button(channel[0], () -> {
                selected = channel;
                playSelected();
            });
            item.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            list.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void playSelected() {
        if (selected == null) return;
        stopPlayback();
        title.setText(selected[0]);
        if (TAG_URL.equals(selected[2])) {
            playStream(selected[3]);
        } else {
            playBroadcaster(selected[3]);
        }
    }

    private void playStream(String url) {
        status.setText("Connecting to stream…");
        video = new VideoView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        video.setOnPreparedListener(media -> {
            status.setText("Playing in IPTV");
            video.start();
        });
        video.setOnCompletionListener(media -> status.setText("Playback ended"));
        video.setOnErrorListener((media, what, extra) -> {
            status.setText("Stream unavailable. Select the channel to retry.");
            return true;
        });
        video.setVideoURI(Uri.parse(url));
    }

    private void playBroadcaster(String url) {
        status.setText("Broadcaster player · Select Play if prompted");
        web = new WebView(this);
        web.setFocusable(true);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https".equalsIgnoreCase(request.getUrl().getScheme());
            }
        });
        screen.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(url);
    }

    private void promptForStream() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://…/live.m3u8");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Open HTTPS stream")
                .setView(address).setNegativeButton("Cancel", null).setPositiveButton("Play", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = address.getText().toString().trim();
                    Uri uri = Uri.parse(value);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                        address.setError("Enter a valid HTTPS stream URL");
                        return;
                    }
                    selected = new String[]{"Custom stream", "Custom", TAG_URL, value};
                    dialog.dismiss();
                    playSelected();
                }));
        dialog.show();
    }

    private void stopPlayback() {
        if (video != null) {
            video.stopPlayback();
            video = null;
        }
        if (web != null) {
            screen.removeView(web);
            web.destroy();
            web = null;
        }
        screen.removeAllViews();
    }

    @Override protected void onStart() {
        super.onStart();
        if (selected != null) playSelected();
    }

    @Override protected void onStop() {
        stopPlayback();
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else if (selected != null) {
            stopPlayback();
            selected = null;
            title.setText("Select a channel");
            status.setText("Choose Pakistan, India or Cricket.");
        } else {
            super.onBackPressed();
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setMinimumWidth(0);
        view.setMinWidth(0);
        view.setPadding(dp(5), dp(4), dp(5), dp(4));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(0xff563a29, ACCENT));
        background.addState(new int[]{}, shape(SURFACE, BACKGROUND));
        view.setBackground(background);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable style = new GradientDrawable();
        style.setColor(color);
        style.setStroke(dp(2), stroke);
        style.setCornerRadius(dp(8));
        return style;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
