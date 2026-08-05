import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.LruCache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The image cache's contract, written down because the implementation behind it changed.
 *
 * <p>{@code PeakNavUtils.readImageCached} used Guava's {@code CacheBuilder} until Guava turned
 * out to be unusable on iOS - RoboVM's runtime has no {@code java.util.function}, so building
 * any cache threw on launch. {@link LruCache} replaced it, which means a well-tested library
 * was swapped for hand-written code on the path that owns every pixmap's native memory. A
 * mistake there does not throw: it leaks video memory, or disposes a pixmap another thread is
 * still drawing from. Hence these.
 *
 * <p>The eviction listener is the part that matters most. In the real cache it is what feeds
 * {@code disposalQueue}, so a value the cache stops holding and does NOT report is a leak.
 */
class TestLruCache {

    @Test
    @DisplayName("A hit returns the stored value and does not run the loader again")
    void hitDoesNotReload() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        LruCache<String, String> cache = new LruCache<>(10, evicted -> { });

        String first = cache.get("k", () -> { loads.incrementAndGet(); return "v"; });
        String second = cache.get("k", () -> { loads.incrementAndGet(); return "other"; });

        assertEquals("v", first);
        assertSame(first, second, "the second call must return the stored instance");
        assertEquals(1, loads.get(), "the loader must run only on a miss");
    }

    @Test
    @DisplayName("Going over the bound evicts the least recently used entry, and reports it")
    void evictsLeastRecentlyUsed() throws Exception {
        List<String> evicted = new ArrayList<>();
        LruCache<String, String> cache = new LruCache<>(2, evicted::add);

        cache.get("a", () -> "A");
        cache.get("b", () -> "B");
        cache.get("c", () -> "C");

        assertEquals(2, cache.size(), "the bound must hold");
        assertEquals(Collections.singletonList("A"), evicted,
                "the oldest entry is the one that goes, and it must be reported");
    }

    @Test
    @DisplayName("Reading an entry makes it recent, so the other one is evicted instead")
    void readingRefreshesRecency() throws Exception {
        List<String> evicted = new ArrayList<>();
        LruCache<String, String> cache = new LruCache<>(2, evicted::add);

        cache.get("a", () -> "A");
        cache.get("b", () -> "B");
        cache.get("a", () -> "A again");   // a hit: "a" becomes the most recent
        cache.get("c", () -> "C");

        assertEquals(Collections.singletonList("B"), evicted,
                "\"b\" was the least recently used once \"a\" had been read");
    }

    @Test
    @DisplayName("A loader that throws surfaces as ExecutionException, carrying its cause")
    void loaderFailurePropagates() {
        LruCache<String, String> cache = new LruCache<>(4, evicted -> { });

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> cache.get("missing", () -> { throw new IOException("no such file"); }));

        assertTrue(thrown.getCause() instanceof IOException,
                "readImageCached distinguishes a miss by the cause");
    }

    @Test
    @DisplayName("A null from the loader is refused, not cached as a hole")
    void nullIsRefused() {
        AtomicInteger loads = new AtomicInteger();
        LruCache<String, String> cache = new LruCache<>(4, evicted -> { });

        assertThrows(ExecutionException.class,
                () -> cache.get("k", () -> { loads.incrementAndGet(); return null; }));
        assertThrows(ExecutionException.class,
                () -> cache.get("k", () -> { loads.incrementAndGet(); return null; }));

        assertEquals(0, cache.size(), "nothing may be stored for a null load");
        assertEquals(2, loads.get(), "a refused load must not leave a poisoned entry behind");
    }

    /**
     * Two threads missing on the same key at once.
     *
     * <p>The loader deliberately runs outside the lock, so this race is possible by design -
     * the alternative was serialising every image decode in the app behind one monitor. What
     * must hold is that both callers end up with the SAME value, and that the discarded one is
     * reported to the eviction listener. In the real cache that report is what disposes the
     * loser's native memory; drop it and every such race leaks a pixmap.
     */
    @Test
    @DisplayName("A concurrent miss on one key yields one winner, and the loser is reported")
    void concurrentMissReportsTheLoser() throws Exception {
        List<String> evicted = Collections.synchronizedList(new ArrayList<>());
        LruCache<String, String> cache = new LruCache<>(10, evicted::add);

        CountDownLatch bothInsideLoader = new CountDownLatch(2);
        List<String> returned = Collections.synchronizedList(new ArrayList<>());

        Runnable racer = () -> {
            try {
                String value = cache.get("k", () -> {
                    // Hold here until the other thread is also past the map check, so both
                    // genuinely load the same key at the same time.
                    bothInsideLoader.countDown();
                    bothInsideLoader.await(5, TimeUnit.SECONDS);
                    return new String("loaded");   // distinct instances on purpose
                });
                returned.add(value);
            } catch (Exception failed) {
                throw new RuntimeException(failed);
            }
        };

        Thread one = new Thread(racer, "racer-1");
        Thread two = new Thread(racer, "racer-2");
        one.start();
        two.start();
        one.join(10_000);
        two.join(10_000);

        assertEquals(2, returned.size(), "both threads must have completed");
        assertSame(returned.get(0), returned.get(1),
                "both callers must see the one value the cache kept");
        assertEquals(1, evicted.size(), "the discarded load must be reported exactly once");
        assertNotNull(evicted.get(0));
        for (String value : returned) {
            assertTrue(value != evicted.get(0),
                    "the reported value must be the discarded one, never the one in use");
        }
        assertEquals(1, cache.size());
    }
}
