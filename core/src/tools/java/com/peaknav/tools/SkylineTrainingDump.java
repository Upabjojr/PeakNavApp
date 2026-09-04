package com.peaknav.tools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.peaknav.skyline.BoundaryFeatures;
import com.peaknav.skyline.SkyClassifier;
import com.peaknav.skyline.SkyFeatures;
import com.peaknav.skyline.SkylineExtractor;
import com.peaknav.skyline.SkylineMatcher;
import com.peaknav.skyline.TerrainHorizon;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the training rows for {@link SkyClassifier}: for every photo of a manifest whose
 * full pose is known, the terrain ridge is projected through that pose, pixels above it are
 * labelled sky and pixels below it ground, and a sample of them is written with their
 * {@link SkyFeatures} - computed by the very code the app runs, so the trees learn the
 * numbers they will be asked about.
 *
 * <pre>
 * ./gradlew :core:skylineTrainingDump --args="manifest.json rows.csv.gz"
 * ./gradlew :core:skylineTrainingDump --args="--ridge geopose3k/manifest.json ridge.jsonl"          # truth ridges once
 * ./gradlew :core:skylineTrainingDump --args="--from-ridge ridge.jsonl rows.csv.gz --exclude-manifest geopose3k_manual/manifest.json --exclude-prefix eth_ch1_"
 * ./gradlew :core:skylineTrainingDump --args="--boundary-rows sky_model.bin ridge.jsonl rows2.csv.gz --exclude-manifest ... --exclude-prefix eth_ch1_"
 * ./gradlew :core:skylineTrainingDump --args="--mask-set dataset/CH1/cvg dataset/skyfinder/75 rows.csv.gz"
 * ./gradlew :core:skylineTrainingDump --args="--check sky_model.bin check.csv"
 * </pre>
 *
 * Rows within {@code BAND} pixels of the ridge are skipped (the truth is not that exact),
 * and most of the sample is drawn from the strip around the ridge, where the decision is
 * made. {@code --check} reads feature rows with the trainer's own probabilities and prints
 * the largest disagreement with the Java evaluation of the exported model.
 */
public final class SkylineTrainingDump {

    private static final int BAND = 3;
    private static final float NEAR_FRACTION = 0.12f;
    private static final int NEAR_SAMPLES = 900;
    private static final int FAR_SAMPLES = 500;

    private SkylineTrainingDump() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("--check")) {
            check(new File(args[1]), new File(args[2]));
            return;
        }
        if (args.length >= 3 && args[0].equals("--mask-set")) {
            File out = new File(args[args.length - 1].replaceFirst("^~", System.getProperty("user.home")));
            List<File> sets = new ArrayList<File>();
            for (int i = 1; i < args.length - 1; i++) {
                sets.add(new File(args[i].replaceFirst("^~", System.getProperty("user.home"))));
            }
            dumpMaskSets(sets, out);
            return;
        }
        if (args.length >= 3 && (args[0].equals("--from-ridge") || args[0].equals("--boundary-rows"))) {
            boolean boundary = args[0].equals("--boundary-rows");
            int i = 1;
            File model = boundary ? new File(expand(args[i++])) : null;
            File ridge = new File(expand(args[i++]));
            File out = new File(expand(args[i++]));
            List<File> excludeManifests = new ArrayList<File>();
            List<String> excludePrefixes = new ArrayList<String>();
            for (; i < args.length; i++) {
                if (args[i].equals("--exclude-manifest") && i + 1 < args.length) {
                    excludeManifests.add(new File(expand(args[++i])));
                } else if (args[i].equals("--exclude-prefix") && i + 1 < args.length) {
                    excludePrefixes.add(args[++i]);
                }
            }
            dumpFromRidge(ridge, out, model, excludeManifests, excludePrefixes);
            return;
        }
        if (args.length == 3 && args[0].equals("--ridge")) {
            ridge(new File(args[1].replaceFirst("^~", System.getProperty("user.home"))),
                    new File(args[2].replaceFirst("^~", System.getProperty("user.home"))), new DatasetElevation());
            return;
        }
        if (args.length < 2) {
            System.err.println("usage: SkylineTrainingDump manifest.json rows.csv.gz [limit]");
            System.exit(2);
        }
        File manifest = new File(args[0].replaceFirst("^~", System.getProperty("user.home")));
        File out = new File(args[1].replaceFirst("^~", System.getProperty("user.home")));
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        dump(manifest, out, limit, new DatasetElevation());
    }

    public static void dump(File manifest, File out, int limit, DatasetElevation dem) throws IOException {
        List<Map<String, Object>> entries;
        try (Reader reader = new FileReader(manifest)) {
            entries = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>() { }.getType());
        }
        File base = manifest.getAbsoluteFile().getParentFile();
        Random random = new Random(7);
        int photos = 0;
        long rows = 0;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new java.io.FileOutputStream(out)), "UTF-8")) {
            StringBuilder header = new StringBuilder("photo,label,y,ridge");
            for (String name : SkyFeatures.NAMES) {
                header.append(',').append(name);
            }
            w.write(header.append('\n').toString());
            for (Map<String, Object> e : entries) {
                if (photos >= limit) {
                    break;
                }
                File photo = new File(base, (String) e.get("file"));
                double lat = number(e, "lat"), lon = number(e, "lon");
                if (!dem.hasTileFor(lat, lon)) {
                    continue;
                }
                BufferedImage image = SkylineBenchmark.readOriented(photo);
                if (image == null) {
                    continue;
                }
                int[] size = new int[2];
                int[] rgb = SkylineBenchmark.toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
                int width = size[0], height = size[1];
                Float[] pose = truthPose(e, width, height);
                if (pose == null) {
                    continue;
                }
                TerrainHorizon horizon = TerrainHorizon.compute(dem, lat, lon,
                        SkylineBenchmark.EYE_ABOVE_GROUND_M, SkylineBenchmark.HORIZON_BINS);
                float[] dummy = new float[width];
                SkylineMatcher matcher = new SkylineMatcher(horizon, dummy, dummy, width, height);
                float[] ridge = matcher.projectHorizon(pose[0], pose[1], pose[2], pose[3]);
                int valid = 0;
                for (int x = 0; x < width; x++) {
                    if (!Float.isNaN(ridge[x])) {
                        valid++;
                    }
                }
                if (valid < width / 2) {
                    continue;
                }
                int n = width * height;
                float[] r = new float[n], g = new float[n], b = new float[n];
                for (int i = 0; i < n; i++) {
                    r[i] = ((rgb[i] >> 16) & 0xFF) / 255f;
                    g[i] = ((rgb[i] >> 8) & 0xFF) / 255f;
                    b[i] = (rgb[i] & 0xFF) / 255f;
                }
                float[][] planes = SkyFeatures.compute(r, g, b, width, height);
                String id = photo.getParentFile().getName().equals(base.getName())
                        ? photo.getName() : photo.getParentFile().getName();
                int near = Math.max(2, Math.round(NEAR_FRACTION * height));
                float[] x = new float[SkyFeatures.COUNT];
                for (int s = 0; s < NEAR_SAMPLES + FAR_SAMPLES; s++) {
                    int col = random.nextInt(width);
                    if (Float.isNaN(ridge[col])) {
                        continue;
                    }
                    int row = s < NEAR_SAMPLES
                            ? Math.round(ridge[col]) + random.nextInt(2 * near + 1) - near
                            : random.nextInt(height);
                    if (row < 0 || row >= height) {
                        continue;
                    }
                    float d = row - ridge[col];
                    if (Math.abs(d) <= BAND) {
                        continue;
                    }
                    int label = d < 0 ? 1 : 0;
                    SkyFeatures.gather(planes, row * width + col, x);
                    StringBuilder line = new StringBuilder(id).append(',').append(label)
                            .append(',').append(row).append(',').append(String.format(Locale.ENGLISH, "%.1f", ridge[col]));
                    for (float v : x) {
                        line.append(',').append(String.format(Locale.ENGLISH, "%.5g", v));
                    }
                    w.write(line.append('\n').toString());
                    rows++;
                }
                photos++;
                if (photos % 50 == 0) {
                    System.out.println(photos + " photos, " + rows + " rows");
                }
            }
        }
        System.out.println("wrote " + rows + " rows from " + photos + " photos to " + out);
    }

    /**
     * Writes, as JSON lines, the ridge projected through the truth pose of every photo of a
     * manifest at the extractor's working width: {@code {"id", "file", "width", "height",
     * "ridge": [row per column, null where the terrain leaves the frame]}}. The ground truth
     * for evaluating skyline extractors outside the app (Python studies).
     */
    private static void ridge(File manifest, File out, DatasetElevation dem) throws IOException {
        List<Map<String, Object>> entries;
        try (Reader reader = new FileReader(manifest)) {
            entries = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>() { }.getType());
        }
        File base = manifest.getAbsoluteFile().getParentFile();
        int photos = 0;
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8")) {
            for (Map<String, Object> e : entries) {
                File photo = new File(base, (String) e.get("file"));
                double lat = number(e, "lat"), lon = number(e, "lon");
                if (!dem.hasTileFor(lat, lon)) {
                    continue;
                }
                BufferedImage image = SkylineBenchmark.readOriented(photo);
                if (image == null) {
                    continue;
                }
                int[] size = new int[2];
                SkylineBenchmark.toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
                int width = size[0], height = size[1];
                Float[] pose = truthPose(e, width, height);
                if (pose == null) {
                    continue;
                }
                TerrainHorizon horizon = TerrainHorizon.compute(dem, lat, lon,
                        SkylineBenchmark.EYE_ABOVE_GROUND_M, SkylineBenchmark.HORIZON_BINS);
                float[] dummy = new float[width];
                SkylineMatcher matcher = new SkylineMatcher(horizon, dummy, dummy, width, height);
                float[] ridge = matcher.projectHorizon(pose[0], pose[1], pose[2], pose[3]);
                String id = photo.getParentFile().getName().equals(base.getName())
                        ? photo.getName() : photo.getParentFile().getName();
                StringBuilder line = new StringBuilder("{\"id\":\"").append(id.replace("\"", "\\\""))
                        .append("\",\"file\":\"").append(((String) e.get("file")).replace("\"", "\\\""))
                        .append("\",\"width\":").append(width).append(",\"height\":").append(height)
                        .append(",\"pose\":[").append(pose[0]).append(',').append(pose[1]).append(',')
                        .append(pose[2]).append(',').append(pose[3]).append("],\"ridge\":[");
                for (int x = 0; x < width; x++) {
                    if (x > 0) {
                        line.append(',');
                    }
                    line.append(Float.isNaN(ridge[x]) ? "null" : String.format(Locale.ENGLISH, "%.2f", ridge[x]));
                }
                w.write(line.append("]}\n").toString());
                photos++;
                if (photos % 100 == 0) {
                    System.out.println(photos + " photos");
                    w.flush();
                }
            }
        }
        System.out.println("wrote ridges of " + photos + " photos to " + out);
    }

    /**
     * Rows from pictures with a hand-made sky mask instead of a pose: each set directory
     * holds {@code images/} and {@code ground_truth/<image stem>-mask.png} (black sky, as
     * ETH's CH1 set and SkyFinder's per-camera masks arranged that way).
     */
    public static void dumpMaskSets(List<File> sets, File out) throws IOException {
        Random random = new Random(11);
        int photos = 0;
        long rows = 0;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new java.io.FileOutputStream(out)), "UTF-8")) {
            StringBuilder header = new StringBuilder("photo,label,y,ridge");
            for (String name : SkyFeatures.NAMES) {
                header.append(',').append(name);
            }
            w.write(header.append('\n').toString());
            for (File set : sets) {
                File[] images = new File(set, "images").listFiles();
                if (images == null) {
                    throw new IOException(set + " has no images/");
                }
                java.util.Arrays.sort(images);
                for (File photo : images) {
                    String stem = photo.getName().replaceAll("\\.[^.]+$", "");
                    File maskFile = new File(new File(set, "ground_truth"), stem + "-mask.png");
                    if (!maskFile.exists()) {
                        continue;
                    }
                    BufferedImage image = SkylineBenchmark.readOriented(photo);
                    BufferedImage mask = javax.imageio.ImageIO.read(maskFile);
                    if (image == null || mask == null) {
                        continue;
                    }
                    int[] size = new int[2];
                    int[] rgb = SkylineBenchmark.toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
                    int width = size[0], height = size[1];
                    // the mask at working size: sky where the mask is dark
                    int mw = mask.getWidth(), mh = mask.getHeight();
                    boolean[] sky = new boolean[width * height];
                    for (int y = 0; y < height; y++) {
                        int my = Math.min(mh - 1, (int) ((y + 0.5) * mh / height));
                        for (int x = 0; x < width; x++) {
                            int mx = Math.min(mw - 1, (int) ((x + 0.5) * mw / width));
                            sky[y * width + x] = (mask.getRGB(mx, my) & 0xFF) < 128;
                        }
                    }
                    // the boundary row per column, so the sample concentrates around it as
                    // the pose-based rows do
                    float[] ridge = new float[width];
                    for (int x = 0; x < width; x++) {
                        int y = 0;
                        while (y < height && sky[y * width + x]) {
                            y++;
                        }
                        ridge[x] = y;
                    }
                    int n = width * height;
                    float[] r = new float[n], g = new float[n], b = new float[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = ((rgb[i] >> 16) & 0xFF) / 255f;
                        g[i] = ((rgb[i] >> 8) & 0xFF) / 255f;
                        b[i] = (rgb[i] & 0xFF) / 255f;
                    }
                    float[][] planes = SkyFeatures.compute(r, g, b, width, height);
                    String id = set.getName() + "/" + stem;
                    int near = Math.max(2, Math.round(NEAR_FRACTION * height));
                    float[] x = new float[SkyFeatures.COUNT];
                    for (int s = 0; s < NEAR_SAMPLES + FAR_SAMPLES; s++) {
                        int col = random.nextInt(width);
                        int row = s < NEAR_SAMPLES
                                ? Math.round(ridge[col]) + random.nextInt(2 * near + 1) - near
                                : random.nextInt(height);
                        if (row < 0 || row >= height) {
                            continue;
                        }
                        // a hand-made mask is exact: keep everything but the boundary pixel itself
                        if (Math.abs(row - ridge[col]) <= 1) {
                            continue;
                        }
                        int label = sky[row * width + col] ? 1 : 0;
                        SkyFeatures.gather(planes, row * width + col, x);
                        StringBuilder line = new StringBuilder(id).append(',').append(label)
                                .append(',').append(row).append(',').append(String.format(Locale.ENGLISH, "%.1f", ridge[col]));
                        for (float v : x) {
                            line.append(',').append(String.format(Locale.ENGLISH, "%.5g", v));
                        }
                        w.write(line.append('\n').toString());
                        rows++;
                    }
                    photos++;
                }
            }
        }
        System.out.println("wrote " + rows + " rows from " + photos + " pictures to " + out);
    }

    private static String expand(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
    }

    private static final int BOUNDARY_POSITIVES = 400;
    private static final int BOUNDARY_NEAR_NEGATIVES = 900;
    private static final int BOUNDARY_FAR_NEGATIVES = 400;
    private static final int BOUNDARY_NEAR_RANGE = 40;
    private static final float BOUNDARY_POSITIVE_RADIUS = 1.5f;

    /**
     * Rows from a truth-ridge file ({@code --ridge} output: one JSON object per line with
     * {@code id, file, width, height, ridge[]}, null where unknown): the pixel rows of
     * {@link #dump} without recomputing the horizon, or - with a pixel model - the
     * boundary rows: positives within 1.5 px of the ridge, negatives within 40 px and
     * anywhere, with {@link BoundaryFeatures} computed from that model's probabilities.
     * Photos named in the excluded manifests or starting with an excluded prefix are
     * left out (the hand-posed photos and CH1 are the test sets).
     */
    public static void dumpFromRidge(File ridgeFile, File out, File pixelModel, List<File> excludeManifests,
                                     List<String> excludePrefixes) throws IOException {
        java.util.Set<String> excluded = new java.util.HashSet<String>();
        for (File manifest : excludeManifests) {
            List<Map<String, Object>> entries;
            try (Reader reader = new FileReader(manifest)) {
                entries = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>() { }.getType());
            }
            for (Map<String, Object> e : entries) {
                File photo = new File((String) e.get("file"));
                // GeoPose3K photos are known by their directory, others by their file name
                if (photo.getParentFile() != null) {
                    excluded.add(photo.getParentFile().getName());
                }
                excluded.add(photo.getName());
            }
        }
        SkyClassifier model = null;
        if (pixelModel != null) {
            try (FileInputStream in = new FileInputStream(pixelModel)) {
                model = SkyClassifier.read(in);
            }
        }
        File base = ridgeFile.getAbsoluteFile().getParentFile();
        Random random = new Random(pixelModel == null ? 7 : 13);
        int photos = 0, skipped = 0;
        long rows = 0;
        String[] names = model == null ? SkyFeatures.NAMES : BoundaryFeatures.NAMES;
        try (BufferedReader in = new BufferedReader(new FileReader(ridgeFile));
             Writer w = new OutputStreamWriter(new GZIPOutputStream(new java.io.FileOutputStream(out)), "UTF-8")) {
            StringBuilder header = new StringBuilder("photo,label,y,ridge");
            for (String name : names) {
                header.append(',').append(name);
            }
            w.write(header.append('\n').toString());
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> e;
                try {
                    e = new Gson().fromJson(line, new TypeToken<Map<String, Object>>() { }.getType());
                } catch (RuntimeException broken) {
                    continue;
                }
                if (e == null || !(e.get("ridge") instanceof List)) {
                    continue;
                }
                String id = (String) e.get("id");
                boolean skip = excluded.contains(id);
                for (String prefix : excludePrefixes) {
                    if (id.startsWith(prefix)) {
                        skip = true;
                    }
                }
                if (skip) {
                    skipped++;
                    continue;
                }
                String file = (String) e.get("file");
                File photo = new File(file).isAbsolute() ? new File(file) : new File(base, file);
                if (!photo.exists()) {
                    photo = new File(new File(base, "geopose3k"), file);
                }
                BufferedImage image = SkylineBenchmark.readOriented(photo);
                if (image == null) {
                    continue;
                }
                int[] size = new int[2];
                int[] rgb = SkylineBenchmark.toSmallRgb(image, SkylineExtractor.DEFAULT_WIDTH, size);
                int width = size[0], height = size[1];
                if (width != ((Number) e.get("width")).intValue() || height != ((Number) e.get("height")).intValue()) {
                    continue;
                }
                List<?> ridgeList = (List<?>) e.get("ridge");
                float[] ridge = new float[width];
                int valid = 0;
                for (int x = 0; x < width; x++) {
                    Object v = x < ridgeList.size() ? ridgeList.get(x) : null;
                    if (v instanceof Number) {
                        // outside the frame the column is all ground (above) or all sky (below)
                        ridge[x] = Math.max(0, Math.min(height - 1, ((Number) v).floatValue()));
                        valid++;
                    } else {
                        ridge[x] = Float.NaN;
                    }
                }
                if (valid < width / 2) {
                    continue;
                }
                int n = width * height;
                float[] r = new float[n], g = new float[n], b = new float[n];
                for (int i = 0; i < n; i++) {
                    r[i] = ((rgb[i] >> 16) & 0xFF) / 255f;
                    g[i] = ((rgb[i] >> 8) & 0xFF) / 255f;
                    b[i] = (rgb[i] & 0xFF) / 255f;
                }
                float[][] planes = SkyFeatures.compute(r, g, b, width, height);
                if (model != null) {
                    planes = BoundaryFeatures.compute(planes, model.probabilities(planes, n), width, height);
                }
                float[] x = new float[planes.length];
                if (model == null) {
                    int near = Math.max(2, Math.round(NEAR_FRACTION * height));
                    for (int s = 0; s < NEAR_SAMPLES + FAR_SAMPLES; s++) {
                        int col = random.nextInt(width);
                        if (Float.isNaN(ridge[col])) {
                            continue;
                        }
                        int row = s < NEAR_SAMPLES
                                ? Math.round(ridge[col]) + random.nextInt(2 * near + 1) - near
                                : random.nextInt(height);
                        if (row < 0 || row >= height) {
                            continue;
                        }
                        float d = row - ridge[col];
                        if (Math.abs(d) <= BAND) {
                            continue;
                        }
                        SkyFeatures.gather(planes, row * width + col, x);
                        w.write(row(id, d < 0 ? 1 : 0, row, ridge[col], x));
                        rows++;
                    }
                } else {
                    int total = BOUNDARY_POSITIVES + BOUNDARY_NEAR_NEGATIVES + BOUNDARY_FAR_NEGATIVES;
                    for (int s = 0; s < total; s++) {
                        int col = random.nextInt(width);
                        if (Float.isNaN(ridge[col])) {
                            continue;
                        }
                        int row;
                        if (s < BOUNDARY_POSITIVES) {
                            row = Math.round(ridge[col] + (2 * random.nextFloat() - 1));
                        } else if (s < BOUNDARY_POSITIVES + BOUNDARY_NEAR_NEGATIVES) {
                            row = Math.round(ridge[col]) + random.nextInt(2 * BOUNDARY_NEAR_RANGE + 1) - BOUNDARY_NEAR_RANGE;
                        } else {
                            row = random.nextInt(height);
                        }
                        if (row < 0 || row >= height) {
                            continue;
                        }
                        float d = Math.abs(row - ridge[col]);
                        if (d > BOUNDARY_POSITIVE_RADIUS && d <= BAND) {
                            continue;
                        }
                        SkyFeatures.gather(planes, row * width + col, x);
                        w.write(row(id, d <= BOUNDARY_POSITIVE_RADIUS ? 1 : 0, row, ridge[col], x));
                        rows++;
                    }
                }
                photos++;
                if (photos % 100 == 0) {
                    System.out.println(photos + " photos, " + rows + " rows");
                }
            }
        }
        System.out.println("wrote " + rows + " rows from " + photos + " photos (" + skipped + " excluded) to " + out);
    }

    private static String row(String id, int label, int row, float ridge, float[] x) {
        StringBuilder line = new StringBuilder(id).append(',').append(label)
                .append(',').append(row).append(',').append(String.format(Locale.ENGLISH, "%.1f", ridge));
        for (float v : x) {
            line.append(',').append(String.format(Locale.ENGLISH, "%.5g", v));
        }
        return line.append('\n').toString();
    }

    private static void check(File model, File csv) throws IOException {
        SkyClassifier classifier;
        try (FileInputStream in = new FileInputStream(model)) {
            classifier = SkyClassifier.read(in);
        }
        double worst = 0;
        int rows = 0;
        try (BufferedReader in = new BufferedReader(new FileReader(csv))) {
            String line = in.readLine();   // header
            int count = classifier.featureCount();
            float[] x = new float[count];
            while ((line = in.readLine()) != null) {
                String[] parts = line.split(",");
                for (int k = 0; k < count; k++) {
                    x[k] = Float.parseFloat(parts[k]);
                }
                double expected = Double.parseDouble(parts[count]);
                worst = Math.max(worst, Math.abs(classifier.probability(x) - expected));
                rows++;
            }
        }
        System.out.println(String.format(Locale.ENGLISH, "%d rows, %d trees, largest probability difference %.2e",
                rows, classifier.treeCount(), worst));
    }

    /** {bearing, pitch, vfov, roll}, or null when the manifest lacks the pose. */
    static Float[] truthPose(Map<String, Object> e, int w, int h) {
        Object pitch = e.get("pitch");
        Float vfov = SkylineBenchmark.knownVerticalFov(e, w, h);
        if (!(pitch instanceof Number) || vfov == null) {
            return null;
        }
        Object roll = e.get("roll");
        float rollDeg = roll instanceof Number ? ((Number) roll).floatValue() : 0f;
        return new Float[]{(float) number(e, "heading"), ((Number) pitch).floatValue(), vfov, rollDeg};
    }

    private static double number(Map<String, Object> e, String key) {
        Object v = e.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }
}
