package com.peaknav.database;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryParser.ParseException;
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

        public GeonameResult(String name, String asciiname, float lat, float lon, int population) {
            this.name = name;
            this.asciiname = asciiname;
            this.lat = lat;
            this.lon = lon;
            this.population = population;
        }

        public String getFullName() {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(this.name);
            if (!this.name.equals(this.asciiname)) {
                stringBuilder.append(" - ");
                stringBuilder.append(this.asciiname);
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

        Query query = null;
        try {
            query = parser.parse(queryName + "~0.8");
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }

        List<GeonameResult> geonameResults = new ArrayList<>();

        if (indexSearcher == null) {
            return geonameResults;
        }

        try {
            TopDocs topDocs = indexSearcher.search(query, maxResults);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = indexSearcher.doc(sd.doc);
                String name = doc.get("name");
                String asciiName = doc.get("asciiname");
                float lat = Float.parseFloat(doc.get("lat_store"));
                float lon = Float.parseFloat(doc.get("lon_store"));
                int population = Integer.parseInt(doc.get("population_store"));

                geonameResults.add(new GeonameResult(name, asciiName, lat, lon, population));
                // System.out.printf("%s: %f,%f (pop: %d)\n", name, lat, lon, population);
            }

        } catch (IOException ignored) {
        }

        return geonameResults;
    }
}
