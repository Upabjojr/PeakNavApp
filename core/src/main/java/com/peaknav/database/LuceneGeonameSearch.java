package com.peaknav.database;

import static com.peaknav.utils.PreferencesManager.P;

import com.peaknav.utils.PreferencesManager;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;

import java.util.ArrayList;
import java.util.List;

public class LuceneGeonameSearch {

    private volatile IndexSearcher indexSearcher = null;
    private final int maxResults = 5;

    public static class GeonameResult {
        public final String name;
        public final String asciiname;
        public final float lat;
        public final float lon;
        public final int population;
        /** ISO country code, or empty when the index predates it / the result is not a place. */
        public final String country;
        /** Metres above sea level for a peak; 0 for places, and for peaks without an ele tag. */
        public final int elevation;
        /** True when this result is a mountain peak rather than a populated place. */
        public final boolean peak;

        public GeonameResult(String name, String asciiname, float lat, float lon, int population) {
            this(name, asciiname, lat, lon, population, "", 0, false);
        }

        public GeonameResult(String name, String asciiname, float lat, float lon, int population,
                             String country) {
            this(name, asciiname, lat, lon, population, country, 0, false);
        }

        public GeonameResult(String name, String asciiname, float lat, float lon, int population,
                             String country, int elevation, boolean peak) {
            this.name = name;
            this.asciiname = asciiname;
            this.lat = lat;
            this.lon = lon;
            this.population = population;
            this.country = (country == null) ? "" : country;
            this.elevation = elevation;
            this.peak = peak;
        }

        /**
         * Label shown in the results list. A search now matches a place's name in any language, so
         * the same query can return several places with the same name in different countries; the
         * country code is what tells them apart.
         */
        public String getFullName() {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(this.name);
            if (this.asciiname != null && !this.name.equals(this.asciiname)) {
                stringBuilder.append(" - ");
                stringBuilder.append(this.asciiname);
            }
            if (!this.country.isEmpty()) {
                stringBuilder.append(" (");
                stringBuilder.append(this.country);
                stringBuilder.append(')');
            }
            // A peak is told apart from a namesake village by its height - "Matterhorn
            // (4478 m)" - the way places are told apart by their country code. In the height
            // the reader chose: the index keeps metres, but someone who set miles and feet is
            // reading feet everywhere else, the peak labels on the map among them. The unit
            // is written as digits and an abbreviation, deliberately: this string has no
            // access to translations, and "m" and "ft" read the same in every language the
            // app speaks.
            if (this.peak && this.elevation > 0) {
                stringBuilder.append(" (")
                        .append(formatElevation(this.elevation,
                                P == null ? PreferencesManager.UnitSystem.METRIC : P.getUnitSystem()))
                        .append(')');
            }
            return stringBuilder.toString();
        }

        /** A peak's height in the reader's units: the index keeps metres, feet are rounded. */
        public static String formatElevation(int elevationMeters, PreferencesManager.UnitSystem units) {
            if (units == PreferencesManager.UnitSystem.IMPERIAL) {
                return Math.round(3.280839895f * elevationMeters) + " ft";
            }
            return elevationMeters + " m";
        }
    }

    public LuceneGeonameSearch() {
        LuceneAssetLoader luceneAssetLoader = new LuceneAssetLoader();
        new Thread(
                () -> this.indexSearcher = luceneAssetLoader.getIndexSearcher()
        ).start();
    }

    /**
     * The places to offer for what is in the search box, best first. Which places those are, and
     * in what order, is {@link GeonameSuggester}'s to decide; this reads them off the index.
     */
    public List<GeonameResult> searchGeoName(String queryName) {
        List<GeonameResult> geonameResults = new ArrayList<>();
        // Anything at all may be typed into a search box, and none of it may crash the app: a
        // half-written name, punctuation Lucene reads as syntax, a query while the index is
        // still being unpacked (indexSearcher null). Every one of those is an empty list.
        try {
            for (Document doc : GeonameSuggester.suggest(indexSearcher, queryName, maxResults)) {
                try {
                    String name = doc.get("name");
                    String asciiName = doc.get("asciiname");
                    float lat = Float.parseFloat(doc.get("lat_store"));
                    float lon = Float.parseFloat(doc.get("lon_store"));
                    int population = Integer.parseInt(doc.get("population_store"));
                    String country = doc.get("country_store"); // absent in indexes built before
                    // Peaks carry a type and an elevation; city documents predate both fields.
                    boolean peak = "peak".equals(doc.get("type_store"));
                    int elevation = 0;
                    String ele = doc.get("ele_store");
                    if (ele != null) {
                        try {
                            elevation = Integer.parseInt(ele);
                        } catch (NumberFormatException leaveZero) {
                        }
                    }

                    geonameResults.add(new GeonameResult(
                            name, asciiName, lat, lon, population, country, elevation, peak));
                } catch (NumberFormatException | NullPointerException ignored) {
                    // Skip index documents missing the stored coordinate fields.
                }
            }
        } catch (Throwable ignored) {
        }

        return geonameResults;
    }
}
