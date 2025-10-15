import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;

class GeoNamesIndexer {

    public static void runner() throws IOException {
        String geonamesFile = "cities500.txt"; // your GeoNames CSV
        String indexDir = "geonames_index.362";    // output Lucene index folder

        Directory dir = FSDirectory.open(new File(indexDir));
        Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_36, analyzer);
        IndexWriter writer = new IndexWriter(dir, config);

        try (BufferedReader br = new BufferedReader(new FileReader(geonamesFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                String name = parts[1];
                String asciiname = parts[2];
                float lat = Float.parseFloat(parts[4]);
                float lon = Float.parseFloat(parts[5]);
                int population = Integer.parseInt(parts[14]);

                Document doc = new Document();
                doc.add(new Field("name", name, Field.Store.YES, Field.Index.ANALYZED));
                doc.add(new Field("asciiname", asciiname, Field.Store.YES, Field.Index.ANALYZED));
                doc.add(new Field("lat_store", ""+lat, Field.Store.YES, Field.Index.NO));
                doc.add(new Field("lon_store", ""+lon, Field.Store.YES, Field.Index.NO));
                doc.add(new Field("population_store", ""+population, Field.Store.YES, Field.Index.NO));

                writer.addDocument(doc);
            }
        }

        writer.close();
        System.out.println("Index created at: " + indexDir);

    }
}

public class TestLuceneGeonames {

    @Test
    public void testLuceneBuildIndex() {
        GeoNamesIndexer geoNamesIndexer = new GeoNamesIndexer();
        try {
            GeoNamesIndexer.runner();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Lucene index built!");
    }

    @Test
    public void testLuceneSearch() throws IOException, ParseException {

        String indexPath = "geonames_index.362";
        FSDirectory directory = FSDirectory.open(new File(indexPath));
        IndexReader reader = IndexReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);

        StandardAnalyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        QueryParser parser = new QueryParser(Version.LUCENE_36,"name", analyzer);

        Query query;

        query = parser.parse("london~0.8");


        int maxResults = 10;
        TopDocs topDocs;

        topDocs = searcher.search(query, maxResults);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.doc(sd.doc);
            String cityName = doc.get("name");
            float lat = Float.parseFloat(doc.get("lat_store"));
            float lon = Float.parseFloat(doc.get("lon_store"));
            int population = Integer.parseInt(doc.get("population_store"));

            System.out.printf("%s: %f,%f (pop: %d)\n", cityName, lat, lon, population);
        }

        System.out.println(query);

        reader.close();
        directory.close();

    }
}
