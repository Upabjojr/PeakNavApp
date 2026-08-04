import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.tools.GeonamesIndexBuilder;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Covers the index builder, which lives in {@code com.peaknav.tools} and is run from Gradle:
 *
 * <pre>./gradlew :core:buildGeonamesIndex --args="cities500.txt alternateNamesV2.txt out_dir"</pre>
 *
 * <p>The search test builds its own small index in a temporary directory and searches that. It
 * used to open {@code geonames_index.362} in the working directory if that happened to exist, so
 * it passed, silently skipped or failed depending on what an earlier run had left behind - a
 * directory holding nothing but a stale {@code write.lock} failed it outright.
 */
class TestLuceneGeonames {

    /** A row of a GeoNames cities dump, carrying the columns the builder reads. */
    private static String cityRow(String id, String name, String ascii, String lat, String lon,
                                  String country, String population) {
        List<String> f = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            f.add("");
        }
        f.set(0, id);
        f.set(1, name);
        f.set(2, ascii);
        f.set(4, lat);
        f.set(5, lon);
        f.set(8, country);
        f.set(14, population);
        return String.join("\t", f);
    }

    /** A row of the alternate-names dump: id, place, language, name, preferred, ..., historic. */
    private static String altRow(String id, String place, String language, String name,
                                 boolean preferred) {
        return String.join("\t", id, place, language, name, preferred ? "1" : "", "", "", "");
    }

    @Test
    @DisplayName("the builder writes an index that the app's kind of search can read")
    void buildsAndSearchesAnIndex(@TempDir Path dir) throws IOException, ParseException {
        Path cities = dir.resolve("cities.txt");
        Files.write(cities, String.join("\n",
                cityRow("2643743", "London", "London", "51.50853", "-0.12574", "GB", "8961989"),
                cityRow("3169070", "Roma", "Roma", "41.89193", "12.51133", "IT", "2318895"),
                cityRow("2661604", "Zürich", "Zurich", "47.36667", "8.55", "CH", "341730")
        ).getBytes(StandardCharsets.UTF_8));

        Path alternates = dir.resolve("alternates.txt");
        Files.write(alternates, String.join("\n",
                altRow("1", "3169070", "it", "Roma", true),
                altRow("2", "3169070", "en", "Rome", false),
                altRow("3", "2643743", "it", "Londra", false)
        ).getBytes(StandardCharsets.UTF_8));

        Path index = dir.resolve("index");
        GeonamesIndexBuilder.runner(cities.toString(), alternates.toString(), index.toString());

        try (Stream<Path> files = Files.list(index)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("segments")),
                    "a real index should have been written, not just a lock file");
        }

        FSDirectory directory = FSDirectory.open(new File(index.toString()));
        IndexReader reader = IndexReader.open(directory);
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser(Version.LUCENE_36, "name",
                    new StandardAnalyzer(Version.LUCENE_36));

            assertEquals("London", firstHit(searcher, parser.parse("london~0.8")),
                    "a fuzzy query on the display name should find the place");
            // Why alternate names are indexed at all: a foreign form and the local form both hit
            // the same document, and the name read back is the display name, not the query.
            assertEquals("Roma", firstHit(searcher, parser.parse("Rome")),
                    "an English exonym must find the place and report its own name");
            assertEquals("London", firstHit(searcher, parser.parse("Londra")),
                    "a name in another language must find the place");
            assertEquals("Zürich", firstHit(searcher, parser.parse("Zurich")),
                    "an accent-free spelling must find the accented place");

            TopDocs hits = searcher.search(parser.parse("london~0.8"), 1);
            Document doc = searcher.doc(hits.scoreDocs[0].doc);
            assertEquals("51.50853", doc.get("lat_store"), "coordinates must be stored");
            assertEquals("-0.12574", doc.get("lon_store"));
            assertEquals("8961989", doc.get("population_store"));
        } finally {
            reader.close();
            directory.close();
        }
    }

    @Test
    @DisplayName("peaks appended to a city index are found, ranked below namesake cities")
    void appendsPeaks(@TempDir Path dir) throws IOException, ParseException {
        // A city index first, exactly as the builder makes one.
        Path cities = dir.resolve("cities.txt");
        Files.write(cities, String.join("\n",
                cityRow("3169070", "Roma", "Roma", "41.89193", "12.51133", "IT", "2318895"),
                // The namesake trap: a real town called Cervinia-like name shadowing a peak.
                cityRow("2661604", "Zermatt", "Zermatt", "46.01936", "7.74861", "CH", "5771")
        ).getBytes(StandardCharsets.UTF_8));
        Path alternates = dir.resolve("alternates.txt");
        Files.write(alternates, new byte[0]);
        Path index = dir.resolve("index");
        GeonamesIndexBuilder.runner(cities.toString(), alternates.toString(), index.toString());

        // Then the peaks file: the Matterhorn with its Italian name, and Zermatt's own
        // Gornergrat to prove low peaks survive when they carry a Wikipedia article.
        Path peaks = dir.resolve("peaks.tsv");
        Files.write(peaks, String.join("\n",
                "45.97640\t7.65860\t4478\t1\tMatterhorn\tCervino|Mont Cervin",
                "46.01000\t7.78000\t3135\t0\tGornergrat\t",
                "45.90000\t7.60000\t900\t0\tNamelessHill\t"
        ).getBytes(StandardCharsets.UTF_8));

        // minEle 1500 drops the hill; the appender rewrites filelist.txt as it finishes.
        com.peaknav.tools.PeakIndexAppender.run(peaks.toString(), index.toString(), 1500);

        assertTrue(Files.exists(index.resolve("filelist.txt")),
                "the loader copies what filelist.txt names, so the appender must write it");
        List<String> listed = Files.readAllLines(index.resolve("filelist.txt"));
        try (Stream<Path> files = Files.list(index)) {
            files.filter(p -> !p.getFileName().toString().equals("filelist.txt"))
                    .filter(p -> !p.getFileName().toString().equals("write.lock"))
                    .forEach(p -> assertTrue(listed.contains(p.getFileName().toString()),
                            p.getFileName() + " is on disk but not in filelist.txt"));
        }

        FSDirectory directory = FSDirectory.open(new File(index.toString()));
        IndexReader reader = IndexReader.open(directory);
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser(Version.LUCENE_36, "name",
                    new StandardAnalyzer(Version.LUCENE_36));

            assertEquals("Matterhorn", firstHit(searcher, parser.parse("matterhorn~0.8")));
            assertEquals("Matterhorn", firstHit(searcher, parser.parse("Cervino")),
                    "the Italian name of the mountain must find it");

            TopDocs hits = searcher.search(parser.parse("matterhorn"), 1);
            Document doc = searcher.doc(hits.scoreDocs[0].doc);
            assertEquals("peak", doc.get("type_store"));
            assertEquals("4478", doc.get("ele_store"));
            assertEquals("0", doc.get("population_store"),
                    "the search side parses population unconditionally, so it must be present");

            assertEquals("Gornergrat", firstHit(searcher, parser.parse("gornergrat~0.8")),
                    "a peak above the elevation floor is kept even without Wikipedia");
            TopDocs none = searcher.search(parser.parse("NamelessHill"), 1);
            assertEquals(0, none.totalHits, "a low peak with no article is not worth its bytes");

            // The town and its mountains coexist; the town outranks them on its own name.
            assertEquals("Zermatt", firstHit(searcher, parser.parse("zermatt~0.8")),
                    "a populated place must outrank peaks for its own name");
        } finally {
            reader.close();
            directory.close();
        }
    }

    @Test
    @DisplayName("a peak's rank grows with height, is stepped up by fame, below big cities")
    void peakBoosts() {
        float hill = com.peaknav.tools.PeakIndexAppender.elevationBoost(300, false);
        float alp = com.peaknav.tools.PeakIndexAppender.elevationBoost(4478, false);
        float famousAlp = com.peaknav.tools.PeakIndexAppender.elevationBoost(4478, true);
        assertTrue(alp > hill, "higher must rank higher");
        assertTrue(famousAlp > alp, "a Wikipedia article must add rank");
        // The deliberate offset between the scales: the greatest peak stays below a big
        // city's boost, so namesake collisions resolve to the city.
        assertTrue(famousAlp < GeonamesIndexBuilder.populationBoost("2318895"),
                "no mountain outranks a metropolis that shares its name");
    }

    private static String firstHit(IndexSearcher searcher, Query query) throws IOException {
        TopDocs hits = searcher.search(query, 5);
        assertTrue(hits.scoreDocs.length > 0, "expected a hit for " + query);
        return searcher.doc(hits.scoreDocs[0].doc).get("name");
    }

    @Test
    @DisplayName("accents are stripped so a name can be typed on any keyboard")
    void stripsAccents() {
        assertEquals("Zurich", GeonamesIndexBuilder.stripAccents("Zürich"));
        assertEquals("Perucko", GeonamesIndexBuilder.stripAccents("Perućko"));
        assertEquals("Malmo", GeonamesIndexBuilder.stripAccents("Malmö"));
        // Letters NFD does not decompose - each has a conventional ASCII spelling that an
        // ASCII keyboard will actually type.
        assertEquals("Grossglockner", GeonamesIndexBuilder.stripAccents("Großglockner"));
        assertEquals("Thorsmork", GeonamesIndexBuilder.stripAccents("Þórsmörk"));
        assertEquals("Snaefell", GeonamesIndexBuilder.stripAccents("Snæfell"));
        assertEquals("Solvorn", GeonamesIndexBuilder.stripAccents("Sølvorn"));
        assertEquals("Lodz", GeonamesIndexBuilder.stripAccents("Łódź"));
    }

    @Test
    @DisplayName("aliases contribute only their new words - a repeated word would inflate tf")
    void indexesOnlyNovelAliasTokens() {
        // "Matterhorn od Žijeva": its ASCII alias repeats "Matterhorn" and "od" but
        // brings "Zijeva". Indexing the alias whole put "matterhorn" in the field twice,
        // and Lucene's term frequency - the square root of two - outweighed a
        // two-kilometre difference in elevation boost: the Montenegrin summit ranked
        // above the Matterhorn for the query "matterhorn". Only the new word may be
        // indexed.
        org.apache.lucene.document.Document ascii =
                com.peaknav.tools.PeakIndexAppender.peakDocument("Matterhorn od Žijeva",
                        new String[0], "42.6", "19.6", 2130, true);
        org.apache.lucene.document.Fieldable[] fields = ascii.getFields("name");
        assertEquals(2, fields.length, "display name plus the novel part of the ASCII form");
        assertEquals("Zijeva", fields[1].stringValue(),
                "only the word not already searchable, not the whole alias");

        // An alias that is a strict repeat contributes nothing and is dropped whole.
        org.apache.lucene.document.Document repeat =
                com.peaknav.tools.PeakIndexAppender.peakDocument("Matterhorn",
                        new String[]{"Matterhorn"}, "45.98", "7.66", 4478, true);
        assertEquals(1, repeat.getFields("name").length);

        // A genuinely different name is indexed; the ASCII spelling of a sharp-s name
        // is a different term to the analyzer and must survive.
        org.apache.lucene.document.Document cervino =
                com.peaknav.tools.PeakIndexAppender.peakDocument("Matterhorn",
                        new String[]{"Cervino"}, "45.98", "7.66", 4478, true);
        assertEquals(2, cervino.getFields("name").length);
        org.apache.lucene.document.Document sharpS =
                com.peaknav.tools.PeakIndexAppender.peakDocument("Großglockner",
                        new String[0], "47.07", "12.69", 3798, true);
        assertEquals(2, sharpS.getFields("name").length,
                "the ASCII spelling is a new term and must be indexed");
        assertEquals("Grossglockner", sharpS.getFields("name")[1].stringValue());
    }

    @Test
    @DisplayName("name selection prefers the local form and drops duplicates")
    void selectsNames() {
        GeonamesIndexBuilder.Place p = new GeonamesIndexBuilder.Place();
        p.name = "Venice";
        p.asciiName = "Venice";
        p.preferred.add("Venezia");
        p.translated.add("Venedig");
        p.translated.add("Venice"); // same as the display name: must not be indexed twice

        List<String> names = GeonamesIndexBuilder.selectNames(p);
        assertTrue(names.contains("Venezia"), "the local name must be searchable");
        assertEquals("Venezia", names.get(0), "preferred names come first");
        assertFalse(names.contains("Venice"),
                "the display name is already indexed as the first value of the field");
    }
}
