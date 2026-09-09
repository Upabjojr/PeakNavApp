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
	 * macOS only: re-launches this jar in a new JVM with {@code -XstartOnFirstThread},
	 * and returns true when it did (the caller must then do nothing else).
	 *
	 * <p>GLFW, which LWJGL and so libGDX use for the window, may only run its event loop
	 * on the process's very first thread on macOS. A JVM started without that flag puts
	 * {@code main} on a different thread, and the app dies as soon as it opens the
	 * window - which is what {@code java -jar peaknav.jar} did on every Mac, whatever
	 * the Java version. The packaged .app does not come through here: its native
	 * launcher (construo's "roast") already starts the JVM on the first thread.
	 *
	 * <p>Everything is deliberately Java 8: this runs before anything else, so it must
	 * load on whatever JVM the user happens to have.
	 */
	private static boolean restartedOnFirstThreadForMac (String[] arg) {
		try {
			return restartOnFirstThread(arg);
		} catch (Throwable neverStopTheApp) {
			System.err.println("PeakNav: first-thread check failed (" + neverStopTheApp + ")");
			return false;
		}
	}

	private static boolean restartOnFirstThread (String[] arg) {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ENGLISH);
		if (!os.contains("mac") && !os.contains("darwin")) {
			return false;
		}
		// The child we spawned below: it already has the flag.
		if (RESTART_MARKER.equals(System.getProperty(RESTART_PROPERTY))) {
			return false;
		}
		String jar = jarPath();
		if (jar == null) {
			// Not running from a jar (an IDE, say); the developer can pass the flag.
			System.err.println("PeakNav: on macOS, start the JVM with -XstartOnFirstThread.");
			return false;
		}
		// The packaged .app: its native launcher already put us on the first thread, and
		// spawning a second JVM there would be both wasteful and wrong.
		if (jar.contains(".app" + java.io.File.separator + "Contents" + java.io.File.separator)) {
			return false;
		}
		try {
			java.util.List<String> command = new java.util.ArrayList<String>();
			command.add(System.getProperty("java.home") + java.io.File.separator + "bin"
					+ java.io.File.separator + "java");
			command.add("-XstartOnFirstThread");
			command.add("-D" + RESTART_PROPERTY + "=" + RESTART_MARKER);
			command.add("-jar");
			command.add(jar);
			for (String a : arg) {
				command.add(a);
			}
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(false);
			builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
			builder.redirectError(ProcessBuilder.Redirect.INHERIT);
			builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
			System.exit(builder.start().waitFor());
			return true;
		} catch (Exception restartFailed) {
			// Better to try running here - it may still work - than to refuse to start.
			System.err.println("PeakNav: could not restart on the first thread ("
					+ restartFailed + "); continuing, which may fail on macOS.");
			return false;
		}
	}

	private static final String RESTART_PROPERTY = "peaknav.startOnFirstThread";
	private static final String RESTART_MARKER = "true";

	/** The jar this class was loaded from, or null when it was not loaded from one. */
	private static String jarPath () {
		try {
			java.net.URL source = DesktopLauncher.class.getProtectionDomain()
					.getCodeSource().getLocation();
			java.io.File file = new java.io.File(source.toURI());
			return file.isFile() && file.getName().endsWith(".jar") ? file.getAbsolutePath() : null;
		} catch (Exception notAJar) {
			return null;
		}
	}

	public static void main (String[] arg) {
		if (restartedOnFirstThreadForMac(arg)) {
			return;
		}
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
		MapViewerDesktopSingleton.initializeDesktopGraphicFactory();
		mapApp = MapViewerDesktopSingleton.getAppInstance();
		new Lwjgl3Application(mapApp, config) {
			@Override
			protected Files createFiles() {
				return new DesktopFiles();
			}
		};
	}

}
