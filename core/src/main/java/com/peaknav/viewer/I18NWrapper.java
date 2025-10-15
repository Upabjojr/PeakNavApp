package com.peaknav.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.I18NBundle;

import java.util.Locale;
import java.util.MissingResourceException;

public class I18NWrapper {

    private final I18NBundle i18NBundle;

    public I18NWrapper() {
        I18NBundle i18NBundle;
        try {
            i18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"));
        } catch (MissingResourceException missingResourceException) {
            i18NBundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), Locale.UK);
        }
        this.i18NBundle = i18NBundle;
        I18NBundle.setExceptionOnMissingKey(false);
    }

    public String s(String key) {
        if (i18NBundle == null)
            return key;
        return i18NBundle.get(key);
    }
}
