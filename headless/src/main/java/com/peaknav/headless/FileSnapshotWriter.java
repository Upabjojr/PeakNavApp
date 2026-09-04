package com.peaknav.headless;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.compatibility.NativeScreenCallerDesktop;

import java.io.File;
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
    /** Every UI request that was intercepted rather than shown, so tests can assert on it. */
    private final java.util.concurrent.atomic.AtomicInteger suppressedPrompts =
            new java.util.concurrent.atomic.AtomicInteger();

    int suppressedPromptCount() {
        return suppressedPrompts.get();
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
        suppressedPrompts.incrementAndGet();
        // The prompt the user hit. Downloads are explicit here, via
        // PeakNavRenderer.downloadMissingData(); missing data otherwise just renders empty.
        System.err.printf("headless: map data missing at %.4f, %.4f "
                + "(ignored; use --download to fetch it)%n", lat, lon);
    }

    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        suppressedPrompts.incrementAndGet();
        System.err.println("headless: download chooser suppressed");
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        suppressedPrompts.incrementAndGet();
        System.err.println("headless: download wizard suppressed");
    }

    @Override
    public void promptGoToImageLocation(double lat, double lon) {
        suppressedPrompts.incrementAndGet();
        // no prompt in headless
    }

    @Override
    public void warnCannotReadImageLocation() {
        suppressedPrompts.incrementAndGet();
        // no dialog in headless
    }

    @Override
    public void alertMessage(String message) {
        suppressedPrompts.incrementAndGet();
        System.err.println("headless: " + message);
    }

    @Override
    public void makeToast(String message) {
        System.err.println("headless: " + message);
    }

    @Override
    public void comingSoon() {
        suppressedPrompts.incrementAndGet();
        // no dialog in headless
    }

    @Override
    public void openAppInfoScreen() {
        // would launch a browser
    }

    @Override
    public void openAppTutorial() {
        // would launch a browser
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
