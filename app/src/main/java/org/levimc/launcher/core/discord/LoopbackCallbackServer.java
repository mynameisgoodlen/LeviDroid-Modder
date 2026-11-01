package org.levimc.launcher.core.discord;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class LoopbackCallbackServer {

    public interface Callback {
        void onResult(String state, String code, String error, String errorDescription);
    }

    private static final String TAG = "DiscordRpcCallback";

    private volatile ServerSocket serverSocket;
    private volatile Callback callback;
    private Thread acceptThread;

    public void start(Callback callback) throws IOException {
        this.callback = callback;
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, TAG);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public String getRedirectUri() {
        ServerSocket socket = serverSocket;
        if (socket == null) throw new IllegalStateException("Callback server not started");
        return "http://127.0.0.1:" + socket.getLocalPort() + "/callback";
    }

    public void stop() {
        try {
            ServerSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close callback server", e);
        }
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket socket = serverSocket;
            if (socket == null || socket.isClosed()) return;
            try {
                handleConnection(socket.accept());
            } catch (IOException e) {
                ServerSocket current = serverSocket;
                if (current == null || current.isClosed()) return;
            }
        }
    }

    private void handleConnection(Socket socket) {
        String state = null;
        String code = null;
        String error = null;
        String errorDescription = null;
        boolean callbackHit = false;
        try (socket) {
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                respond(socket, "HTTP/1.1 400 Bad Request", "<html><body><h3>Bad request</h3></body></html>");
                return;
            }
            URI uri = URI.create("http://localhost" + extractTarget(requestLine));
            if (!"/callback".equals(uri.getPath())) {
                respond(socket, "HTTP/1.1 404 Not Found", "<html><body><h3>Not found</h3></body></html>");
                return;
            }
            Map<String, String> params = parseQuery(uri.getRawQuery());
            state = params.get("state");
            code = params.get("code");
            error = params.get("error");
            errorDescription = params.get("error_description");
            callbackHit = true;
            if (error == null && code != null) {
                respond(socket, "HTTP/1.1 200 OK", "<html><body><h3>Discord linked</h3><p>You can close this tab and return to LeviLauncher.</p></body></html>");
            } else {
                respond(socket, "HTTP/1.1 400 Bad Request", "<html><body><h3>Discord login failed</h3><p>Return to LeviLauncher and try again.</p></body></html>");
            }
        } catch (Exception e) {
            Log.w(TAG, "Callback connection failed", e);
            return;
        }
        if (callbackHit) {
            Callback cb = callback;
            if (cb != null) cb.onResult(state, code, error, errorDescription);
        }
    }

    private static String extractTarget(String requestLine) {
        int start = 4;
        int end = requestLine.indexOf(' ', start);
        String target = end > start ? requestLine.substring(start, end) : requestLine.substring(start);
        return target.trim();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                params.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                );
            } catch (IllegalArgumentException ignored) {
            }
        }
        return params;
    }

    private static void respond(Socket socket, String statusLine, String page) throws IOException {
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write((statusLine + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
