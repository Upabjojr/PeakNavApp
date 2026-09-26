package com.peaknav.viewer.desktop;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The operating system's own file dialogs, in place of Swing's chooser: the dialog people know
 * from every other program on their computer, with its places, its search and its previews of
 * pictures.
 *
 * <ul>
 *   <li><b>Windows and macOS:</b> AWT's {@link FileDialog}, which on both is the native one -
 *       Explorer's dialog with its thumbnail views, and the Finder's panel with Quick Look.</li>
 *   <li><b>Linux:</b> AWT's dialog there is an old GTK one without thumbnails, so the desktop's
 *       own is asked for instead: kdialog under KDE, zenity elsewhere (zenity 4 is GTK 4's
 *       chooser, with its grid of thumbnails).</li>
 * </ul>
 *
 * <p>Where there is none of these, {@link #UNAVAILABLE} comes back and the caller shows Swing's
 * chooser, as before. Call from the event dispatch thread (see {@link DesktopSwing#onEdt}): the
 * dialogs are modal, and AWT's must be shown from there.
 *
 * <p>The native dialogs ask before overwriting a file themselves; a caller only needs to ask
 * when it changes the name it was given - adding an extension, say.
 */
public final class NativeFileDialogs {

    /** Returned when this system has no native dialog to offer: show Swing's chooser instead. */
    public static final File UNAVAILABLE = new File("\u0000native-file-dialog-unavailable");

    /** What the dialog lists: a description, and the extensions it covers, without dots. */
    public static final class Filter {
        final String description;
        final String[] extensions;

        public Filter(String description, String... extensions) {
            this.description = description;
            this.extensions = extensions;
        }

        boolean accepts(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String extension : extensions) {
                if (lower.endsWith("." + extension)) {
                    return true;
                }
            }
            return false;
        }

        /** "*.jpg *.jpeg *.png", in both cases, for the Linux tools' patterns. */
        String patterns(String separator) {
            StringBuilder out = new StringBuilder();
            for (String extension : extensions) {
                for (String e : new String[]{extension.toLowerCase(Locale.ROOT), extension.toUpperCase(Locale.ROOT)}) {
                    if (out.length() > 0) {
                        out.append(separator);
                    }
                    out.append("*.").append(e);
                }
            }
            return out.toString();
        }
    }

    /** Where the last dialog was answered, so the next one opens there. */
    private static volatile File lastDirectory;

    private NativeFileDialogs() {
    }

    /** Asks for a file to open. Null if the user cancelled; {@link #UNAVAILABLE} if no native dialog. */
    public static File open(String title, Filter filter) {
        return ask(title, filter, null, false);
    }

    /**
     * Asks where to save, suggesting {@code suggestedName}. Null if the user cancelled;
     * {@link #UNAVAILABLE} if no native dialog. The name is as typed: it may lack an extension.
     */
    public static File save(String title, Filter filter, String suggestedName) {
        return ask(title, filter, suggestedName, true);
    }

    private static File ask(String title, Filter filter, String suggestedName, boolean save) {
        File chosen;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win") || os.contains("mac") || os.contains("darwin")) {
                chosen = awt(title, filter, suggestedName, save, os.contains("win"));
            } else {
                chosen = linux(title, filter, suggestedName, save);
            }
        } catch (Throwable failed) {
            // An AWT without a display, a tool that would not start: Swing's chooser instead.
            System.err.println("PeakNav: native file dialog unavailable (" + failed + ")");
            return UNAVAILABLE;
        }
        if (chosen != null && chosen != UNAVAILABLE) {
            chosen = chosen.getAbsoluteFile();
            lastDirectory = chosen.getParentFile();
        }
        return chosen;
    }

    private static File awt(String title, Filter filter, String suggestedName, boolean save, boolean windows) {
        FileDialog dialog = new FileDialog((Frame) null, title == null ? "" : title,
                save ? FileDialog.SAVE : FileDialog.LOAD);
        if (lastDirectory != null) {
            dialog.setDirectory(lastDirectory.getPath());
        }
        // macOS honours a filename filter; Windows ignores it but takes a pattern as the name.
        dialog.setFilenameFilter((dir, name) -> filter.accepts(name));
        if (save && suggestedName != null) {
            dialog.setFile(suggestedName);
        } else if (windows) {
            dialog.setFile(filter.patterns(";").replace(" ", ""));
        }
        dialog.setVisible(true);   // modal: returns once answered
        String file = dialog.getFile();
        if (file == null) {
            return null;
        }
        return new File(dialog.getDirectory(), file);
    }

    private static File linux(String title, Filter filter, String suggestedName, boolean save) throws Exception {
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        boolean kde = desktop != null && desktop.toUpperCase(Locale.ROOT).contains("KDE");
        String start = lastDirectory != null ? lastDirectory.getPath() : System.getProperty("user.home");
        List<String> command = new ArrayList<>();
        if (kde && onPath("kdialog")) {
            command.add("kdialog");
            command.add(save ? "--getsavefilename" : "--getopenfilename");
            command.add(save && suggestedName != null ? new File(start, suggestedName).getPath() : start);
            command.add(filter.description + " (" + filter.patterns(" ") + ")");
            if (title != null) {
                command.add("--title");
                command.add(title);
            }
        } else if (onPath("zenity")) {
            command.add("zenity");
            command.add("--file-selection");
            if (save) {
                command.add("--save");
            }
            if (title != null) {
                command.add("--title=" + title);
            }
            command.add("--filename=" + (save && suggestedName != null
                    ? new File(start, suggestedName).getPath() : start + File.separator));
            command.add("--file-filter=" + filter.description + " | " + filter.patterns(" "));
        } else {
            return UNAVAILABLE;
        }
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();   // Java 8: no DISCARD
        String line;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            line = out.readLine();
        }
        int exit = process.waitFor();
        if (exit == 1) {
            return null;   // cancelled
        }
        if (exit != 0 || line == null || line.trim().isEmpty()) {
            return UNAVAILABLE;   // the tool failed: Swing's chooser rather than nothing
        }
        return new File(line.trim());
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, program).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
