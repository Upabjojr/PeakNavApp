package com.peaknav.database;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.Version;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

public class LuceneGeonameSearch {

    private volatile IndexSearcher indexSearcher = null;
    private final int maxResults = 5;
    private final int maxLevinDist = 2;

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
            // (4478 m)" - the way places are told apart by their country code. Digits and
            // "m", deliberately: this string has no access to translations, and the SI
            // abbreviation reads the same in every interface language the app has.
            if (this.peak && this.elevation > 0) {
                stringBuilder.append(" (");
                stringBuilder.append(this.elevation);
                stringBuilder.append(" m)");
            }
            return stringBuilder.toString();
        }
    }

    public LuceneGeonameSearch() {
        LuceneAssetLoader luceneAssetLoader = new LuceneAssetLoader();
        new Thread(
                () -> this.indexSearcher = luceneAssetLoader.getIndexSearcher()
        ).start();
    }

    public List<GeonameResult> searchGeoName(String queryName) {

        StandardAnalyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        QueryParser parser = new QueryParser(Version.LUCENE_36, "name", analyzer);

        List<GeonameResult> geonameResults = new ArrayList<>();

        Query query;
        try {
            // Escape user input so Lucene special characters can't produce a
            // ParseException / TokenMgrError; catch anything else defensively so
            // arbitrary text typed in the search box can never crash the app.
            query = parser.parse(QueryParser.escape(queryName) + "~0.8");
        } catch (Throwable t) {
            return geonameResults;
        }

        if (indexSearcher == null) {
            return geonameResults;
        }

        try {
            TopDocs topDocs = indexSearcher.search(query, maxResults);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                try {
                    Document doc = indexSearcher.doc(sd.doc);
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
                    // System.out.printf("%s: %f,%f (pop: %d)\n", name, lat, lon, population);
                } catch (NumberFormatException | NullPointerException ignored) {
                    // Skip index documents missing the stored coordinate fields.
                }
            }

        } catch (IOException ignored) {
        }

        return geonameResults;
    }
}
