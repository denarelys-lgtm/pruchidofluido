package com.example.detectcamera;

import android.util.Log;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.Socket;

public class ScreenSocketClient {
    private static final String TAG = "ScreenSocketClient";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9090;

    private boolean isRunning = false;
    private Thread clientThread;

    public void start(WebServer webServer) {
        if (isRunning) return;
        isRunning = true;

        clientThread = new Thread(() -> {
            while (isRunning) {
                try (Socket socket = new Socket(HOST, PORT);
                     InputStream in = socket.getInputStream();
                     DataInputStream dataIn = new DataInputStream(in)) {

                    Log.d(TAG, "Conectado al daemon de pantalla H.264");

                    while (isRunning && !socket.isClosed()) {
                        int length = dataIn.readInt();
                        if (length > 0 && length < 10_000_000) {
                            byte[] h264Buffer = new byte[length];
                            dataIn.readFully(h264Buffer);

                            if (webServer != null) {
                                webServer.retransmitirFrameH264(h264Buffer);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error en el socket de pantalla, reintentando en 2s...", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }
        });

        clientThread.start();
    }

    public void stop() {
        isRunning = false;
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
    }
}
