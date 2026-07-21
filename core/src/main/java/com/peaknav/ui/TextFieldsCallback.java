package com.peaknav.ui;

/**
 * Result of a native multi-field text prompt (see
 * {@link com.peaknav.compatibility.NativeScreenCaller#promptForTextFields}).
 */
public interface TextFieldsCallback {

    /**
     * @param values one entry per requested field, in the order the labels were given.
     */
    void onEntered(String[] values);

    /** Called instead of {@link #onEntered} when the user dismisses the dialog. */
    void onCancelled();
}
