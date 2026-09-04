import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.skyline.SkyClassifier;
import com.peaknav.skyline.SkyFeatures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The sky pixel classifier: its file format, tree walk, and the features it splits on. */
class TestSkyClassifier {

    /** A forest of one stump on {@code rowFrac}: sky in the top half, ground below. */
    private static byte[] stumpModel() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x534B5931);
        out.writeInt(SkyFeatures.COUNT);
        out.writeFloat(0.25f);           // baseline
        out.writeInt(1);                 // trees
        out.writeInt(3);                 // nodes
        out.writeInt(6); out.writeFloat(0.5f); out.writeInt(1); out.writeInt(2); out.writeFloat(0f);
        out.writeInt(-1); out.writeFloat(0f); out.writeInt(0); out.writeInt(0); out.writeFloat(2f);
        out.writeInt(-1); out.writeFloat(0f); out.writeInt(0); out.writeInt(0); out.writeFloat(-3f);
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("A model file is read and evaluated as baseline plus leaf values")
    void readsAndScores() throws IOException {
        SkyClassifier model = SkyClassifier.read(new ByteArrayInputStream(stumpModel()));
        assertEquals(1, model.treeCount());
        float[] x = new float[SkyFeatures.COUNT];
        x[6] = 0.2f;
        assertEquals(2.25f, model.score(x), 1e-6);
        assertEquals(1 / (1 + Math.exp(-2.25)), model.probability(x), 1e-6);
        x[6] = 0.9f;
        assertEquals(-2.75f, model.score(x), 1e-6);
    }

    @Test
    @DisplayName("Features come out finite, one plane each, and see the sky/ground contrast")
    void featuresOfASimplePicture() {
        int w = 60, h = 40;
        float[] r = new float[w * h], g = new float[w * h], b = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean sky = y < 15 + (x % 7);
                r[y * w + x] = sky ? 0.55f : 0.30f;
                g[y * w + x] = sky ? 0.70f : 0.35f + ((x * 31 + y * 17) % 5) * 0.03f;
                b[y * w + x] = sky ? 0.95f : 0.25f;
            }
        }
        float[][] planes = SkyFeatures.compute(r, g, b, w, h);
        assertEquals(SkyFeatures.COUNT, planes.length);
        for (float[] plane : planes) {
            assertNotNull(plane);
            assertEquals(w * h, plane.length);
            for (float v : plane) {
                assertTrue(Float.isFinite(v));
            }
        }
        int skyPixel = 5 * w + 30, groundPixel = 35 * w + 30;
        assertTrue(planes[5][skyPixel] > planes[5][groundPixel], "sky is bluer");
        assertEquals(0f, planes[16][skyPixel], 0.05f, "sky looks like the top rows");
        assertTrue(planes[16][groundPixel] < -0.2f, "ground is darker than the top rows");
        assertTrue(planes[11][groundPixel] > planes[11][skyPixel], "ground has texture");
    }
}
