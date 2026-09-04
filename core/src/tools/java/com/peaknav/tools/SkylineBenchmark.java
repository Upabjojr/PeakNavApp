package com.peaknav.tools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.peaknav.skyline.SkylineExtractor;
import com.peaknav.skyline.SkylineMatcher;
import com.peaknav.skyline.TerrainHorizon;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the photo skyline matcher over a dataset of geotagged mountain photographs whose
 * camera heading is known, and reports how often it recovers the bearing - and, more to
 * the point for the app, how often a match it calls confident is actually right.
 *
 * <pre>
 * ./gradlew :core:skylineBenchmark --args="~/.peaknav/skyline_dataset/geopose3k/manifest.json"
 * </pre>
 *
 * <p>The manifest comes from {@code tools/skyline_dataset.py}: one entry per photo with
 * {@code file, lat, lon, heading} and, when known, {@code pitch, fov} (GeoPose3K: the field
 * of view across the wider side, degrees) or {@code focal35} (Commons: the EXIF 35 mm
 * equivalent focal length). The elevation tiles of the photographed areas must be on disk
 * (see {@link DatasetElevation}); photos in areas without tiles are skipped.
 *
 * <p>This is also what {@code TestSkylineDataset} runs, so the numbers in a test failure
 * and in this report are the same numbers.
 */
public final class SkylineBenchmark {

    /** Height of the app's camera above the ground, the eye height used for the horizon. */
    public static final double EYE_ABOVE_GROUND_M = 20.0;
    public static final int HORIZON_BINS = 720;

    /** One photo's outcome. */
    public static final class Result {
        public final String file;
        public final double truthBearing;
        public final SkylineMatcher.Match match;
        public final double seconds;

        Result(String file, double truthBearing, SkylineMatcher.Match match, double seconds) {
            this.file = file;
            this.truthBearing = truthBearing;
            this.match = match;
            this.seconds = seconds;
        }

        public double bearingError() {
            double d = Math.abs(match.bearingDeg - truthBearing) % 360.0;
            return d > 180 ? 360 - d : d;
        }
    }

    /** Aggregate numbers over a run. */
    public static final class Summary {
        public int photos;
        public int within5;
        public int within10;
        public int confident;
        public int confidentWithin10;

        public double confidentPrecision() {
            return confident == 0 ? 0 : confidentWithin10 / (double) confident;
        }

        @Override
        public String toString() {
            return String.format(Locale.ENGLISH,
                    "%d photos: within 5deg %.0f%%, within 10deg %.0f%%; confident %d of which %d within 10deg (precision %.0f%%)",
                    photos, 100.0 * within5 / Math.max(1, photos), 100.0 * within10 / Math.max(1, photos),
                    confident, confidentWithin10, 100 * confidentPrecision());
        }
    }

    private SkylineBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        File annotate = null;
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--annotate") && i + 1 < args.length) {
                annotate = new File(args[++i].replaceFirst("^~", System.getProperty("user.home")));
            } else {
                plain.add(args[i]);
            }
        }
        if (plain.isEmpty()) {
            System.err.println("usage: SkylineBenchmark [--annotate outDir] manifest.json [limit]");
            System.exit(2);
        }
        File manifest = new File(plain.get(0).replaceFirst("^~", System.getProperty("user.home")));
        int limit = plain.size() > 1 ? Integer.parseInt(plain.get(1)) : Integer.MAX_VALUE;
        Summary summary = new Summary();
        run(manifest, limit, new DatasetElevation(), System.out, summary, annotate);
        System.out.println(summary);
    }

    public static List<Result> run(File manifest, int limit, DatasetElevation dem, PrintStream out,
                                   Summary summary) throws IOException {
        return run(manifest, limit, dem, out, summary, null);
    }

    /**
     * Runs every photo of a manifest, printing one line each to {@code out} and filling in
     * {@code summary}; returns the individual results. With {@code annotate} set, also
     * writes one PNG per photo into that
     * directory - the reduced photo with the extracted skyline in red, the matched pose's
     * ridge in green and, when the manifest carries the truth pose, its ridge in blue -
     * plus an {@code index.html} listing them with their numbers, for checking by eye.
     */
    public static List<Result> run(File manifest, int limit, DatasetElevation dem, PrintStream out,
                                   Summary summary, File annotate) throws IOException {
        List<Map<String, Object>> entries;
        try (Reader reader = new FileReader(manifest)) {
            entries = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>() { }.getType());
        }
        File base = manifest.getAbsoluteFile().getParentFile();
        List<Result> results = new ArrayList<>();
        StringBuilder index = new StringBuilder();
        if (annotate != null) {
            annotate.mkdirs();
            index.append("<!doctype html><meta charset=utf-8><title>skyline check</title>"
                    + "<style>body{font-family:sans-serif;background:#222;color:#ddd}figure{display:inline-block;margin:8px}"
                    + "img{max-width:480px;display:block}figcaption{font-size:12px;max-width:480px}"
                    + ".bad{color:#f66}.ok{color:#8f8}</style>"
                    + "<p>red = skyline the extractor traced; green = ridge of the matched pose; blue = ridge of the truth pose (when known);"
                    + " second picture = the classifier's sky probability (cyan sky, magenta ground, grey undecided)</p>\n");
        }
        for (Map<String, Object> e : entries) {
            if (results.size() >= limit) {
                break;
            }
            File photo = new File(base, (String) e.get("file"));
            double lat = number(e, "lat"), lon = number(e, "lon"), truth = number(e, "heading");
            if (!dem.hasTileFor(lat, lon)) {
                out.println(photo.getName() + ": no elevation tile, skipped");
                continue;
            }
            BufferedImage image = readOriented(photo);
            if (image == null) {
                out.println(photo.getName() + ": unreadable, skipped");
                continue;
            }
            long t0 = System.nanoTime();
            int[] size = new int[2];
            int[] rgb = toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
            int w = size[0], h = size[1];

            TerrainHorizon horizon = TerrainHorizon.compute(dem, lat, lon, EYE_ABOVE_GROUND_M, HORIZON_BINS);
            SkylineExtractor.Skyline skyline = SkylineExtractor.extract(rgb, w, h);
            SkylineMatcher matcher = new SkylineMatcher(horizon, skyline.rows, skyline.confidence, w, h);
            Float vfov = knownVerticalFov(e, w, h);
            SkylineMatcher.Match match = vfov == null ? matcher.match() : matcher.match(vfov);
            double seconds = (System.nanoTime() - t0) / 1e9;

            Result r = new Result(photo.getName(), truth, match, seconds);
            results.add(r);
            summary.photos++;
            double err = r.bearingError();
            if (err < 5) summary.within5++;
            if (err < 10) summary.within10++;
            if (match.isConfident()) {
                summary.confident++;
                if (err < 10) summary.confidentWithin10++;
            }
            out.println(String.format(Locale.ENGLISH, "%-36s truth %6.1f  got %6.1f  err %5.1f  %s  %.1fs",
                    truncate(photo.getParentFile().getName().equals(base.getName())
                            ? photo.getName() : photo.getParentFile().getName(), 36),
                    truth, match.bearingDeg, err, match, seconds));
            if (annotate != null) {
                String stem = photo.getParentFile().getName().equals(base.getName())
                        ? photo.getName().replaceAll("\\.[^.]+$", "") : photo.getParentFile().getName();
                stem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
                File png = new File(annotate, stem + ".png");
                Float[] truthPose = truthPose(e, w, h);
                writeAnnotated(png, rgb, w, h, skyline, matcher, match, truthPose);
                String probability = "";
                if (skyline.skyProbability != null) {
                    File sky = new File(annotate, stem + "_sky.png");
                    writeProbability(sky, rgb, w, h, skyline.skyProbability);
                    probability = "<img src=\"" + sky.getName() + "\">";
                }
                index.append(String.format(Locale.ENGLISH,
                        "<figure><img src=\"%s\">%s<figcaption class=\"%s\">%s<br>truth %.1f, got %.1f (err %.1f), %s</figcaption></figure>\n",
                        png.getName(), probability, err < 10 ? "ok" : "bad", stem, truth, match.bearingDeg, err, match));
            }
        }
        if (annotate != null) {
            try (java.io.Writer wr = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(new File(annotate, "index.html")), "UTF-8")) {
                wr.write(index.toString());
            }
        }
        return results;
    }

    /**
     * {bearing, pitch, vfov, roll} of the manifest's truth pose, or null without pitch and
     * FOV. GeoPose3K's roll goes in with its own sign: the roll that best fits each photo
     * correlates +0.75 with it (and -0.75 with its negative), so the two conventions agree.
     */
    private static Float[] truthPose(Map<String, Object> e, int w, int h) {
        Object pitch = e.get("pitch");
        Float vfov = knownVerticalFov(e, w, h);
        if (!(pitch instanceof Number) || vfov == null) {
            return null;
        }
        Object roll = e.get("roll");
        float rollDeg = roll instanceof Number ? ((Number) roll).floatValue() : 0f;
        return new Float[]{(float) number(e, "heading"), ((Number) pitch).floatValue(), vfov, rollDeg};
    }

    private static void writeAnnotated(File png, int[] rgb, int w, int h, SkylineExtractor.Skyline skyline,
                                       SkylineMatcher matcher, SkylineMatcher.Match match, Float[] truthPose)
            throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (truthPose != null) {
            drawRows(g, matcher.projectHorizon(truthPose[0], truthPose[1], truthPose[2], truthPose[3]), new java.awt.Color(60, 140, 255), 2f);
        }
        drawRows(g, matcher.projectHorizon(match.bearingDeg, match.pitchDeg, match.verticalFovDeg, match.rollDeg),
                new java.awt.Color(40, 220, 60), 2f);
        drawRows(g, skyline.rows, java.awt.Color.RED, 2f);
        g.dispose();
        ImageIO.write(img, "png", png);
    }

    /** The photo tinted by the sky probability: cyan where sure it is sky, magenta where sure it is not. */
    private static void writeProbability(File png, int[] rgb, int w, int h, float[] p) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] out = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int r = (rgb[i] >> 16) & 0xFF, g = (rgb[i] >> 8) & 0xFF, b = rgb[i] & 0xFF;
            int grey = (r * 299 + g * 587 + b * 114) / 1000;
            float q = p[i];
            int tr = Math.round(255 * (1 - q)), tg = Math.round(255 * q), tb = 255;
            int rr = (grey + tr) / 2, gg = (grey + tg) / 2, bb = (grey + tb) / 2;
            out[i] = (rr << 16) | (gg << 8) | bb;
        }
        img.setRGB(0, 0, w, h, out, 0, w);
        ImageIO.write(img, "png", png);
    }

    private static void drawRows(Graphics2D g, float[] rows, java.awt.Color color, float stroke) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(stroke));
        for (int x = 1; x < rows.length; x++) {
            if (Float.isNaN(rows[x - 1]) || Float.isNaN(rows[x])) {
                continue;
            }
            g.drawLine(x - 1, Math.round(rows[x - 1]), x, Math.round(rows[x]));
        }
    }

    /**
     * The vertical field of view a manifest entry implies, or null when unknown: GeoPose3K's
     * {@code fov} spans the wider side; a 35 mm-equivalent focal length is defined on the
     * 43.27 mm diagonal.
     */
    static Float knownVerticalFov(Map<String, Object> e, int w, int h) {
        Object fov = e.get("fov");
        if (fov instanceof Number) {
            double half = Math.toRadians(((Number) fov).doubleValue()) / 2;
            if (w >= h) {
                return (float) Math.toDegrees(2 * Math.atan(Math.tan(half) * h / (double) w));
            }
            return ((Number) fov).floatValue();
        }
        Object f35 = e.get("focal35");
        if (f35 instanceof Number) {
            double halfDiag = Math.atan(43.27 / 2 / ((Number) f35).doubleValue());
            return (float) Math.toDegrees(2 * Math.atan(Math.tan(halfDiag) * h / Math.hypot(w, h)));
        }
        return null;
    }

    /**
     * Reads a photo the way it is meant to be seen: ImageIO ignores the EXIF orientation
     * tag, so a picture stored on its side (as cameras do) is turned upright here, as the
     * app does with {@code PeakNavUtils.applyExifOrientation}.
     */
    public static BufferedImage readOriented(File photo) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(photo.toPath());
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) {
            return null;
        }
        int orientation = com.peaknav.utils.ExifReader.extractOrientation(bytes);
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth(), h = image.getHeight();
        boolean quarter = orientation >= 5;
        BufferedImage out = new BufferedImage(quarter ? h : w, quarter ? w : h, BufferedImage.TYPE_INT_RGB);
        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
        switch (orientation) {
            case 2: t.translate(w, 0); t.scale(-1, 1); break;
            case 3: t.translate(w, h); t.rotate(Math.PI); break;
            case 4: t.translate(0, h); t.scale(1, -1); break;
            case 5: t.rotate(Math.PI / 2); t.scale(1, -1); break;
            case 6: t.translate(h, 0); t.rotate(Math.PI / 2); break;
            case 7: t.translate(h, 0); t.rotate(Math.PI / 2); t.translate(w, 0); t.scale(-1, 1); break;
            case 8: t.translate(0, w); t.rotate(-Math.PI / 2); break;
            default: break;
        }
        Graphics2D g = out.createGraphics();
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }

    /** Downscales to {@code width} pixels wide (area averaging) and packs RGB ints. */
    public static int[] toSmallRgb(BufferedImage image, int width, int[] sizeOut) {
        int w = image.getWidth(), h = image.getHeight();
        int nw = Math.min(width, w);
        int nh = Math.max(1, (int) Math.round(h * (double) nw / w));
        BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Bilinear alone aliases when shrinking a lot; step down by halves first, as
        // Image.getScaledInstance(SCALE_AREA_AVERAGING) would but far faster.
        BufferedImage src = image;
        while (src.getWidth() > 2 * nw) {
            int hw = src.getWidth() / 2, hh = Math.max(1, src.getHeight() / 2);
            BufferedImage half = new BufferedImage(hw, hh, BufferedImage.TYPE_INT_RGB);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(src, 0, 0, hw, hh, null);
            hg.dispose();
            src = half;
        }
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        sizeOut[0] = nw;
        sizeOut[1] = nh;
        return small.getRGB(0, 0, nw, nh, null, 0, nw);
    }

    private static double number(Map<String, Object> e, String key) {
        Object v = e.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
