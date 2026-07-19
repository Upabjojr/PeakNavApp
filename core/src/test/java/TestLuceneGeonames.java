import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.DefaultSimilarity;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the place-search index from the GeoNames dumps.
 *
 * <p>Two files are needed, both from {@code https://download.geonames.org/export/dump/}:
 * {@code cities500.txt} (the places) and {@code alternateNamesV2.txt} (their names in other
 * languages). Point {@link #CITIES_FILE} and {@link #ALTERNATE_NAMES_FILE} at them and run
 * {@link #main}.
 *
 * <p>The index used to hold nothing but each place's own GeoNames name, which is often the English
 * exonym — so "Venice" was found and "Venezia" was not, and the same for every place whose local
 * name differs from the international one. It now also carries a selection of that place's names
 * in other languages, plus accent-free forms so a name can be typed on any keyboard.
 *
 * <p>The whole index ships inside the app, so its size is capped at a few tens of MB. Venice alone
 * has 73 alternate names in the dump, so taking all of them for 224k places is not an option. Two
 * things keep it small. Every name of a place is another value of the same {@code name} field on
 * the SAME document, so the stored data — coordinates, population, country — is paid for once per
 * place rather than once per name. And only the names likely to be typed are kept, chosen by
 * {@link #selectNames}.
 *
 * <p>Because the extra names live in {@code name}, and the display name is that field's first and
 * only stored value, {@code LuceneGeonameSearch} needs no change to find them: it queries
 * {@code name} and reads the display name straight back.
 */
class GeoNamesIndexer {

    static final String CITIES_FILE = "cities500.txt";
    static final String ALTERNATE_NAMES_FILE = "alternateNamesV2.txt";
    static final String INDEX_DIR = "geonames_index.362";

    /**
     * Names in these languages are always kept: the languages the app itself is translated into,
     * so whoever reads the app in their language can search in it too.
     */
    private static final Set<String> KEEP_LANGUAGES = new HashSet<>(Arrays.asList(
            "en", "de", "es", "fr", "it", "no", "pt"));

    /**
     * Entries in the dump whose "language" is not a language at all but a link or an identifier —
     * Wikipedia URLs, Wikidata ids, postal and airport codes. None of them is a name.
     */
    private static final Set<String> NON_LANGUAGE_TAGS = new HashSet<>(Arrays.asList(
            "link", "wkdt", "post", "iata", "icao", "faac", "fr_1793", "abbr", "phon", "piny",
            "unlc", "tcid"));

    /**
     * Most a single place may contribute. Names are taken in priority order (see
     * {@link #selectNames}), so the cap drops the least useful ones — without it a handful of
     * world cities would cost as much as thousands of ordinary towns.
     */
    private static final int MAX_NAMES_PER_PLACE = 6;

    /** One place from {@code cities500.txt}, with the names gathered for it. */
    static final class Place {
        String name;
        String asciiName;
        String country;
        String lat;
        String lon;
        String population;
        /** Names GeoNames marks as preferred in their language: the local form lives here. */
        final List<String> preferred = new ArrayList<>(2);
        /** Names in the app's languages, kept when there is room. */
        final List<String> translated = new ArrayList<>(4);
    }

    public static void main(String[] args) throws IOException {
        String cities = args.length > 0 ? args[0] : CITIES_FILE;
        String alternates = args.length > 1 ? args[1] : ALTERNATE_NAMES_FILE;
        String out = args.length > 2 ? args[2] : INDEX_DIR;
        runner(cities, alternates, out);
    }

    public static void runner() throws IOException {
        runner(CITIES_FILE, ALTERNATE_NAMES_FILE, INDEX_DIR);
    }

    public static void runner(String citiesFile, String alternateNamesFile, String indexDir)
            throws IOException {
        System.out.println("reading places from " + citiesFile);
        Map<String, Place> places = readPlaces(citiesFile);
        System.out.println("  " + places.size() + " places");

        File alternates = new File(alternateNamesFile);
        if (alternates.exists()) {
            System.out.println("reading alternate names from " + alternateNamesFile);
            long kept = readAlternateNames(alternates, places);
            System.out.println("  kept " + kept + " names");
        } else {
            // Still produces a working index, just without the local-language names.
            System.out.println("no " + alternateNamesFile + "; building without alternate names");
        }

        System.out.println("writing index to " + indexDir);
        write(places, indexDir);
    }

    private static Map<String, Place> readPlaces(String citiesFile) throws IOException {
        Map<String, Place> places = new HashMap<>(300_000);
        try (BufferedReader br = reader(new File(citiesFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 15) {
                    continue;
                }
                Place p = new Place();
                p.name = parts[1];
                p.asciiName = parts[2];
                p.lat = trimCoordinate(parts[4]);
                p.lon = trimCoordinate(parts[5]);
                p.country = parts[8];
                p.population = parts[14];
                places.put(parts[0], p);
            }
        }
        return places;
    }

    /**
     * Streams the alternate-name dump — it is far too large to hold in memory — keeping only the
     * rows belonging to a place already selected, and only those worth indexing.
     */
    private static long readAlternateNames(File file, Map<String, Place> places) throws IOException {
        long kept = 0;
        try (BufferedReader br = reader(file)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length < 5) {
                    continue;
                }
                Place p = places.get(c[1]);
                if (p == null) {
                    continue;
                }
                String language = c[2];
                String value = c[3];
                if (value.isEmpty() || NON_LANGUAGE_TAGS.contains(language)) {
                    continue;
                }
                // Historic names would crowd out the current one under the per-place cap.
                if (c.length > 7 && "1".equals(c[7])) {
                    continue;
                }
                if ("1".equals(c[4])) {
                    if (p.preferred.size() < MAX_NAMES_PER_PLACE) {
                        p.preferred.add(value);
                        kept++;
                    }
                } else if (KEEP_LANGUAGES.contains(language)
                        && p.translated.size() < MAX_NAMES_PER_PLACE) {
                    p.translated.add(value);
                    kept++;
                }
            }
        }
        return kept;
    }

    /**
     * The names indexed for a place besides its display name, in priority order and without
     * duplicates:
     * <ol>
     *   <li>names GeoNames marks preferred — this is what brings in "Venezia" for Venice;</li>
     *   <li>names in the app's languages;</li>
     *   <li>the ASCII name, and accent-free copies of everything above, so "Zurich" finds
     *       "Zürich" and "Perucko" finds "Perućko".</li>
     * </ol>
     */
    static List<String> selectNames(Place p) {
        Set<String> out = new LinkedHashSet<>();
        String display = p.name.toLowerCase(Locale.ROOT);

        for (String s : p.preferred) {
            add(out, s, display);
        }
        for (String s : p.translated) {
            if (out.size() >= MAX_NAMES_PER_PLACE) {
                break;
            }
            add(out, s, display);
        }
        add(out, p.asciiName, display);

        List<String> folded = new ArrayList<>(out.size() + 1);
        folded.add(stripAccents(p.name));
        for (String s : out) {
            folded.add(stripAccents(s));
        }
        for (String s : folded) {
            add(out, s, display);
        }
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String value, String displayLower) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).equals(displayLower)) {
            return;
        }
        out.add(trimmed);
    }

    static String stripAccents(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void write(Map<String, Place> places, String indexDir) throws IOException {
        Directory dir = FSDirectory.open(new File(indexDir));
        Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_36, analyzer);
        // Score without Lucene's usual short-field bonus. It divides by the square root of the
        // number of terms in the field, which here would punish a place for the very thing that
        // makes it findable: Rome carries a dozen names and a hamlet called Rome carries one, so
        // the hamlet outranked the city. Dropping the length term leaves the norm carrying only
        // the population boost, which is the order actually wanted. Only the index is affected —
        // the search side reads the norm as it finds it.
        config.setSimilarity(new DefaultSimilarity() {
            @Override
            public float computeNorm(String field, FieldInvertState state) {
                return state.getBoost();
            }
        });
        IndexWriter writer = new IndexWriter(dir, config);

        long names = 0;
        for (Place p : places.values()) {
            Document doc = new Document();

            // Rank by how big the place is. Without this the search is scored on text alone, so
            // "Rome" offered a handful of American villages before the Italian capital, and every
            // extra name a place carries lengthens its name field and pushes it further down. The
            // boost is logarithmic — a city a thousand times larger should rank above a village,
            // but not a thousand times above it — and it rides on the field norms, so the search
            // side gets the better order without knowing anything about it.
            doc.setBoost(populationBoost(p.population));

            // First value of "name" and the only stored one, so doc.get("name") is the display name.
            doc.add(new Field("name", p.name, Field.Store.YES, Field.Index.ANALYZED));
            for (String other : selectNames(p)) {
                doc.add(new Field("name", other, Field.Store.NO, Field.Index.ANALYZED));
                names++;
            }
            // Stored for display only: its searchable copy is among the names above, so indexing
            // it again would pay for the same terms twice.
            doc.add(new Field("asciiname", p.asciiName, Field.Store.YES, Field.Index.NO));
            doc.add(new Field("country_store", p.country, Field.Store.YES, Field.Index.NO));
            doc.add(new Field("lat_store", p.lat, Field.Store.YES, Field.Index.NO));
            doc.add(new Field("lon_store", p.lon, Field.Store.YES, Field.Index.NO));
            doc.add(new Field("population_store", p.population, Field.Store.YES, Field.Index.NO));

            writer.addDocument(doc);
        }
        System.out.println("  " + places.size() + " documents, " + names + " extra names");

        // A single segment is smaller on disk and quicker to open on a phone.
        writer.forceMerge(1);
        writer.close();
        System.out.println("index written to " + indexDir);
    }

    /**
     * Score multiplier for a place of this size. Lucene stores norms in a single byte, so only the
     * broad magnitude survives — which is all that is wanted here: villages, towns, cities and
     * capitals should fall into separate bands, and places within a band should still be ordered
     * by how well the name matched.
     */
    static float populationBoost(String population) {
        long pop;
        try {
            pop = Long.parseLong(population.trim());
        } catch (RuntimeException e) {
            pop = 0;
        }
        if (pop < 0) {
            pop = 0;
        }
        return 1f + (float) Math.log10(1d + pop);
    }

    /** Five decimals is about a metre; further digits would only cost bytes. */
    private static String trimCoordinate(String raw) {
        try {
            return String.format(Locale.ROOT, "%.5f", Float.parseFloat(raw.trim()));
        } catch (RuntimeException e) {
            return "0";
        }
    }

    private static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 20);
    }
}

public class TestLuceneGeonames {

    /**
     * Rebuilds the index. Disabled by default: it needs the GeoNames dumps next to the working
     * directory and takes minutes. Run {@link GeoNamesIndexer#main} directly instead.
     */
    // @Test
    public void testLuceneBuildIndex() {
        try {
            GeoNamesIndexer.runner();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Lucene index built!");
    }

    @Test
    public void testAccentStripping() {
        org.junit.jupiter.api.Assertions.assertEquals("Zurich", GeoNamesIndexer.stripAccents("Zürich"));
        org.junit.jupiter.api.Assertions.assertEquals("Perucko", GeoNamesIndexer.stripAccents("Perućko"));
        org.junit.jupiter.api.Assertions.assertEquals("Malmo", GeoNamesIndexer.stripAccents("Malmö"));
    }

    @Test
    public void testSelectNamesPrefersLocalFormAndDropsDuplicates() {
        GeoNamesIndexer.Place p = new GeoNamesIndexer.Place();
        p.name = "Venice";
        p.asciiName = "Venice";
        p.preferred.add("Venezia");
        p.translated.add("Venedig");
        p.translated.add("Venice"); // same as the display name: must not be indexed twice

        List<String> names = GeoNamesIndexer.selectNames(p);
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("Venezia"),
                "the local name must be searchable");
        org.junit.jupiter.api.Assertions.assertEquals("Venezia", names.get(0),
                "preferred names come first");
        org.junit.jupiter.api.Assertions.assertFalse(names.contains("Venice"),
                "the display name is already indexed as the first value of the field");
    }

    @Test
    public void testLuceneSearch() throws IOException, ParseException {
        File indexPath = new File(GeoNamesIndexer.INDEX_DIR);
        if (!indexPath.isDirectory()) {
            return; // nothing built here; the app ships the index
        }
        FSDirectory directory = FSDirectory.open(indexPath);
        IndexReader reader = IndexReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);

        StandardAnalyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        QueryParser parser = new QueryParser(Version.LUCENE_36, "name", analyzer);
        Query query = parser.parse("london~0.8");

        TopDocs topDocs = searcher.search(query, 10);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.doc(sd.doc);
            System.out.printf("%s: %s,%s (pop: %s)\n", doc.get("name"),
                    doc.get("lat_store"), doc.get("lon_store"), doc.get("population_store"));
        }

        reader.close();
        directory.close();
    }
}
