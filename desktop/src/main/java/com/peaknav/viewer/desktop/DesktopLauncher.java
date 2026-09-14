package com.peaknav.viewer.desktop;

import static com.peaknav.viewer.controller.MapController.setNumOfCpuCores;
import static com.peaknav.viewer.desktop.DesktopFiles.getGdxFilesExternalRootFolderName;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.peaknav.viewer.MapApp;

public class DesktopLauncher {
	public static final String appName = "PeakNav";

	private static MapApp mapApp;

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

	public static void main (String[] arg) {
		prepareMacWindowSystem();
		warnIfNoDesktopSupport();
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		config.setForegroundFPS(60);
		config.setTitle(appName);
		config.setWindowListener(new Lwjgl3WindowAdapter() {
			@Override
			public boolean closeRequested() {
				System.exit(0);
				return true;
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
