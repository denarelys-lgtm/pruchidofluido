package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebServer extends NanoWSD {

    private String usuarioValido = "";
    private String passwordValida = "";
    private CameraService cameraService;
    private final AudioStreamManager audioStreamManager = new AudioStreamManager();
    private final Set<ScreenWebSocket> sockets = ConcurrentHashMap.newKeySet();

    public WebServer(int port) {
        super(port);
    }

    public void setCameraService(CameraService service) {
        this.cameraService = service;
    }

    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    // Método principal para transmitir H.264 por WebSocket
    public void retransmitirFrameH264(byte[] h264Chunk) {
        if (h264Chunk == null) return;
        for (ScreenWebSocket socket : sockets) {
            try {
                socket.send(h264Chunk);
            } catch (IOException e) {
                socket.closeQuietly();
            }
        }
    }

    // Métodos de compatibilidad requeridos por CameraService y ScreenCaptureController
    public void actualizarFrameCamara(byte[] frame) {
        // Compatibilidad con CameraService
    }

    public void actualizarFramePantalla(byte[] frame) {
        // Compatibilidad con ScreenCaptureController
        if (frame != null) {
            retransmitirFrameH264(frame);
        }
    }

    public void detenerAudio() {
        audioStreamManager.detenerCaptura();
    }

    private boolean estaAutenticado(IHTTPSession session) {
        if (usuarioValido.isEmpty() || passwordValida.isEmpty()) return true;
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Creds = authHeader.substring(6).trim();
                String credenciales = new String(Base64.decode(base64Creds, Base64.DEFAULT));
                String[] partes = credenciales.split(":", 2);
                if (partes.length == 2) {
                    return usuarioValido.equals(partes[0]) && passwordValida.equals(partes[1]);
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ScreenWebSocket(handshake);
    }

    @Override
    public Response serveHttp(IHTTPSession session) {
        if (!estaAutenticado(session)) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Acceso Denegado.");
            response.addHeader("WWW-Authenticate", "Basic realm=\"Acceso Restringido\"");
            return response;
        }

        String uri = session.getUri();

        if ("/audio.wav".equals(uri)) {
            java.io.InputStream audioStream = audioStreamManager.crearAudioStreamCliente();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error iniciando audio");
        }

        if ("/api/camera".equals(uri)) {
            String action = session.getParms().get("action");
            if (cameraService != null) {
                if ("on".equals(action)) cameraService.iniciarCamara();
                else if ("off".equals(action)) cameraService.detenerCamara();
                else if ("toggle".equals(action)) cameraService.alternarCamara();
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}");
        }

        String html = "<!DOCTYPE html><html><head><title>Panel Ultra-Fluido 60FPS</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>body{background:#121212;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:15px;}"
                + "canvas{background:#000;border-radius:8px;max-width:100%;height:auto;}</style></head><body>"
                + "<h1>Transmisión H.264 (60 FPS)</h1>"
                + "<canvas id='display'></canvas>"
                + "<script src='https://cdn.jsdelivr.net/npm/jmuxer@2.0.4/dist/jmuxer.min.js'></script>"
                + "<script>"
                + "  const jmuxer = new JMuxer({ node: 'display', mode: 'video', fps: 60, clearBuffer: true });"
                + "  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';"
                + "  const ws = new WebSocket(protocol + '//' + window.location.host + '/ws/screen');"
                + "  ws.binaryType = 'arraybuffer';"
                + "  ws.onmessage = (e) => jmuxer.feed({ video: new Uint8Array(e.data) });"
                + "</script></body></html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private class ScreenWebSocket extends WebSocket {
        public ScreenWebSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
        }

        @Override
        protected void onOpen() {
            sockets.add(this);
        }

        @Override
        protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            sockets.remove(this);
        }

        @Override
        protected void onMessage(WebSocketFrame message) {}

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            closeQuietly();
        }

        public void closeQuietly() {
            try {
                close(CloseCode.NormalClosure, "Closing", false);
            } catch (Exception ignored) {}
            sockets.remove(this);
        }
    }
}
