package com.peaknav.viewer.desktop;

import static com.peaknav.viewer.controller.MapController.setNumOfCpuCores;
import static com.peaknav.viewer.desktop.DesktopFiles.getGdxFilesExternalRootFolderName;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.peaknav.viewer.MapApp;

import org.lwjgl.glfw.GLFW;

public class DesktopLauncher {
	public static final String appName = "PeakNav";

	private static MapApp mapApp;

	/** The window's size where the display has room for it: libGDX's own default. */
	static final int DEFAULT_WINDOW_WIDTH = 640;
	static final int DEFAULT_WINDOW_HEIGHT = 480;

	/**
	 * Room kept for the window manager's title bar and borders - GLFW cannot measure them
	 * before the window exists - and for the window not to touch the edge of the screen.
	 */
	static final int WINDOW_FRAME_ALLOWANCE = 64;

	/** No window smaller than this, whatever the display: the app needs some room to draw. */
	static final int MIN_WINDOW_SIDE = 240;

	DesktopLauncher() {
	}

	/**
	 * macOS only: lets the map window and the Swing windows (place search, image and GPX
	 * choosers, dialogs) live in the same process.
	 *
	 * <p>GLFW, which LWJGL and so libGDX use for the window, may only touch the window system
	 * from the process's first thread on macOS. The usual answer is to start the JVM with
	 * {@code -XstartOnFirstThread}, and the launcher used to re-exec itself with that flag - but
	 * that flag also hands the first thread to GLFW's loop, and AWT needs that same thread for
	 * its own: every Swing window then waited forever, so search and "open an image" did
	 * nothing at all on a Mac.
	 *
	 * <p>LWJGL's "glfw_async" build solves both: it runs GLFW's calls on the first thread itself,
	 * so the JVM starts normally, the first thread stays AWT's, and no flag is needed. AWT is
	 * brought up before GLFW so that it, not GLFW, owns the application object on that thread.
	 * The packaged .app starts the same way; see {@code roast.runOnFirstThread} in the build.
	 *
	 * <p>Deliberately Java 8 and failure-proof: this runs before anything else.
	 */
	private static void prepareMacWindowSystem () {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ENGLISH);
		if (!os.contains("mac") && !os.contains("darwin")) {
			return;
		}
		try {
			Lwjgl3ApplicationConfiguration.useGlfwAsync();
			java.awt.Toolkit.getDefaultToolkit();
		} catch (Throwable neverStopTheApp) {
			System.err.println("PeakNav: macOS window-system setup failed (" + neverStopTheApp + ")");
		}
	}

	/**
	 * Says so at once when this Java cannot open a window, rather than letting the user
	 * find out by clicking search and getting nothing. See {@link DesktopSwing}; note that
	 * the Java term for it - "headless" - is not PeakNav's own headless renderer.
	 */
	private static void warnIfNoDesktopSupport () {
		DesktopSwing.announceIfUnavailable();
	}

	/**
	 * Map labels the app's fonts cannot draw (a name in its own script, for a reader of it) are
	 * drawn by Java2D. A Java without AWT's libraries cannot: the labels stay Latin there.
	 */
	public static void installLabelRasterizer() {
		try {
			com.peaknav.viewer.desktop.Java2DLabelRasterizer rasterizer = new com.peaknav.viewer.desktop.Java2DLabelRasterizer();
			rasterizer.canDraw("A");
			com.peaknav.viewer.labels.LabelTextRasterizers.set(rasterizer);
		} catch (Throwable noJava2D) {
			System.err.println("PeakNav: no Java2D text (" + noJava2D + "); labels stay in Latin letters");
		}
	}

	/**
	 * A window that fits the display it opens on.
	 *
	 * <p>The default 640 x 480 window is as tall as a Raspberry Pi's 800 x 480 touchscreen,
	 * and its panel and the title bar leave less than that: the window manager put the window
	 * where it could, and its bottom - the buttons along it - was below the screen's border.
	 * The window is sized from the primary monitor's work area, the screen less its panels and
	 * docks, and never larger than the default, so a monitor with the room still gets 640 x 480.
	 *
	 * <p>Failure-proof, like the other steps that run before the app: on any trouble the window
	 * keeps its default size.
	 */
	private static void fitWindowToDisplay (Lwjgl3ApplicationConfiguration config) {
		int width = DEFAULT_WINDOW_WIDTH;
		int height = DEFAULT_WINDOW_HEIGHT;
		try {
			// Initialises GLFW, which the work area query below needs (and the backend
			// initialises it only once, so this costs the app's own start nothing).
			Lwjgl3ApplicationConfiguration.getDisplayMode();
			long monitor = GLFW.glfwGetPrimaryMonitor();
			if (monitor != 0L) {
				int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
				GLFW.glfwGetMonitorWorkarea(monitor, x, y, w, h);
				width = windowSideWithin(w[0], DEFAULT_WINDOW_WIDTH);
				height = windowSideWithin(h[0], DEFAULT_WINDOW_HEIGHT);
			}
		} catch (Throwable neverStopTheApp) {
			System.err.println("PeakNav: could not measure the display (" + neverStopTheApp
					+ "); the window keeps its default size");
		}
		config.setWindowedMode(width, height);
	}

	/**
	 * One side of the window for a work area of {@code available} pixels along it: the
	 * default, when the work area has room for it and the window's frame, else what is left
	 * after the frame - never below {@link #MIN_WINDOW_SIDE}. An unknown work area (0, or a
	 * negative size from a monitor GLFW could not read) gives the default.
	 */
	static int windowSideWithin (int available, int preferred) {
		if (available <= 0) {
			return preferred;
		}
		int fits = available - WINDOW_FRAME_ALLOWANCE;
		return Math.max(MIN_WINDOW_SIDE, Math.min(preferred, fits));
	}

	public static void main (String[] arg) {
		prepareMacWindowSystem();
		warnIfNoDesktopSupport();
		installLabelRasterizer();
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		fitWindowToDisplay(config);
		config.setForegroundFPS(60);
		config.setTitle(appName);
		config.setWindowListener(new Lwjgl3WindowAdapter() {
			@Override
			public boolean closeRequested() {
				System.exit(0);
				return true;
			}

			@Override
			public void created(Lwjgl3Window window) {
				// Pinch to zoom on a touchpad, where the X server has it; GLFW does not.
				if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
					TouchpadPinchX11.start(window.getWindowHandle());
				}
			}

			@Override
			public void filesDropped(String[] files) {
				// A photo dropped on the window becomes the background, a .gpx a path.
				GalleryPickDesktop.openDropped(files);
			}
		});
		config.setPreferencesConfig(getGdxFilesExternalRootFolderName(), Files.FileType.External);
		setNumOfCpuCores(4);
		MapViewerDesktopSingleton.initializeDesktopLoadFactory();
		mapApp = MapViewerDesktopSingleton.getAppInstance();
		new Lwjgl3Application(mapApp, config) {
			@Override
			protected Files createFiles() {
				return new DesktopFiles();
			}
		};
	}

}
