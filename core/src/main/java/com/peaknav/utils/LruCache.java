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
 * serialise every tile worker in the app behind one decode. The cost of letting go is that
 * two threads can miss on the same key at once; the second one to finish finds the winner's
 * value already in the map, and hands its own to the eviction listener. That is the same path
 * an evicted value takes, so a loser is disposed of correctly rather than leaked.
 */
public class LruCache<K, V> {

    /** Produces a value on a miss. May throw; the throwable reaches the caller wrapped. */
    public interface Loader<V> {
        V load() throws Exception;
    }

    /** Told about every value the cache stops holding, by eviction or by losing a race. */
    public interface EvictionListener<V> {
        void onEvicted(V value);
    }

    private final int maximumSize;
    private final EvictionListener<V> evictionListener;

    /** accessOrder = true, so iteration starts at the least recently used entry. */
    private final LinkedHashMap<K, V> entries;

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
        synchronized (this) {
            V existing = entries.get(key);
            if (existing != null) {
                return existing;
            }
        }

        V loaded;
        try {
            loaded = loader.load();
        } catch (Exception failed) {
            throw new ExecutionException(failed);
        }
        if (loaded == null) {
            throw new ExecutionException(new IllegalStateException("loader returned null for " + key));
        }

        V lost = null;
        List<V> evicted;
        synchronized (this) {
            V winner = entries.get(key);
            if (winner != null) {
                // Another thread loaded the same key while this one was reading it.
                lost = loaded;
                loaded = winner;
                evicted = null;
            } else {
                entries.put(key, loaded);
                evicted = evictDownToSize();
            }
        }

        // Outside the lock: the listener disposes native memory and takes its own locks.
        if (lost != null) {
            evictionListener.onEvicted(lost);
        }
        if (evicted != null) {
            for (V value : evicted) {
                evictionListener.onEvicted(value);
            }
        }
        return loaded;
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
