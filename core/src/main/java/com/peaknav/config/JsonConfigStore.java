package com.peaknav.config;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * A {@link ProviderStore} backed by a JSON file in the app's data folder, under a shared
 * {@code json_config/} sub-folder. The data folder is {@code Gdx.files.external(...)},
 * which resolves to the platform's download location — {@code ~/.peaknav} (and the
 * per-OS equivalents) on desktop, the app's external files dir on Android — so the same
 * code writes to the right place on every platform.
 */
public class JsonConfigStore implements ProviderStore {

    /** Sub-folder of the data folder holding every JSON config file. */
    public static final String CONFIG_FOLDER = "json_config";

    private final String fileName;

    public JsonConfigStore(String fileName) {
        this.fileName = fileName;
    }

    private FileHandle handle() {
        return Gdx.files.external(CONFIG_FOLDER + "/" + fileName);
    }

    @Override
    public String read() {
        FileHandle handle = handle();
        if (!handle.exists()) {
            return null;
        }
        try {
            return handle.readString("UTF-8");
        } catch (RuntimeException e) {
            // A missing or unreadable file must not break the app: treat it as "unset".
            return null;
        }
    }

    @Override
    public void write(String json) {
        FileHandle handle = handle();
        handle.parent().mkdirs();
        handle.writeString(json, false, "UTF-8");
    }
}
