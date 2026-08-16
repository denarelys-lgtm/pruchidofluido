package com.example.detectcamera;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class ScreenDaemon {

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final int BIT_RATE = 3000000;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;

    private static MediaCodec encoder;
    private static Surface inputSurface;
    private static IBinder virtualDisplayToken;
    private static boolean isRunning = false;

    public static void main(String[] args) {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        startCapture();
    }

    public static synchronized void startCapture() {
        if (isRunning) return;
        isRunning = true;

        new Thread(() -> {
            try {
                prepareEncoder();
                createVirtualDisplay();
                encoder.start();
                drainEncoder();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                stopCapture();
            }
        }, "ScreenDaemon-Thread").start();
    }

    public static synchronized void stopCapture() {
        isRunning = false;
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (virtualDisplayToken != null) {
                destroyVirtualDisplay(virtualDisplayToken);
                virtualDisplayToken = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void prepareEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
    }

    private static void createVirtualDisplay() throws Exception {
        Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");

        IBinder mainDisplayToken = getMainDisplayToken(surfaceControlClass);

        Method createDisplayMethod = surfaceControlClass.getMethod("createDisplay", String.class, boolean.class);
        virtualDisplayToken = (IBinder) createDisplayMethod.invoke(null, "ScreenDaemonDisplay", false);

        openSurfaceControlTransaction(surfaceControlClass);

        try {
            Method setDisplaySurfaceMethod = surfaceControlClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            setDisplaySurfaceMethod.invoke(null, virtualDisplayToken, inputSurface);

            Method setDisplayLayerStackMethod = surfaceControlClass.getMethod("setDisplayLayerStack", IBinder.class, int.class);
            setDisplayLayerStackMethod.invoke(null, virtualDisplayToken, 0);

            Rect layerStackRect = new Rect(0, 0, WIDTH, HEIGHT);
            Rect displayRect = new Rect(0, 0, WIDTH, HEIGHT);

            Method setDisplayProjectionMethod = surfaceControlClass.getMethod(
                    "setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class
            );
            setDisplayProjectionMethod.invoke(null, virtualDisplayToken, 0, layerStackRect, displayRect);

        } finally {
            closeSurfaceControlTransaction(surfaceControlClass);
        }
    }

    private static IBinder getMainDisplayToken(Class<?> surfaceControlClass) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Method getPhysicalDisplayIdsMethod = surfaceControlClass.getMethod("getPhysicalDisplayIds");
            long[] ids = (long[]) getPhysicalDisplayIdsMethod.invoke(null);
            if (ids != null && ids.length > 0) {
                Method getPhysicalDisplayTokenMethod = surfaceControlClass.getMethod("getPhysicalDisplayToken", long.class);
                return (IBinder) getPhysicalDisplayTokenMethod.invoke(null, ids[0]);
            }
        }

        Method getBuiltInDisplayMethod = surfaceControlClass.getMethod("getBuiltInDisplay", int.class);
        return (IBinder) getBuiltInDisplayMethod.invoke(null, 0);
    }

    private static void openSurfaceControlTransaction(Class<?> surfaceControlClass) throws Exception {
        try {
            Method openTransactionMethod = surfaceControlClass.getMethod("openTransaction");
            openTransactionMethod.invoke(null);
        } catch (NoSuchMethodException ignored) {}
    }

    private static void closeSurfaceControlTransaction(Class<?> surfaceControlClass) throws Exception {
        try {
            Method closeTransactionMethod = surfaceControlClass.getMethod("closeTransaction");
            closeTransactionMethod.invoke(null);
        } catch (NoSuchMethodException ignored) {}
    }

    private static void destroyVirtualDisplay(IBinder token) {
        try {
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
            Method destroyDisplayMethod = surfaceControlClass.getMethod("destroyDisplay", IBinder.class);
            destroyDisplayMethod.invoke(null, token);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long lastKeyframeRequest = System.currentTimeMillis();

        while (isRunning) {
            if (System.currentTimeMillis() - lastKeyframeRequest > 3000) {
                requestKeyframe();
                lastKeyframeRequest = System.currentTimeMillis();
            }

            int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);

            if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);

                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.get(outData);

                    // Escribe los bytes H.264 a la salida estándar para desacoplar el proceso
                    try {
                        System.out.write(outData);
                        System.out.flush();
                    } catch (Exception ignored) {}
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    }

    private static void requestKeyframe() {
        if (encoder != null) {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        }
    }
}
