package com.hitmacreed.androidtvdebloater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot HTTP receiver for a background photo, open only while the "From your phone" screen
 * is showing. GET / serves the upload page; POST /upload takes the photo as the raw request body
 * with the screen's code in an X-Code header. It closes after one good photo or too many wrong codes.
 */
final class WallpaperServer {

    static final int PORT = 8765;
    private static final int MAX_WRONG_CODES = 5;
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_LINE = 8192;

    interface Listener {
        void onReceiving();
        /** Called on the server thread; returns whether the photo was saved. */
        boolean onImage(byte[] data);
        void onRejected();
    }

    private final String code;
    private final String page;
    private final Listener listener;
    private ServerSocket socket;
    private volatile boolean done;
    private int wrongCodes;

    WallpaperServer(String code, String page, Listener listener) {
        this.code = code;
        this.page = page;
        this.listener = listener;
    }

    void start() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT));
        Thread t = new Thread(this::loop, "wallpaper-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        done = true;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        while (!done) {
            try (Socket s = socket.accept()) {
                s.setSoTimeout(30_000);
                handle(s);
            } catch (IOException e) {
                if (done) return;
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        String[] request = readLine(in).split(" ");
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(in);
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        if (request.length < 2) {
            respond(out, 400, "text/plain", "Bad request");
            return;
        }
        String method = request[0];
        String path = request[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        if ("GET".equals(method) && "/".equals(path)) {
            respond(out, 200, "text/html; charset=utf-8", page);
        } else if ("POST".equals(method) && "/upload".equals(path)) {
            upload(in, out, headers);
        } else {
            respond(out, 404, "text/plain", "Not found");
        }
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        if (!code.equals(headers.get("x-code"))) {
            respond(out, 403, "text/plain", "Wrong code");
            // Six digits are easy to guess with unlimited tries, so stop listening after a few.
            if (++wrongCodes >= MAX_WRONG_CODES) {
                stop();
                listener.onRejected();
            }
            return;
        }
        long length;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length <= 0) {
            respond(out, 411, "text/plain", "Length required");
            return;
        }
        if (length > WallpaperStore.MAX_BYTES) {
            respond(out, 413, "text/plain", "Too large");
            return;
        }
        listener.onReceiving();
        byte[] data = new byte[(int) length];
        int read = 0;
        while (read < data.length) {
            int n = in.read(data, read, data.length - read);
            if (n < 0) throw new IOException("Upload cut short");
            read += n;
        }
        if (listener.onImage(data)) {
            respond(out, 200, "text/plain", "OK");
            stop();
        } else {
            respond(out, 415, "text/plain", "Not an image");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') line.write(c);
            if (line.size() > MAX_LINE) throw new IOException("Header too long");
        }
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static void respond(OutputStream out, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            default:  return "Bad Request";
        }
    }
}
