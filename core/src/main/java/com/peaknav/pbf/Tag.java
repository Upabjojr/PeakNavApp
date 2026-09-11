package com.peaknav.pbf;

/** An OpenStreetMap tag, as read from the map data: a key and its value. Immutable. */
public final class Tag {

    public final String key;
    public final String value;

    public Tag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Tag)) {
            return false;
        }
        Tag t = (Tag) o;
        return (key == null ? t.key == null : key.equals(t.key))
                && (value == null ? t.value == null : value.equals(t.value));
    }

    @Override
    public int hashCode() {
        return 31 * (key == null ? 0 : key.hashCode()) + (value == null ? 0 : value.hashCode());
    }

    @Override
    public String toString() {
        return "key=" + key + ", value=" + value;
    }
}
