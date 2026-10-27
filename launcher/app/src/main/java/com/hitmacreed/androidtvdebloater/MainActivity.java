package com.hitmacreed.androidtvdebloater;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


/**
 * tvOS style home: a top shelf lit in the focused app's colour, a pinned dock row, and the app grid below.
 * Focus lifts and tilts the tile itself instead of drawing a ring around it.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private View ambient;
    private View hero;
    private TextView heroName;
    private TextView heroDetail;
    private View moreAppsHint;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView fatherGreeting;
    private TextView btnWifi;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateFatherGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Fonts.applyWeights(findViewById(android.R.id.content));
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        ambient = findViewById(R.id.ambient);
        hero = findViewById(R.id.hero);
        heroName = findViewById(R.id.heroName);
        heroDetail = findViewById(R.id.heroDetail);
        moreAppsHint = findViewById(R.id.moreAppsHint);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.appVersion)).setText("v" + v);
        } catch (PackageManager.NameNotFoundException ignored) {}
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // Light falls from the upper right across the shelf, like the tvOS top shelf art.
        ambientGlow.setGradientCenter(0.72f, 0.1f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.85f));
        greeting = findViewById(R.id.greeting);
        fatherGreeting = findViewById(R.id.fatherGreeting);
        updateFatherGreeting();
        btnWifi = findViewById(R.id.btnWifi);
        BootReceiver.ensureAccessibilityEnabled(this);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            // Leave ~70dp under the dock so the next row of apps peeks up (with the "All apps" hint).
            int want = Math.max(0, h - dockRow - (int) (70 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (v.getId() == R.id.btnInputs
                    || (v.getId() == R.id.btnWifi && findViewById(R.id.btnInputs).getVisibility() != View.VISIBLE))) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        for (TextView b : new TextView[]{(TextView) btnInputs, btnWifi, (TextView) btnSettings}) {
            b.setTag(b.getText());
            b.setOnFocusChangeListener((v, focused) -> showLabel((TextView) v, focused));
            showLabel(b, false);
        }
        // Animate the buttons sliding apart as the focused one grows to show its name.
        for (int id : new int[]{R.id.topButtons, R.id.rightBlock}) {
            LayoutTransition t = new LayoutTransition();
            t.enableTransitionType(LayoutTransition.CHANGING);
            t.setDuration(220);
            ((ViewGroup) findViewById(id)).setLayoutTransition(t);
        }

        // Focus on the top bar: keep the "All apps" hint under the dock.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) updateMoreAppsHint(false);
        });
    }

    /**
     * Builds the tiles below the first screen one at a time while the app is idle. Each tile costs
     * ~8 ms to inflate on the TCL TV, so creating a whole row on the first Down press stuttered.
     */
    private void prewarmTiles() {
        RecyclerView.RecycledViewPool pool = grid.getRecycledViewPool();
        int want = adapter.getItemCount();
        pool.setMaxRecycledViews(0, want);
        Looper.myQueue().addIdleHandler(() -> {
            if (isDestroyed() || grid.getChildCount() + pool.getRecycledViewCount(0) >= want) return false;
            pool.putRecycledView(adapter.createViewHolder(grid, 0));
            return true;
        });
    }

    /**
     * Fresh-screen setup runs here, not in onResume: see-through overlays such as the Google TV
     * quick-settings panel only pause us, and replaying the intro on their close looked like a relaunch.
     */
    @Override
    protected void onStart() {
        super.onStart();
        heroShown = false;
        hero.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        prewarmTiles();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        BootReceiver.ensureAccessibilityEnabled(this);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cheap and may have changed under an overlay (e.g. Wi-Fi switched from the quick-settings panel).
        updateFatherGreeting();
        updateWifiLabel();
        findViewById(R.id.btnInputs).setVisibility(hasInputs() ? View.VISIBLE : View.GONE);
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (grid != null) {
            grid.post(() -> focusTile(lastFocused));
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- focus ---------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        updateMoreAppsHint(position >= COLUMNS);
        int from = lastFocused;
        lastFocused = position;
        tiltToward(card, from, position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
            moreAppsHint.setY(r.bottom - dy + 16 * density);
        } else {
            hideHero();
            // Scroll so the lifted tile and its shadow stay on screen.
            int pad = (int) (12 * density);
            Rect want = new Rect(0, -pad * 2, item.getWidth(), item.getHeight() + pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    /** Leans the newly focused tile toward the direction the remote moved, then lets it settle. */
    private void tiltToward(View card, int from, int to) {
        if (from == to) return;
        int dx = 0, dy = 0;
        if (from / COLUMNS == to / COLUMNS) dx = Integer.signum(to - from);
        else dy = Integer.signum(to / COLUMNS - from / COLUMNS);
        card.setRotationY(dx * 8f);
        card.setRotationX(-dy * 6f);
        card.animate().rotationX(0f).rotationY(0f).setDuration(360)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        String detail = adapter.detailAt(position);
        int color = adapter.ambientColorAt(position);
        // The shelf hangs from its bottom edge, so a status line can come and go without moving the name.
        float lift = -(root.getHeight() - dockTopInRoot + 34 * density);
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            setHeroText(label, detail);
            hero.setTranslationY(lift);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                setHeroText(label, detail);
                hero.setTranslationY(lift);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setTranslationY(lift);
        }
    }

    private void setHeroText(String label, String detail) {
        heroName.setText(label);
        heroDetail.setText(detail);
        heroDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x8C000000 | c, 0x38000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        hero.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** NLRC brand is set statically in layout; this method is kept for lifecycle compatibility. */
    private void updateGreeting() {
        // No-op: NLRC brand name is declared in activity_main.xml — no runtime greeting needed.
    }

    /** Greeting based on time of day: morning 5-12, afternoon 12-17, evening 17-21, night otherwise. */
    private void updateFatherGreeting() {
        if (fatherGreeting == null) return;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int wish;
        if (hour >= 5 && hour < 12)       wish = R.string.greeting_morning;
        else if (hour >= 12 && hour < 17) wish = R.string.greeting_afternoon;
        else if (hour >= 17 && hour < 21) wish = R.string.greeting_evening;
        else                              wish = R.string.greeting_night;
        fatherGreeting.setText(wish);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean ethernet = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                ethernet = true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setTag(text);
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi, 0, 0, 0);
        showLabel(btnWifi, btnWifi.isFocused());
    }

    /** Top buttons are icon-only circles; the focused one shows its name (kept in the view's tag). */
    private void showLabel(TextView b, boolean show) {
        CharSequence label = (CharSequence) b.getTag();
        b.setContentDescription(label);
        b.setText(show ? label : "");
        b.setCompoundDrawablePadding(show ? (int) (10 * density) : 0);
        b.setPaddingRelative((int) ((show ? 16 : 14) * density), 0, (int) ((show ? 20 : 14) * density), 0);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Shows the "All apps" hint while focus is on the dock or top bar and more apps sit below. */
    private void updateMoreAppsHint(boolean inGrid) {
        boolean show = !inGrid && adapter.getItemCount() > COLUMNS;
        if (show) moreAppsHint.setVisibility(View.VISIBLE);
        moreAppsHint.animate().alpha(show ? 1f : 0f).setDuration(200)
                .withEndAction(() -> { if (!show) moreAppsHint.setVisibility(View.GONE); }).start();
    }

    /** True when the Inputs menu would list something (HDMI/AV passthrough or a Live TV app). */
    private boolean hasInputs() {
        if (!HdmiHelper.passthroughInputs(this).isEmpty()) return true;
        return !liveTvPkg.isEmpty() && getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg) != null;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        Fonts.applyWeights(panel);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // At least as wide as the Inputs pill, hanging right below it; widened to fit the rows further down.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            for (TvInputInfo info : HdmiHelper.passthroughInputs(this)) {
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = HdmiHelper.inputState(tim, id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Wide enough for the longest row ("HDMI 2  ● Connected"): the round Inputs button is too narrow to set it.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth() + (int) (16 * density)));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        Fonts.applyWeights(row);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
