package com.example.detectcamera;

import android.content.Context;
import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class WebServer extends NanoWSD {

    private final String username;
    private final String password;
    private static final List<ScreenWebSocket> activeSockets = new ArrayList<>();
    private static byte[] spsPpsBuffer = null;

    public WebServer(int port, String username, String password) {
        super(port);
        this.username = username;
        this.password = password;
    }

    public static void sendH264Chunk(byte[] chunk) {
        // Detectar y guardar SPS/PPS NAL units (0x00000001 seguido de NAL 7 u 8)
        if (chunk.length > 4 && chunk[0] == 0 && chunk[1] == 0 && chunk[2] == 0 && chunk[3] == 1) {
            int nalType = chunk[4] & 0x1F;
            if (nalType == 7 || nalType == 8) {
                spsPpsBuffer = chunk.clone();
            }
        }

        synchronized (activeSockets) {
            for (ScreenWebSocket socket : activeSockets) {
                try {
                    socket.send(chunk);
                } catch (IOException ignored) {}
            }
        }
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ScreenWebSocket(handshake);
    }

    @Override
    public Response serveHttp(IHTTPSession session) {
        if (!estaAutenticado(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Acceso denegado.");
        }

        String uri = session.getUri();
        if ("/stream/screen".equals(uri)) {
            return super.serveHttp(session);
        }

        return newFixedLengthResponse(Response.Status.OK, "text/html", getWebInterfaceHtml());
    }

    private boolean estaAutenticado(IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String credentials = new String(android.util.Base64.decode(base64Credentials, android.util.Base64.NO_WRAP));
            String[] parts = credentials.split(":", 2);
            return parts.length == 2 && username.equals(parts[0]) && password.equals(parts[1]);
        }
        return false;
    }

    private String getWebInterfaceHtml() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<title>Panel de Control - DetectCamera</title>"
            + "<style>"
            + "body { font-family: Arial, sans-serif; background: #121212; color: #fff; margin: 0; padding: 20px; }"
            + "h1 { text-align: center; color: #00e676; }"
            + ".grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; max-width: 1200px; margin: auto; }"
            + ".card { background: #1e1e1e; padding: 15px; border-radius: 10px; border: 1px solid #333; text-align: center; }"
            + "video, canvas { width: 100%; height: 320px; background: #000; border-radius: 6px; border: 1px solid #444; }"
            + ".controls { margin-top: 15px; display: flex; justify-content: center; gap: 10px; }"
            + "button { background: #00e676; color: #000; border: none; padding: 10px 15px; font-weight: bold; border-radius: 5px; cursor: pointer; }"
            + "button:hover { background: #00b359; }"
            + "</style></head><body>"
            + "<h1>Panel de Monitoreo en Vivo</h1>"
            + "<div class='grid'>"
            + "  <div class='card'>"
            + "    <h3>Cámara del Dispositivo</h3>"
            + "    <canvas id='cameraCanvas'></canvas>"
            + "    <div class='controls'><button onclick='toggleCamera()'>Alternar Cámara</button></div>"
            + "  </div>"
            + "  <div class='card'>"
            + "    <h3>Transmisión de Pantalla</h3>"
            + "    <video id='screenVideo' autoplay playsinline muted></video>"
            + "    <div class='controls'><button onclick='connectScreen()'>Reconectar Pantalla</button></div>"
            + "  </div>"
            + "</div>"
            + "<script>"
            + "let wsScreen;"
            + "function connectScreen() {"
            + "  const loc = window.location;"
            + "  const wsUrl = (loc.protocol === 'https:' ? 'wss://' : 'ws://') + loc.host + '/stream/screen';"
            + "  wsScreen = new WebSocket(wsUrl);"
            + "  wsScreen.binaryType = 'arraybuffer';"
            + "  wsScreen.onmessage = function(e) {"
            + "    /* Aquí se procesa el flujo H.264 recibido */"
            + "  };"
            + "}"
            + "window.onload = connectScreen;"
            + "</script></body></html>";
    }

    private class ScreenWebSocket extends NanoWSD.WebSocket {
        public ScreenWebSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
        }

        @Override
        protected void onOpen() {
            synchronized (activeSockets) {
                activeSockets.add(this);
            }
            // Envía inmediatamente el búfer SPS/PPS guardado para que el reproductor no se quede en negro
            if (spsPpsBuffer != null) {
                try {
                    send(spsPpsBuffer);
                } catch (IOException ignored) {}
            }
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            synchronized (activeSockets) {
                activeSockets.remove(this);
            }
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame message) {}

        @Override
        protected void onPong(NanoWSD.WebSocketFrame pong) {}

        @Override
        protected void Exception(IOException exception) {}
    }
}
