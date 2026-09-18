package com.peaknav.headless;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.compatibility.NativeScreenCallerDesktop;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends the app's snapshots to a file instead of to the share dialog.
 *
 * <p>{@code MapViewerScreen} finishes a snapshot by calling
 * {@code NativeScreenCaller.shareSnapshot(Pixmap, SnapshotInfo)}. On desktop that opens a save dialog and
 * waits for a person. Swapping in this subclass leaves the whole snapshot path untouched -
 * the flag, the moment it is taken in the frame, the crop - and only redirects where the
 * finished image ends up.
 *
 * <p>Writing goes through the platform's own {@code UtilsOSDep}, so the encoding is the same
 * one the app uses everywhere else, including the vertical flip that a framebuffer read needs.
 */
final class FileSnapshotWriter extends NativeScreenCallerDesktop {

    /** Forced output format, or null to take it from the file name. */
    private volatile Boolean jpegOverride = null;

    private final AtomicReference<File> target = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> pending = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    /**
     * Every UI request that was intercepted rather than shown, in order: what a person would
     * have been asked. Tests assert on the count; REST clients read the entries back
     * ({@code GET /prompts}) to learn what the app wanted, since nothing appears on screen.
     */
    private final List<SuppressedPrompt> suppressedPrompts = new ArrayList<>();
    private int suppressedPromptTotal;

    /** Only the latest are kept; the sequence numbers keep counting past the dropped ones. */
    private static final int KEPT_PROMPTS = 200;

    /** One intercepted request: {@code kind} names the hook, {@code detail} its text. */
    static final class SuppressedPrompt {
        final int seq;
        final long timeMillis;
        final String kind;
        final String detail;

        SuppressedPrompt(int seq, long timeMillis, String kind, String detail) {
            this.seq = seq;
            this.timeMillis = timeMillis;
            this.kind = kind;
            this.detail = detail;
        }
    }

    synchronized int suppressedPromptCount() {
        return suppressedPromptTotal;
    }

    /** The kept prompts with a sequence number above {@code afterSeq} (0 for all of them). */
    synchronized List<SuppressedPrompt> suppressedPromptsAfter(int afterSeq) {
        List<SuppressedPrompt> out = new ArrayList<>();
        for (SuppressedPrompt prompt : suppressedPrompts) {
            if (prompt.seq > afterSeq) {
                out.add(prompt);
            }
        }
        return out;
    }

    private synchronized void suppress(String kind, String detail) {
        suppressedPromptTotal++;
        suppressedPrompts.add(new SuppressedPrompt(
                suppressedPromptTotal, System.currentTimeMillis(), kind, detail));
        if (suppressedPrompts.size() > KEPT_PROMPTS) {
            suppressedPrompts.remove(0);
        }
        System.err.println("headless: suppressed " + kind + (detail.isEmpty() ? "" : ": " + detail));
    }

    /** Arms the writer for the next snapshot and returns a latch that fires once saved. */
    CountDownLatch expect(File output) {
        failure.set(null);
        target.set(output);
        CountDownLatch latch = new CountDownLatch(1);
        pending.set(latch);
        return latch;
    }

    /** Rethrows whatever the background save threw, if anything. */
    void rethrowFailure() {
        Throwable thrown = failure.getAndSet(null);
        if (thrown instanceof RuntimeException) {
            throw (RuntimeException) thrown;
        }
        if (thrown != null) {
            throw new IllegalStateException("could not save the snapshot", thrown);
        }
    }

    // ---------------------------------------------------------------------------
    // Everything that would put something on screen is neutralised below.
    //
    // Headless means headless: there is nobody to answer a dialog. A modal JOptionPane
    // raised here does not just look wrong, it stops the run dead - the render loop keeps
    // spinning while the dialog waits forever for a click that never comes. That is
    // exactly what "Dati mancanti per la posizione selezionata" did.
    //
    // Overriding them on this class is the reliable place: the headless renderer installs
    // it as the app's NativeScreenCaller, so every one of these paths lands here whichever
    // bit of the app decided to ask something.
    // ---------------------------------------------------------------------------

    @Override
    public void askForDownloadScreen(double lat, double lon) {
        // The prompt the user hit. Downloads are explicit here, via
        // PeakNavRenderer.downloadMissingData(); missing data otherwise just renders empty.
        suppress("download_prompt", String.format(Locale.ROOT,
                "map data missing at %.4f, %.4f (use download_timeout_ms to fetch it)", lat, lon));
    }

    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        suppress("download_chooser", String.format(Locale.ROOT, "%.4f, %.4f", lat, lon));
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        suppress("download_wizard", "");
    }

    @Override
    public void openScreenSearchLocation(com.peaknav.ui.ClickCallback callback) {
        // A Swing search window; positions are given explicitly here.
        suppress("search_location", "");
    }

    @Override
    public void openCameraPictureView() {
        suppress("camera", "");
    }

    @Override
    public void openGalleryPick() {
        // A file chooser; photographs come in through PeakNavRenderer's photo calls instead.
        suppress("gallery_pick", "");
    }

    @Override
    public void pickGpxFile() {
        // A file chooser; tracks come in through PeakNavRenderer's GPX calls instead.
        suppress("gpx_pick", "");
    }

    @Override
    public void promptGoToImageLocation(double lat, double lon) {
        suppress("go_to_image_location", String.format(Locale.ROOT, "%.4f, %.4f", lat, lon));
    }

    @Override
    public void promptYesNo(String title, String message, Runnable onYes) {
        // "Photo direction found - point the camera?" and the like: no one to answer, so no.
        suppress("yes_no", title + " - " + message);
    }

    @Override
    public void promptForTextFields(String title, String message, String[] labels,
            String[] initialValues, com.peaknav.ui.TextFieldsCallback callback) {
        suppress("text_fields", title);
        callback.onCancelled();
    }

    @Override
    public void chooseSkyTime() {
        // the sky time is set explicitly here (PeakNavRenderer.setSkyTimeMillis)
        suppress("sky_time", "");
    }

    @Override
    public com.peaknav.ui.CurrentLocationListener getCurrentLocationListener() {
        // The desktop listener asks for consent to locate by IP address in a dialog; headless
        // positions are always given explicitly, so the request is simply left unanswered.
        return callback -> suppress("current_location", "");
    }

    @Override
    public void ensureLocationPermissions() {
    }

    @Override
    public void warnCannotReadImageLocation() {
        suppress("image_location_unreadable", "");
    }

    @Override
    public void alertMessage(String message) {
        suppress("alert", String.valueOf(message));
    }

    @Override
    public void makeToast(String message) {
        // Not a prompt: the desktop toast is a window of its own, so it only goes to the log.
        System.err.println("headless: " + message);
    }

    @Override
    public void comingSoon() {
        suppress("coming_soon", "");
    }

    @Override
    public void openAppInfoScreen() {
        suppress("browser", "app info page");
    }

    @Override
    public void openCoordinate(double latitude, double longitude) {
        suppress("browser", String.format(Locale.ROOT, "coordinate %.5f, %.5f", latitude, longitude));
    }

    /**
     * Wraps the app's {@code Gdx.net} so that {@code openURI} - a hyperlink label tapped
     * through {@code /tap}, say - is recorded instead of launching a browser. Everything
     * else (HTTP requests, the downloads) goes straight through.
     */
    com.badlogic.gdx.Net withoutBrowser(final com.badlogic.gdx.Net net) {
        return (com.badlogic.gdx.Net) java.lang.reflect.Proxy.newProxyInstance(
                com.badlogic.gdx.Net.class.getClassLoader(),
                new Class<?>[] {com.badlogic.gdx.Net.class},
                (proxy, method, args) -> {
                    if ("openURI".equals(method.getName())) {
                        suppress("browser", String.valueOf(args[0]));
                        return Boolean.FALSE;
                    }
                    try {
                        return method.invoke(net, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Forces the format of later captures. JPEG is what a video wants: a frame is about a
     * tenth the size of the PNG and it is re-encoded by the video codec anyway.
     *
     * @param format "png", "jpg"/"jpeg", or null to go by the file's extension
     */
    void setFormat(String format) {
        if (format == null || format.trim().isEmpty()) {
            jpegOverride = null;
            return;
        }
        String f = format.trim().toLowerCase(Locale.ENGLISH);
        if (f.equals("jpg") || f.equals("jpeg")) {
            jpegOverride = Boolean.TRUE;
        } else if (f.equals("png")) {
            jpegOverride = Boolean.FALSE;
        } else {
            throw new IllegalArgumentException("unknown image format: " + format);
        }
    }

    @Override
    public void shareGpx(String fileName, String xml) {
        // Headless: nobody to hand a file to, and a dialog would stop the run.
    }

    @Override
    public void shareSnapshot(Pixmap pixmap, com.peaknav.utils.SnapshotInfo info) {
        // Called on one of the app's generic executor threads, not the render thread.
        CountDownLatch latch = pending.getAndSet(null);
        File output = target.getAndSet(null);
        try {
            if (output == null) {
                // A snapshot nobody asked for - the share button cannot be pressed here, but
                // do not leave the Pixmap leaked if it ever happens.
                return;
            }
            Boolean forced = jpegOverride;
            boolean jpeg = forced != null ? forced
                    : output.getName().toLowerCase(Locale.ENGLISH).endsWith(".jpg")
                            || output.getName().toLowerCase(Locale.ENGLISH).endsWith(".jpeg");
            // The same method the share button uses: it takes the bottom-up RGBA8888
            // pixmap straight from glReadPixels, flips the rows and writes via ImageIO.
            // Reimplementing any of that here is how the images came out upside down.
            savePixmapToFile(pixmap, output, jpeg, info);
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            pixmap.dispose();
            if (latch != null) {
                latch.countDown();
            }
        }
    }
}
