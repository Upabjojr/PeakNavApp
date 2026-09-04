package com.peaknav.tools;

import com.peaknav.skyline.SkylineExtractor;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Scores the skyline extractor alone against hand-traced skylines - no terrain, no
 * matcher - on the datasets of Ahmad et al. ("Resource efficient mountainous skyline
 * extraction using shallow learning", IJCNN 2021): CH1 (ETH Zurich, black-sky masks named
 * {@code <image>-mask.png}) and the Basalt Hills and Web sets (the skyline drawn in pure
 * red over a copy of the image, {@code GT_*_Edge.bmp}, paired with the images by their
 * running number).
 *
 * <pre>
 * ./gradlew :core:skylineMaskEval --args="[--annotate outDir] dataset/CH1/cvg dataset/web_dataset ..."
 * </pre>
 *
 * Each dataset directory holds {@code images/} and {@code ground_truth/}. What matters is
 * not being a few pixels off but tracing a cloud or a snow-line instead of the ridge, so a
 * picture is scored by the share of its columns where the traced row is further from the
 * truth than {@link #GROSS_FRACTION} of the height (the mean error is printed too).
 */
public final class SkylineMaskEval {

    /** A column is a gross miss when the traced row is further than this fraction of the height from the truth. */
    private static final float GROSS_FRACTION = 0.05f;

    private SkylineMaskEval() {
    }

    public static void main(String[] args) throws IOException {
        File annotate = null;
        List<File> sets = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--annotate") && i + 1 < args.length) {
                annotate = new File(expand(args[++i]));
            } else {
                sets.add(new File(expand(args[i])));
            }
        }
        if (sets.isEmpty()) {
            System.err.println("usage: SkylineMaskEval [--annotate outDir] datasetDir...");
            System.exit(2);
        }
        if (annotate != null) {
            annotate.mkdirs();
        }
        List<double[]> all = new ArrayList<double[]>();
        for (File set : sets) {
            List<double[]> errors = evaluate(set, annotate);
            all.addAll(errors);
            System.out.println(set.getName() + ": " + describe(errors));
        }
        if (sets.size() > 1) {
            System.out.println("all: " + describe(all));
        }
    }

    private static String expand(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
    }

    /** Per picture: {share of grossly missed columns in percent, mean error in pixels}. */
    static List<double[]> evaluate(File set, File annotate) throws IOException {
        File[] images = new File(set, "images").listFiles();
        File[] truths = new File(set, "ground_truth").listFiles();
        if (images == null || truths == null) {
            throw new IOException(set + " needs images/ and ground_truth/");
        }
        Arrays.sort(images);
        Arrays.sort(truths);
        List<double[]> errors = new ArrayList<double[]>();
        for (File image : images) {
            File truth = truthFor(image, truths);
            if (truth == null) {
                System.out.println(image.getName() + ": no ground truth, skipped");
                continue;
            }
            BufferedImage photo = ImageIO.read(image);
            BufferedImage gt = ImageIO.read(truth);
            if (photo == null || gt == null) {
                System.out.println(image.getName() + ": unreadable, skipped");
                continue;
            }
            int[] size = new int[2];
            int[] rgb = SkylineBenchmark.toSmallRgb(photo, SkylineExtractor.DEFAULT_WIDTH, size);
            int w = size[0], h = size[1];
            float[] trueRows = truth.getName().endsWith("-mask.png")
                    ? rowsFromMask(gt, w, h) : rowsFromRedLine(gt, w, h);
            SkylineExtractor.Skyline skyline = SkylineExtractor.extract(rgb, w, h);
            double sum = 0;
            int count = 0, gross = 0;
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(trueRows[x])) {
                    continue;
                }
                double d = Math.abs(skyline.rows[x] - trueRows[x]);
                sum += d;
                if (d > GROSS_FRACTION * h) {
                    gross++;
                }
                count++;
            }
            if (count == 0) {
                continue;
            }
            double meanPx = sum / count;
            double grossShare = 100.0 * gross / count;
            errors.add(new double[]{grossShare, meanPx});
            System.out.println(String.format(Locale.ENGLISH, "%-32s off by more than %.0f%% of height in %4.1f%% of columns; mean error %5.1f px",
                    image.getName(), 100 * GROSS_FRACTION, grossShare, meanPx));
            if (annotate != null) {
                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                out.setRGB(0, 0, w, h, rgb, 0, w);
                Graphics2D g = out.createGraphics();
                draw(g, trueRows, new java.awt.Color(60, 140, 255));
                draw(g, skyline.rows, java.awt.Color.RED);
                g.dispose();
                String stem = image.getName().replaceAll("\\.[^.]+$", "");
                ImageIO.write(out, "png", new File(annotate, set.getName() + "_" + stem + ".png"));
            }
        }
        return errors;
    }

    /** {@code name-mask.png} beside the image, else the edge picture with the same running number. */
    private static File truthFor(File image, File[] truths) {
        String stem = image.getName().replaceAll("\\.[^.]+$", "");
        for (File t : truths) {
            if (t.getName().equals(stem + "-mask.png")) {
                return t;
            }
        }
        String digits = stem.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        int number = Integer.parseInt(digits);
        for (File t : truths) {
            String d = t.getName().replaceAll("\\.[^.]+$", "").replaceAll("\\D", "");
            if (!d.isEmpty() && Integer.parseInt(d) == number) {
                return t;
            }
        }
        return null;
    }

    /** First non-black row per column of a black-sky mask, scaled to the working size. */
    private static float[] rowsFromMask(BufferedImage mask, int w, int h) {
        int mw = mask.getWidth(), mh = mask.getHeight();
        float[] rows = new float[w];
        for (int x = 0; x < w; x++) {
            int mx = Math.min(mw - 1, (int) ((x + 0.5) * mw / w));
            int y = 0;
            while (y < mh && (mask.getRGB(mx, y) & 0xFF) < 128) {
                y++;
            }
            rows[x] = y * (float) h / mh;
        }
        return rows;
    }

    /** Mean row of the pure-red pixels per column, scaled; NaN where none was drawn. */
    private static float[] rowsFromRedLine(BufferedImage edge, int w, int h) {
        int ew = edge.getWidth(), eh = edge.getHeight();
        float[] sum = new float[w];
        int[] count = new int[w];
        for (int y = 0; y < eh; y++) {
            for (int x = 0; x < ew; x++) {
                int p = edge.getRGB(x, y);
                if (((p >> 16) & 0xFF) == 255 && ((p >> 8) & 0xFF) == 0 && (p & 0xFF) == 0) {
                    int sx = Math.min(w - 1, x * w / ew);
                    sum[sx] += y * (float) h / eh;
                    count[sx]++;
                }
            }
        }
        float[] rows = new float[w];
        for (int x = 0; x < w; x++) {
            rows[x] = count[x] == 0 ? Float.NaN : sum[x] / count[x];
        }
        // fill short gaps from the neighbours, so a one-pixel-per-column line survives scaling
        for (int x = 0; x < w; x++) {
            if (Float.isNaN(rows[x])) {
                int a = x - 1, b = x + 1;
                while (a >= 0 && Float.isNaN(rows[a])) a--;
                while (b < w && Float.isNaN(rows[b])) b++;
                if (a >= 0 && b < w && b - a <= 6) {
                    rows[x] = rows[a] + (rows[b] - rows[a]) * (x - a) / (b - a);
                }
            }
        }
        return rows;
    }

    private static void draw(Graphics2D g, float[] rows, java.awt.Color color) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(2f));
        for (int x = 1; x < rows.length; x++) {
            if (!Float.isNaN(rows[x - 1]) && !Float.isNaN(rows[x])) {
                g.drawLine(x - 1, Math.round(rows[x - 1]), x, Math.round(rows[x]));
            }
        }
    }

    private static String describe(List<double[]> errors) {
        if (errors.isEmpty()) {
            return "no pictures";
        }
        int count = errors.size();
        double mean = 0;
        int clean = 0, mostlyRight = 0, within5 = 0, within10 = 0;
        for (double[] e : errors) {
            mean += e[0];
            if (e[0] == 0) clean++;
            if (e[0] < 10) mostlyRight++;
            if (e[1] < 5) within5++;
            if (e[1] < 10) within10++;
        }
        return String.format(Locale.ENGLISH,
                "%d pictures: gross misses (off by more than %.0f%% of height) in %.1f%% of columns on average; "
                        + "no gross miss at all: %d (%.0f%%), gross misses in under 10%% of columns: %d (%.0f%%); "
                        + "whole skyline within 5 px on average: %d (%.0f%%), within 10 px: %d (%.0f%%)",
                count, 100 * GROSS_FRACTION, mean / count, clean, 100.0 * clean / count,
                mostlyRight, 100.0 * mostlyRight / count, within5, 100.0 * within5 / count,
                within10, 100.0 * within10 / count);
    }
}
