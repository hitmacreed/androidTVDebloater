package com.hitmacreed.androidtvdebloater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/**
 * "From your phone": shows a QR code and a one-time code, and receives one background photo over
 * the home network (see {@link WallpaperServer}). NLRC Setup opens this screen too, passing its
 * own code in {@link #EXTRA_CODE}, and uploads the photo from the computer.
 */
public class PhoneUploadActivity extends Activity implements WallpaperServer.Listener {

    static final String EXTRA_CODE = "code";
    private static final long TIMEOUT_MS = 5 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::finish;
    private WallpaperServer server;
    private TextView status;
    private String code;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_upload);
        Fonts.applyWeights(findViewById(android.R.id.content));
        status = findViewById(R.id.status);
        code = codeFrom(getIntent());
    }

    /** singleTop: NLRC Setup may open the screen again with a new code while it is showing. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        code = codeFrom(intent);
    }

    private static String codeFrom(Intent intent) {
        String given = intent.getStringExtra(EXTRA_CODE);
        return given != null && given.matches("\\d{6}")
                ? given : String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    /**
     * Listens only while in front. Resume/pause rather than start/stop: a screen being replaced is
     * paused before the next one resumes, but stopped only after, so the port would still be taken.
     */
    @Override
    protected void onResume() {
        super.onResume();
        status.setText(R.string.bg_phone_waiting);
        String ip = localIp(this);
        if (ip == null) {
            status.setText(R.string.bg_phone_no_network);
            return;
        }
        String url = "http://" + ip + ":" + WallpaperServer.PORT + "/";
        ((TextView) findViewById(R.id.address)).setText(url);
        ((TextView) findViewById(R.id.code)).setText(getString(R.string.bg_phone_code, code));
        ((ImageView) findViewById(R.id.qr)).setImageBitmap(qr(url + "?c=" + code));
        server = new WallpaperServer(code, page(), this);
        try {
            server.start();
        } catch (IOException e) {
            server = null;
            status.setText(R.string.bg_phone_busy);
            return;
        }
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(timeout);
        if (server != null) server.stop();
        server = null;
    }

    @Override
    public void onReceiving() {
        runOnUiThread(() -> status.setText(R.string.bg_phone_receiving));
    }

    @Override
    public boolean onImage(byte[] data) {
        boolean saved = WallpaperStore.save(this, data);
        runOnUiThread(() -> {
            if (saved) {
                Toast.makeText(this, R.string.bg_set, Toast.LENGTH_SHORT).show();
                // Opened over adb by NLRC Setup, this screen may sit on top of any app: show the
                // home screen so the new background is seen.
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            } else {
                status.setText(R.string.bg_phone_bad_image);
            }
        });
        return saved;
    }

    @Override
    public void onRejected() {
        runOnUiThread(this::finish);
    }

    /** The TV's IPv4 address on the active network (Wi-Fi or Ethernet). */
    private static String localIp(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()) {
                return a.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private static Bitmap qr(String text) {
        int size = 512;
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return null;
        }
    }

    /** assets/upload.html with the TV's language filled in. */
    private String page() {
        String html;
        try (InputStream in = getAssets().open("upload.html")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            html = buf.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
        int[] keys = {R.string.web_title, R.string.web_code, R.string.web_pick, R.string.web_send,
                R.string.web_sending, R.string.web_done, R.string.web_wrong_code, R.string.web_too_big,
                R.string.web_bad_image, R.string.web_failed};
        for (int id : keys) {
            String name = getResources().getResourceEntryName(id);
            String text = getString(id);
            html = html.replace("{{js:" + name + "}}", jsString(text))
                    .replace("{{" + name + "}}", TextUtils.htmlEncode(text));
        }
        return html.replace("{{lang}}", Locale.getDefault().getLanguage());
    }

    /** Text for inside a "..." JS literal in a script block: no quote, backslash or tag can break out. */
    private static String jsString(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\' || c == '<' || c == '>' || c == '&' || c < 0x20) {
                b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
