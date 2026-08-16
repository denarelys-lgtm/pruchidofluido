package com.example.detectcamera;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.view.Surface;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenDaemon {
    private static final int PORT = 9090;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int BITRATE = 3_000_000; // 3 Mbps
    private static final int FPS = 60;

    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Daemon H.264 activo en puerto " + PORT);

            while (true) {
                Socket client = serverSocket.accept();
                OutputStream out = client.getOutputStream();

                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface inputSurface = codec.createInputSurface();

                // Interfaz SurfaceControl por reflexión
                Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
                Method createDisplayMethod = surfaceControlClass.getMethod("createDisplay", String.class, boolean.class);
                IBinder displayToken = (IBinder) createDisplayMethod.invoke(null, "ScreenDaemonDisplay", false);

                Method openTransactionMethod = surfaceControlClass.getMethod("openTransaction");
                Method closeTransactionMethod = surfaceControlClass.getMethod("closeTransaction");
                Method setDisplaySurfaceMethod = surfaceControlClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
                Method setDisplayProjectionMethod = surfaceControlClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
                Method setDisplayLayerStackMethod = surfaceControlClass.getMethod("setDisplayLayerStack", IBinder.class, int.class);

                openTransactionMethod.invoke(null);
                setDisplaySurfaceMethod.invoke(null, displayToken, inputSurface);
                setDisplayProjectionMethod.invoke(null, displayToken, 0, new Rect(0, 0, WIDTH, HEIGHT), new Rect(0, 0, WIDTH, HEIGHT));
                setDisplayLayerStackMethod.invoke(null, displayToken, 0);
                closeTransactionMethod.invoke(null);

                codec.start();
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                while (!client.isClosed()) {
                    int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                        byte[] outData = new byte[bufferInfo.size];
                        outputBuffer.get(outData);

                        byte[] header = ByteBuffer.allocate(4).putInt(outData.length).array();
                        out.write(header);
                        out.write(outData);
                        out.flush();

                        codec.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }

                codec.stop();
                codec.release();

                Method destroyDisplayMethod = surfaceControlClass.getMethod("destroyDisplay", IBinder.class);
                destroyDisplayMethod.invoke(null, displayToken);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
