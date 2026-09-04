package com.peaknav.skyline;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A gradient-boosted forest of small decision trees evaluated with plain threshold
 * comparisons - no linear algebra, no runtime dependency. Two are shipped as resources
 * next to this class, both trained offline by {@code tools/skyline_train.py} on
 * photographs whose camera pose is known (so where the ridge is, is known):
 * {@code sky_model.bin} gives every pixel a probability of being sky from its
 * {@link SkyFeatures}, {@code boundary_model.bin} gives every (column, row) a probability
 * that the skyline passes there from its {@link BoundaryFeatures}.
 *
 * <p>File layout, big-endian: {@code "SKY1"}, feature count, baseline score, tree count;
 * then per tree its node count and per node {@code feature} (-1 for a leaf), {@code
 * threshold}, {@code left}, {@code right}, {@code value}. A pixel's raw score is the
 * baseline plus the leaf value of every tree; the probability is its logistic.
 */
public final class SkyClassifier {

    private static final String RESOURCE = "sky_model.bin";
    private static final String BOUNDARY_RESOURCE = "boundary_model.bin";
    private static SkyClassifier shipped;
    private static SkyClassifier shippedBoundary;
    private static boolean lookedForShipped;
    private static boolean lookedForBoundary;

    private final int featureCount;
    private final float baseline;
    private final int[] treeStart;      // node offset of each tree
    private final int[] feature;        // -1 marks a leaf
    private final float[] threshold;
    private final int[] left;
    private final int[] right;
    private final float[] value;

    private SkyClassifier(int featureCount, float baseline, int[] treeStart, int[] feature,
                          float[] threshold, int[] left, int[] right, float[] value) {
        this.featureCount = featureCount;
        this.baseline = baseline;
        this.treeStart = treeStart;
        this.feature = feature;
        this.threshold = threshold;
        this.left = left;
        this.right = right;
        this.value = value;
    }

    /** The pixel model shipped with the app ({@link SkyFeatures}), or null when the build carries none. */
    public static synchronized SkyClassifier shipped() {
        if (!lookedForShipped) {
            lookedForShipped = true;
            shipped = load(RESOURCE, SkyFeatures.COUNT);
        }
        return shipped;
    }

    /** The boundary model shipped with the app ({@link BoundaryFeatures}), or null. */
    public static synchronized SkyClassifier shippedBoundary() {
        if (!lookedForBoundary) {
            lookedForBoundary = true;
            shippedBoundary = load(BOUNDARY_RESOURCE, BoundaryFeatures.COUNT);
        }
        return shippedBoundary;
    }

    private static SkyClassifier load(String resource, int expectedFeatures) {
        InputStream in = SkyClassifier.class.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            SkyClassifier model = read(in);
            // a model trained on another feature set would answer nonsense
            return model.featureCount == expectedFeatures ? model : null;
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    public static SkyClassifier read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int magic = in.readInt();
        if (magic != 0x534B5931) {   // "SKY1"
            throw new IOException("not a sky model");
        }
        int featureCount = in.readInt();
        if (featureCount <= 0 || featureCount > 1000) {
            throw new IOException("sky model with " + featureCount + " features");
        }
        float baseline = in.readFloat();
        int trees = in.readInt();
        int[] treeStart = new int[trees + 1];
        int[] counts = new int[trees];
        int total = 0;
        // Two passes are not possible on a stream; read into growable arrays instead.
        int[] feature = new int[256];
        float[] threshold = new float[256];
        int[] left = new int[256];
        int[] right = new int[256];
        float[] value = new float[256];
        for (int t = 0; t < trees; t++) {
            int nodes = in.readInt();
            counts[t] = nodes;
            treeStart[t] = total;
            if (total + nodes > feature.length) {
                int cap = Math.max(feature.length * 2, total + nodes);
                feature = java.util.Arrays.copyOf(feature, cap);
                threshold = java.util.Arrays.copyOf(threshold, cap);
                left = java.util.Arrays.copyOf(left, cap);
                right = java.util.Arrays.copyOf(right, cap);
                value = java.util.Arrays.copyOf(value, cap);
            }
            for (int k = 0; k < nodes; k++) {
                int i = total + k;
                feature[i] = in.readInt();
                threshold[i] = in.readFloat();
                left[i] = in.readInt();
                right[i] = in.readInt();
                value[i] = in.readFloat();
            }
            total += nodes;
        }
        treeStart[trees] = total;
        return new SkyClassifier(featureCount, baseline,
                treeStart, java.util.Arrays.copyOf(feature, total), java.util.Arrays.copyOf(threshold, total),
                java.util.Arrays.copyOf(left, total), java.util.Arrays.copyOf(right, total),
                java.util.Arrays.copyOf(value, total));
    }

    public int treeCount() {
        return treeStart.length - 1;
    }

    public int featureCount() {
        return featureCount;
    }

    /** Raw additive score of one feature vector (positive means sky). */
    public float score(float[] x) {
        float s = baseline;
        for (int t = 0; t < treeStart.length - 1; t++) {
            int node = treeStart[t];
            int base = treeStart[t];
            while (feature[node] >= 0) {
                node = base + (x[feature[node]] <= threshold[node] ? left[node] : right[node]);
            }
            s += value[node];
        }
        return s;
    }

    /** Probability of sky for one feature vector. */
    public float probability(float[] x) {
        return (float) (1.0 / (1.0 + Math.exp(-score(x))));
    }

    /** Probability for every pixel of the feature planes (which must be {@link #featureCount()} many). */
    public float[] probabilities(float[][] planes, int pixels) {
        if (planes.length != featureCount) {
            throw new IllegalArgumentException(planes.length + " planes for a model of " + featureCount + " features");
        }
        float[] x = new float[featureCount];
        float[] out = new float[pixels];
        for (int i = 0; i < pixels; i++) {
            SkyFeatures.gather(planes, i, x);
            out[i] = probability(x);
        }
        return out;
    }
}
