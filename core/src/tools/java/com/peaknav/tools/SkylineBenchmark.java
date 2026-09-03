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
        if (args.length < 1) {
            System.err.println("usage: SkylineBenchmark manifest.json [limit]");
            System.exit(2);
        }
        File manifest = new File(args[0].replaceFirst("^~", System.getProperty("user.home")));
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        Summary summary = new Summary();
        run(manifest, limit, new DatasetElevation(), System.out, summary);
        System.out.println(summary);
    }

    /**
     * Runs every photo of a manifest, printing one line each to {@code out} and filling in
     * {@code summary}; returns the individual results.
     */
    public static List<Result> run(File manifest, int limit, DatasetElevation dem, PrintStream out,
                                   Summary summary) throws IOException {
        List<Map<String, Object>> entries;
        try (Reader reader = new FileReader(manifest)) {
            entries = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>() { }.getType());
        }
        File base = manifest.getAbsoluteFile().getParentFile();
        List<Result> results = new ArrayList<>();
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
            BufferedImage image = ImageIO.read(photo);
            if (image == null) {
                out.println(photo.getName() + ": unreadable, skipped");
                continue;
            }
            long t0 = System.nanoTime();
            int[] rgb = new int[SkylineExtractor.DEFAULT_WIDTH * 4];
            int[] size = new int[2];
            rgb = toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
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
        }
        return results;
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
