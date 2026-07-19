package com.peaknav.config;

/**
 * A tiny read/write sink for a registry's serialised (JSON) state. Abstracted so the
 * registries can persist to a file in production yet be driven by an in-memory store in
 * unit tests, without pulling in a libGDX file system.
 */
public interface ProviderStore {

    /** Returns the stored JSON, or null when nothing has been saved yet. */
    String read();

    /** Persists the given JSON, replacing anything previously stored. */
    void write(String json);
}
