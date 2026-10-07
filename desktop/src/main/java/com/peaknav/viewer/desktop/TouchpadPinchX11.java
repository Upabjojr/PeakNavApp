package com.peaknav.viewer.desktop;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memGetDouble;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memGetLong;

import com.badlogic.gdx.Gdx;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.mapscreens.MapScreens;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.linux.X11;

import java.nio.ByteBuffer;

/**
 * Pinch to zoom on a touchpad, on Linux under X11.
 *
 * <p>GLFW has no touchpad gestures at all: two fingers scrolling arrive as a scroll, but a
 * pinch arrives as nothing. The X server has had them since XInput 2.4 (X.Org 21.1), as
 * GesturePinch events a client must ask for on its window; this asks for them on the map's
 * window, over a connection of its own, and turns the pinch's scale into the same zoom a
 * pinch on a phone makes.
 *
 * <p>Every call goes through LWJGL, which the app already carries: {@code X11} for the
 * connection, and libXi's functions looked up by name and called through {@code JNI}. The
 * struct offsets are those of the 64-bit Linux ABI, checked against XInput2.h. Anywhere this
 * cannot work - Wayland, an older server, no libXi - it does nothing and says why.
 */
final class TouchpadPinchX11 {

    // XI2.h
    private static final int XI_GESTURE_PINCH_BEGIN = 27;
    private static final int XI_GESTURE_PINCH_UPDATE = 28;
    private static final int XI_GESTURE_PINCH_END = 29;
    private static final int XI_ALL_MASTER_DEVICES = 1;
    private static final int GENERIC_EVENT = 35;

    // XGenericEventCookie
    private static final int COOKIE_EXTENSION = 32;
    private static final int COOKIE_EVTYPE = 36;
    private static final int COOKIE_DATA = 48;
    private static final int XEVENT_SIZE = 192;
    // XIGesturePinchEvent
    private static final int PINCH_SCALE = 152;

    private static final long POLL_MS = 8;

    private TouchpadPinchX11() {
    }

    /** Through the app's log: stderr is lost once the window is up. */
    private static void log(String message) {
        System.out.println("[Pinch] " + message);
    }

    /** Starts listening for pinches on the window, if this is X11 and the server has them. */
    static void start(long glfwWindow) {
        try {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_X11) {
                return;
            }
            long window = GLFWNativeX11.glfwGetX11Window(glfwWindow);
            if (window == 0) {
                return;
            }
            Thread thread = new Thread(() -> listen(window), "touchpad-pinch");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable unavailable) {
            log("no touchpad pinch: " + unavailable);
        }
    }

    private static void listen(long window) {
        long display = X11.XOpenDisplay((CharSequence) null);
        if (display == 0) {
            log("no touchpad pinch: cannot open the X display");
            return;
        }
        try (SharedLibrary xi = Library.loadNative(TouchpadPinchX11.class, "org.lwjgl", "libXi.so.6")) {
            SharedLibrary x11 = X11.getLibrary();
            long xQueryExtension = x11.getFunctionAddress("XQueryExtension");
            long xPending = x11.getFunctionAddress("XPending");
            long xNextEvent = x11.getFunctionAddress("XNextEvent");
            long xGetEventData = x11.getFunctionAddress("XGetEventData");
            long xFreeEventData = x11.getFunctionAddress("XFreeEventData");
            long xFlush = x11.getFunctionAddress("XFlush");
            long xiQueryVersion = xi.getFunctionAddress("XIQueryVersion");
            long xiSelectEvents = xi.getFunctionAddress("XISelectEvents");

            ByteBuffer name = BufferUtils.createByteBuffer(16).put("XInputExtension".getBytes()).put((byte) 0);
            name.flip();
            ByteBuffer ints = BufferUtils.createByteBuffer(12);
            if (JNI.invokePPPPPI(display, memAddress(name), memAddress(ints), memAddress(ints) + 4,
                    memAddress(ints) + 8, xQueryExtension) == 0) {
                log("no touchpad pinch: the X server has no XInput");
                return;
            }
            int xiOpcode = ints.getInt(0);

            // Ask for 2.4; the server answers with what it has.
            ByteBuffer version = BufferUtils.createByteBuffer(8);
            version.putInt(0, 2).putInt(4, 4);
            JNI.invokePPPI(display, memAddress(version), memAddress(version) + 4, xiQueryVersion);
            int major = version.getInt(0), minor = version.getInt(4);
            if (major < 2 || (major == 2 && minor < 4)) {
                log("no touchpad pinch: XInput " + major + "." + minor + ", gestures need 2.4");
                return;
            }

            // XIEventMask {int deviceid; int mask_len; unsigned char *mask;} with the three
            // pinch bits set.
            ByteBuffer maskBits = BufferUtils.createByteBuffer(4);
            for (int event : new int[]{XI_GESTURE_PINCH_BEGIN, XI_GESTURE_PINCH_UPDATE, XI_GESTURE_PINCH_END}) {
                maskBits.put(event >> 3, (byte) (maskBits.get(event >> 3) | (1 << (event & 7))));
            }
            ByteBuffer mask = BufferUtils.createByteBuffer(16);
            mask.putInt(0, XI_ALL_MASTER_DEVICES).putInt(4, maskBits.capacity()).putLong(8, memAddress(maskBits));
            if (JNI.invokePNPI(display, window, memAddress(mask), 1, xiSelectEvents) != 0) {
                log("no touchpad pinch: the window refused the gesture events");
                return;
            }
            JNI.invokePI(display, xFlush);
            log("touchpad pinch on (XInput " + major + "." + minor + ")");

            ByteBuffer event = BufferUtils.createByteBuffer(XEVENT_SIZE);
            long eventAddress = memAddress(event);
            double lastScale = 1.0;
            while (!Thread.currentThread().isInterrupted()) {
                if (JNI.invokePI(display, xPending) == 0) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                JNI.invokePPI(display, eventAddress, xNextEvent);
                if (memGetInt(eventAddress) != GENERIC_EVENT
                        || memGetInt(eventAddress + COOKIE_EXTENSION) != xiOpcode
                        || JNI.invokePPI(display, eventAddress, xGetEventData) == 0) {
                    continue;
                }
                try {
                    int type = memGetInt(eventAddress + COOKIE_EVTYPE);
                    // The scale is relative to where the fingers started: each update zooms
                    // by its ratio to the one before.
                    double scale = memGetDouble(memGetLong(eventAddress + COOKIE_DATA) + PINCH_SCALE);
                    if (type == XI_GESTURE_PINCH_BEGIN) {
                        lastScale = scale > 0 ? scale : 1.0;
                    } else if (type == XI_GESTURE_PINCH_UPDATE && scale > 0) {
                        final float ratio = (float) (scale / lastScale);
                        lastScale = scale;
                        Gdx.app.postRunnable(() -> zoomBy(ratio));
                    }
                } finally {
                    JNI.invokePPV(display, eventAddress, xFreeEventData);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable unavailable) {
            log("no touchpad pinch: " + unavailable);
        } finally {
            X11.XCloseDisplay(display);
        }
    }

    /** On the render thread: the map's camera, and only while the map itself is on screen. */
    private static void zoomBy(float ratio) {
        MapApp app = MapViewerSingleton.getAppInstance();
        if (app.getScreen() != app.mapViewerScreen || MapScreens.isOpen()) {
            return;
        }
        app.mapViewerScreen.controller.zoomByPinchScale(ratio);
    }
}
