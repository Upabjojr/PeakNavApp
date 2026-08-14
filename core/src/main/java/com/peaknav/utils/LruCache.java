package com.peaknav.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * A bounded, least-recently-used cache with a load-on-miss call and an eviction callback.
 *
 * <p>This exists because Guava's {@code CacheBuilder} cannot run on iOS. RoboVM's runtime is
 * derived from Android's libcore and contains no {@code java.util.function} package at all;
 * Guava's {@code Equivalence} implements {@code BiPredicate}, so the very first
 * {@code CacheBuilder.build()} died with {@code NoClassDefFoundError:
 * java.util.function.BiPredicate} before the app drew a frame. Guava was the only thing
 * {@code core} used it for - two caches, one of which was dead code - so the dependency went
 * rather than the platform.
 *
 * <p>The semantics {@link PeakNavUtils#readImageCached} relies on are kept exactly: a miss
 * runs the loader, a loader that throws surfaces as {@link ExecutionException}, and anything
 * pushed out by the size bound is handed to the eviction listener rather than dropped - which
 * for pixmaps is what gets their native memory freed.
 *
 * <h2>Concurrency</h2>
 *
 * <p>The map is guarded by this object's monitor, but <b>the loader runs outside it</b>.
 * Loading decodes an image from disk, and holding a single global lock across that would
 * serialise every tile worker in the app behind one decode.
 *
 * <p>Concurrent misses on the same key are deduplicated, exactly as Guava's
 * {@code Cache.get(key, Callable)} did it: the first thread in becomes the loader, and every
 * other thread arriving for that key while the load is in flight waits for its result instead
 * of decoding the same file again. This matters on the tile path, where the satellite
 * provider keys many neighbouring tiles to one zoomed-out parent image and both tile workers
 * routinely miss on it in the same instant - without the dedup, each decoded the full pixmap
 * and one was thrown away. A load that fails is delivered as {@link ExecutionException} to
 * the loading thread and every waiter alike, and nothing is cached, so the next call simply
 * tries again. Distinct keys never wait on each other.
 */
public class LruCache<K, V> {

    /** Produces a value on a miss. May throw; the throwable reaches the caller wrapped. */
    public interface Loader<V> {
        V load() throws Exception;
    }

    /** Told about every value the cache stops holding. */
    public interface EvictionListener<V> {
        void onEvicted(V value);
    }

    /**
     * One in-flight load, shared by the thread running the loader and every thread waiting
     * on the same key. A hand-rolled latch rather than {@code CompletableFuture}, which is
     * Java 8 API that RoboVM's runtime does not have; {@code wait}/{@code notifyAll} exist
     * everywhere.
     */
    private static final class InFlight<V> {
        V value;
        Throwable failure;
        boolean done;
    }

    private final int maximumSize;
    private final EvictionListener<V> evictionListener;

    /** accessOrder = true, so iteration starts at the least recently used entry. */
    private final LinkedHashMap<K, V> entries;

    /** Loads currently running, so a second miss on the same key waits instead of reloading.
     *  Guarded by this object's monitor, like {@link #entries}. */
    private final Map<K, InFlight<V>> loading = new java.util.HashMap<K, InFlight<V>>();

    public LruCache(int maximumSize, EvictionListener<V> evictionListener) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1");
        }
        this.maximumSize = maximumSize;
        this.evictionListener = evictionListener;
        this.entries = new LinkedHashMap<K, V>(16, 0.75f, true);
    }

    /**
     * The value for {@code key}, loading and storing it if it is not already held.
     *
     * @throws ExecutionException if the loader threw, or returned null - a null would be
     *         indistinguishable from a miss on the next call, so it is refused here rather
     *         than caching a hole. {@code readImageCached} depends on this: it signals
     *         "no such file" by throwing out of the loader.
     */
    public V get(K key, Loader<V> loader) throws ExecutionException {
        InFlight<V> flight;
        boolean thisThreadLoads;
        synchronized (this) {
            V existing = entries.get(key);
            if (existing != null) {
                return existing;
            }
            flight = loading.get(key);
            thisThreadLoads = flight == null;
            if (thisThreadLoads) {
                flight = new InFlight<V>();
                loading.put(key, flight);
            }
        }
        if (!thisThreadLoads) {
            return await(key, flight);
        }

        // The loader itself runs with no lock held - see the class comment.
        V loaded = null;
        Throwable failure = null;
        try {
            loaded = loader.load();
            if (loaded == null) {
                // A null would be indistinguishable from a miss on the next call and be
                // reloaded forever, so it is refused rather than cached as a hole.
                failure = new IllegalStateException("loader returned null for " + key);
            }
        } catch (Exception thrown) {
            failure = thrown;
        }

        List<V> evicted = null;
        synchronized (this) {
            loading.remove(key);
            if (failure == null) {
                entries.put(key, loaded);
                evicted = evictDownToSize();
            }
        }
        synchronized (flight) {
            flight.value = loaded;
            flight.failure = failure;
            flight.done = true;
            flight.notifyAll();
        }

        // Outside the lock: the listener disposes native memory and takes its own locks.
        if (evicted != null) {
            for (V value : evicted) {
                evictionListener.onEvicted(value);
            }
        }
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        return loaded;
    }

    /**
     * Waits for another thread's in-flight load of {@code key} and shares its outcome,
     * failure included. The wait is uninterruptible with the interrupt flag restored, which
     * is how Guava's {@code Cache.get} behaved for a thread waiting on someone else's load.
     */
    private V await(K key, InFlight<V> flight) throws ExecutionException {
        boolean interrupted = false;
        synchronized (flight) {
            while (!flight.done) {
                try {
                    flight.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (flight.failure != null) {
            throw new ExecutionException(flight.failure);
        }
        // Touch the entry so the waiter counts as a use for LRU order. It may already have
        // been evicted again; the flight's value is still the right thing to return then.
        synchronized (this) {
            V current = entries.get(key);
            if (current != null) {
                return current;
            }
        }
        return flight.value;
    }

    /** Drops least-recently-used entries until the bound holds. Caller holds the monitor. */
    private List<V> evictDownToSize() {
        if (entries.size() <= maximumSize) {
            return null;
        }
        List<V> evicted = new ArrayList<V>();
        Iterator<Map.Entry<K, V>> oldestFirst = entries.entrySet().iterator();
        while (entries.size() > maximumSize && oldestFirst.hasNext()) {
            evicted.add(oldestFirst.next().getValue());
            oldestFirst.remove();
        }
        return evicted;
    }

    /** How many entries are held. For tests and diagnostics. */
    public synchronized int size() {
        return entries.size();
    }
}
