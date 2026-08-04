package com.peaknav.tools;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.DefaultSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adds mountain peaks to an existing GeoNames search index, in place.
 *
 * <pre>
 * ./gradlew :core:addPeaksToIndex --args="peaks.tsv assets/geonames_index.362 [minEle]"
 * </pre>
 *
 * <p>Appends rather than rebuilds, because rebuilding needs the GeoNames dumps - half a
 * gigabyte that is not kept on disk - while appending needs only the index this repository
 * already ships. The writer opens the index as {@link GeonamesIndexBuilder} created it,
 * with the same analyzer and the same similarity, so peak documents and city documents
 * score by the same rules.
 *
 * <p>The input is one peak per line: {@code lat, lon, ele, wikidata, name, alternates}
 * (tab-separated, alternates '|'-separated) - the output of the OSM extraction script.
 *
 * <p>Peaks are ranked the way cities are: a boost riding on the field norms, logarithmic
 * in elevation where a city's is logarithmic in population. The scales are deliberately
 * offset - the highest Alp reaches the boost of a middling town - so a city and a peak
 * sharing a name resolve to the city, which is nearly always the more searched-for.
 * Having a Wikipedia article adds a fixed step, which among peaks of similar height
 * lifts the one people actually visit.
 *
 * <p>After the append the index is optimized into a single segment and
 * {@code filelist.txt} is rewritten: the loader copies exactly the files that list names,
 * and an optimize renames every segment file.
 */
public final class PeakIndexAppender {

    private PeakIndexAppender() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: PeakIndexAppender <peaks.tsv> <indexDir> [minEle]");
            System.exit(2);
        }
        String peaksFile = args[0];
        String indexDir = args[1];
        int minEle = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        run(peaksFile, indexDir, minEle);
    }

    public static void run(String peaksFile, String indexDir, int minEle) throws IOException {
        Directory dir = FSDirectory.open(new File(indexDir));
        Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_36);
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_36, analyzer);
        // The same norm the city index was written with (see GeonamesIndexBuilder.write):
        // the boost and nothing else, so a peak with six names is as findable as a peak
        // with one.
        config.setSimilarity(new DefaultSimilarity() {
            @Override
            public float computeNorm(String field, FieldInvertState state) {
                return state.getBoost();
            }
        });

        long added = 0, skippedLow = 0;
        IndexWriter writer = new IndexWriter(dir, config);
        try {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(peaksFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 6 || f[4].isEmpty()) {
                        continue;
                    }
                    int ele = f[2].isEmpty() ? 0 : Integer.parseInt(f[2]);
                    boolean wiki = "1".equals(f[3]);
                    // The budget knob. A minimum elevation only ever excludes peaks that
                    // are BOTH low and obscure: anything with a Wikipedia article stays,
                    // whatever its height - Vesuvius is 1281 m and better known than most
                    // four-thousanders.
                    if (ele < minEle && !wiki) {
                        skippedLow++;
                        continue;
                    }
                    writer.addDocument(peakDocument(
                            f[4], f[5].isEmpty() ? new String[0] : f[5].split("\\|"),
                            f[0], f[1], ele, wiki));
                    added++;
                }
            }
            // One segment, like the shipped index: the loader copies a fixed list of
            // files, and a multi-segment index would make that list longer and fragile.
            writer.forceMerge(1);
        } finally {
            writer.close();
        }

        writeFileList(new File(indexDir));
        System.out.println("added " + added + " peaks"
                + (minEle > 0 ? " (skipped " + skippedLow + " below " + minEle + " m)" : ""));
    }

    /** A peak as a search document, shaped exactly like the builder's city documents. */
    public static Document peakDocument(String name, String[] alternates,
                                        String lat, String lon, int ele, boolean wiki) {
        Document doc = new Document();
        doc.setBoost(elevationBoost(ele, wiki));

        // First "name" value stored, the rest search-only - the same convention as the
        // cities, so doc.get("name") is always the display name.
        doc.add(new Field("name", name, Field.Store.YES, Field.Index.ANALYZED));
        List<String> extra = new ArrayList<>(Arrays.asList(alternates));
        String ascii = GeonamesIndexBuilder.stripAccents(name);
        if (!ascii.equals(name) && !extra.contains(ascii)) {
            extra.add(ascii);   // "grossglockner" must find Großglockner
        }
        // Of every alias, only the words the document cannot be found by yet. Indexing
        // whole aliases repeats words: "Matterhorn od Žijeva" with its ASCII alias
        // "Matterhorn od Zijeva" carries "matterhorn" twice, and Lucene's term frequency
        // - the square root of two - outweighed a two-kilometre difference in elevation
        // boost, putting that Montenegrin summit above the Matterhorn for its own name.
        // Skipping such aliases whole is no better, just wrong the other way: it threw
        // away "Zijeva", or - with folded comparison - the ASCII spelling itself.
        // Word order is not preserved, which the search does not mind: the app issues
        // per-term fuzzy queries, never phrase queries.
        java.util.Set<String> seenTokens = new java.util.HashSet<>(tokensOf(name));
        for (String other : extra) {
            if (other.isEmpty() || other.equals(name)) {
                continue;
            }
            StringBuilder novel = new StringBuilder();
            for (String word : other.split("[^\\p{L}\\p{N}]+")) {
                if (!word.isEmpty()
                        && seenTokens.add(word.toLowerCase(java.util.Locale.ROOT))) {
                    if (novel.length() > 0) {
                        novel.append(' ');
                    }
                    novel.append(word);
                }
            }
            if (novel.length() > 0) {
                doc.add(new Field("name", novel.toString(),
                        Field.Store.NO, Field.Index.ANALYZED));
            }
        }
        doc.add(new Field("asciiname", ascii, Field.Store.YES, Field.Index.NO));
        doc.add(new Field("lat_store", lat, Field.Store.YES, Field.Index.NO));
        doc.add(new Field("lon_store", lon, Field.Store.YES, Field.Index.NO));
        // Zero, not absent: the search side parses this field unconditionally.
        doc.add(new Field("population_store", "0", Field.Store.YES, Field.Index.NO));
        doc.add(new Field("type_store", "peak", Field.Store.YES, Field.Index.NO));
        doc.add(new Field("ele_store", String.valueOf(ele), Field.Store.YES, Field.Index.NO));
        return doc;
    }

    /**
     * A name as comparable search tokens: lowercased and split the way the
     * StandardAnalyzer roughly would, but NOT accent-folded - the analyzer indexes text
     * as written, so "Großglockner" and "Grossglockner" are different terms to the
     * search, and must be different tokens here or the ASCII spelling is dropped as
     * redundant.
     */
    private static List<String> tokensOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : lower.split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * A peak's rank, on the same scale as {@link GeonamesIndexBuilder#populationBoost}.
     * Logarithmic in elevation; Mont Blanc lands near the boost of a town of ten
     * thousand, so namesake cities still come first. A Wikipedia article is worth a
     * fixed step - roughly one order of magnitude of elevation, far more than any
     * height difference between neighbours.
     */
    public static float elevationBoost(int ele, boolean wiki) {
        float boost = 1f + (float) Math.log10(1d + Math.max(0, ele));
        return wiki ? boost + 0.5f : boost;
    }

    /** The exact segment files on disk, one per line - what the asset loader copies. */
    private static void writeFileList(File indexDir) throws IOException {
        File[] files = indexDir.listFiles();
        if (files == null) {
            throw new IOException("cannot list " + indexDir);
        }
        List<String> names = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (!n.equals("filelist.txt") && !n.startsWith(".") && f.isFile()) {
                names.add(n);
            }
        }
        try (PrintWriter out = new PrintWriter(
                new File(indexDir, "filelist.txt"), "UTF-8")) {
            for (String n : names) {
                out.println(n);
            }
        }
    }
}
