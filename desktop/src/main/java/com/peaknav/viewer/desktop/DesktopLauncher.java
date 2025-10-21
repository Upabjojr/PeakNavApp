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

	public static void main (String[] arg) {
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
