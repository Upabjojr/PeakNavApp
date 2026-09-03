import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peaknav.tools.DatasetElevation;
import com.peaknav.tools.SkylineBenchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The skyline matcher on real photographs with a known camera heading - the datasets
 * {@code tools/skyline_dataset.py} builds (GeoPose3K's hand-annotated poses, Wikimedia
 * Commons photos with a heading) - through {@link SkylineBenchmark}, so a failure here
 * prints the same per-photo report the benchmark does.
 *
 * <p>Skipped, not failed, when no dataset is installed: it looks for manifests under
 * {@code $PEAKNAV_SKYLINE_DATASET} or {@code ~/.peaknav/skyline_dataset/*}, and needs the
 * elevation tiles of the photographed areas on disk.
 *
 * <p>What is asserted is the property the app relies on, not raw accuracy: when the matcher
 * says it is confident, it must be right nearly every time - a wrong "point the camera
 * here?" is worse than no suggestion. Overall accuracy is reported, and only loosely
 * bounded, because it is the photographs that vary (fog, fences, foregrounds), not the code.
 */
class TestSkylineDataset {

    private static List<File> manifests() {
        List<File> found = new ArrayList<>();
        String env = System.getenv("PEAKNAV_SKYLINE_DATASET");
        File[] roots = env != null
                ? new File[]{new File(env)}
                : new File[]{new File(System.getProperty("user.home"), ".peaknav/skyline_dataset")};
        for (File root : roots) {
            File direct = new File(root, "manifest.json");
            if (direct.exists()) {
                found.add(direct);
            }
            File[] subs = root.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    File m = new File(sub, "manifest.json");
                    if (m.exists()) {
                        found.add(m);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("confident matches are right (>= 85% within 10 degrees) on the installed datasets")
    void confidentMatchesAreRight() throws IOException {
        List<File> manifests = manifests();
        assumeTrue(!manifests.isEmpty(), "no skyline dataset installed (see tools/skyline_dataset.py)");
        DatasetElevation dem = new DatasetElevation();
        SkylineBenchmark.Summary total = new SkylineBenchmark.Summary();
        for (File manifest : manifests) {
            SkylineBenchmark.Summary summary = new SkylineBenchmark.Summary();
            SkylineBenchmark.run(manifest, Integer.MAX_VALUE, dem, System.out, summary);
            System.out.println(manifest + ": " + summary);
            total.photos += summary.photos;
            total.within5 += summary.within5;
            total.within10 += summary.within10;
            total.confident += summary.confident;
            total.confidentWithin10 += summary.confidentWithin10;
        }
        assumeTrue(total.photos >= 10, "too few photos with elevation data to judge: " + total);
        System.out.println("all datasets: " + total);
        assertTrue(total.confident >= 3, "the matcher should be confident on some photos: " + total);
        assertTrue(total.confidentPrecision() >= 0.85, "confident matches must be right: " + total);
        assertTrue(total.within10 >= 0.25 * total.photos, "overall accuracy collapsed: " + total);
    }
}
