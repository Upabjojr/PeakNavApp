import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * Two threads missing on the same key at once - the Guava behavior this class replaces.
     *
     * <p>Guava's {@code Cache.get(key, Callable)} ran ONE load per key and handed its result
     * to every concurrent caller. That is the semantics the tile path was written against:
     * the satellite provider keys many neighbouring tiles to the same zoomed-out parent
     * image, and both tile workers routinely miss on it in the same instant. A cache that
     * loaded twice would decode the same pixmap twice and throw one away - so this test holds
     * the first load open until the second caller is verifiably waiting on it, then checks
     * that only one load ever ran and both callers share its instance.
     */
    @Test
    @DisplayName("A concurrent miss on one key runs the loader once, and both callers share it")
    void concurrentMissLoadsOnce() throws Exception {
        List<String> evicted = Collections.synchronizedList(new ArrayList<>());
        LruCache<String, String> cache = new LruCache<>(10, evicted::add);

        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        List<String> returned = Collections.synchronizedList(new ArrayList<>());

        Runnable caller = () -> {
            try {
                returned.add(cache.get("k", () -> {
                    loads.incrementAndGet();
                    loaderEntered.countDown();
                    releaseLoader.await(5, TimeUnit.SECONDS);
                    return new String("loaded");
                }));
            } catch (Exception failed) {
                throw new RuntimeException(failed);
            }
        };

        Thread first = new Thread(caller, "loader-thread");
        first.start();
        assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "the first caller must be loading");

        // The second caller arrives while the load is in flight, and must block on it
        // rather than load again. Only once it is observably waiting is the loader let go.
        Thread second = new Thread(caller, "waiter-thread");
        second.start();
        waitUntilWaiting(second);
        releaseLoader.countDown();

        first.join(10_000);
        second.join(10_000);

        assertEquals(2, returned.size(), "both threads must have completed");
        assertEquals(1, loads.get(), "one load per key, however many callers miss on it");
        assertSame(returned.get(0), returned.get(1),
                "every caller must see the single loaded instance");
        assertTrue(evicted.isEmpty(), "nothing was discarded, so nothing may be reported");
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("A waiter on someone else's failed load gets the failure, and a retry reloads")
    void waiterSharesTheFailure() throws Exception {
        LruCache<String, String> cache = new LruCache<>(10, evicted -> { });

        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable caller = () -> {
            try {
                cache.get("k", () -> {
                    loads.incrementAndGet();
                    loaderEntered.countDown();
                    releaseLoader.await(5, TimeUnit.SECONDS);
                    throw new IOException("decode failed");
                });
            } catch (ExecutionException expected) {
                failures.add(expected.getCause());
            }
        };

        Thread first = new Thread(caller, "failing-loader");
        first.start();
        assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
        Thread second = new Thread(caller, "failing-waiter");
        second.start();
        waitUntilWaiting(second);
        releaseLoader.countDown();
        first.join(10_000);
        second.join(10_000);

        assertEquals(1, loads.get(), "the waiter shares the load, failed or not");
        assertEquals(2, failures.size(), "the one failure must reach both callers");
        for (Throwable cause : failures) {
            assertTrue(cause instanceof IOException, "each with the loader's own exception");
        }
        assertEquals(0, cache.size(), "a failure must not be cached...");

        cache.get("k", () -> { loads.incrementAndGet(); return "recovered"; });
        assertEquals(2, loads.get(), "...so the next call simply tries again");
    }

    /** Spins until the thread parks in WAITING/TIMED_WAITING, i.e. is blocked on the load. */
    private static void waitUntilWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("second caller never blocked on the in-flight load");
    }
}
