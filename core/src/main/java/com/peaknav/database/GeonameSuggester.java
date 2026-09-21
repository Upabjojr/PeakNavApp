package com.peaknav.database;

import com.badlogic.gdx.Gdx;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.Version;

import java.io.StringReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what is being typed into the search box into a short list of places.
 *
 * <p>The box asks again at every keystroke, so half-typed words are the normal case, not the
 * exception: "Matterh" has to offer the Matterhorn. A fuzzy query cannot do that - it measures
 * whole words, and seven letters of a ten-letter name are three edits away, further than any
 * usable similarity allows - so the half-typed word is looked up as a <em>prefix</em> instead,
 * which is also what the index is fast at: reading the run of terms that begin with "matterh"
 * takes a fraction of a millisecond, where a fuzzy query walks the whole dictionary of 330000
 * places and took a tenth of a second on the same query. Fuzzy matching is still there, but as
 * a second pass, for a genuine misspelling, and only when the prefix pass came back short.
 *
 * <p>Lucene's own score decides which places are <em>considered</em>, and nothing more. The
 * order they are shown in is decided here, from two things a reader can see: how much of the
 * name was actually typed, and how big the place is. Left to Lucene alone, "Zermat" offered a
 * lake above Zermatt and "Londo" a 2388-metre summit above London, because the index scores a
 * rare word higher than a common one, and the size boost it carries is quantised so coarsely
 * that the Matterhorn, Klein Matterhorn and a Montenegrin namesake came back in a dead heat.
 */
public final class GeonameSuggester {

    /** The one indexed field: a place's own name and every alternate name it is known by. */
    private static final String FIELD = "name";

    /**
     * How many places are ranked here before the best few are handed back. Lucene picks them by
     * its own score, which the population boost dominates, so a pool of sixty was all cities:
     * "Matt" reached five towns called Mattapan and Matteson and never the Matterhorn, which a
     * hundred does reach. Every place in the pool costs a read of its stored name.
     */
    private static final int CANDIDATE_POOL = 100;

    /**
     * A ceiling on how many words one typed prefix may stand for. "matterh" reaches 3 words and
     * "mat" 585, but "ma" reaches 8452 and a lone "m" 22553, and the search runs on the drawing
     * thread at every keystroke. Beyond the ceiling the shortest words win, being the closest to
     * what was typed; the next keystroke narrows it down anyway. Not lower than this, though:
     * cutting it to 128 dropped "paradiso" from the 600-odd words beginning with "par", and
     * "Gran Par" stopped offering the Gran Paradiso.
     */
    private static final int MAX_PREFIX_TERMS = 512;

    /** A single letter stands for too much of the world to be looked up as a prefix. */
    private static final int MIN_PREFIX_LENGTH = 2;

    /** Below this, a word is too short for a misspelling of it to be told from another word. */
    private static final int MIN_FUZZY_LENGTH = 4;

    /** Lucene's measure: the share of the shorter word that has to survive the edits. */
    private static final float FUZZY_SIMILARITY = 0.7f;

    private GeonameSuggester() {
    }

    /**
     * The places to offer for what has been typed so far, best first.
     *
     * @param searcher   the open index; nothing is returned without one
     * @param queryText  the raw contents of the search box
     * @param maxResults how many places the list can show
     */
    public static List<Document> suggest(IndexSearcher searcher, String queryText, int maxResults) {
        List<Document> results = new ArrayList<>();
        if (searcher == null || queryText == null) {
            return results;
        }
        List<String> tokens = analyze(queryText);
        if (tokens.isEmpty()) {
            return results;
        }

        IndexReader reader = searcher.getIndexReader();
        // Three passes, each looser than the last and each run only if the ones before it came
        // back short. The strict pass is what answers nearly every keystroke; the loose ones
        // cost something, and only buy their keep when there is otherwise nothing to show.
        List<Candidate> candidates = new ArrayList<>();
        Map<Integer, Candidate> seen = new LinkedHashMap<>();
        collect(searcher, reader, tokens, false, true, 0, seen, candidates);
        if (candidates.size() < maxResults) {
            collect(searcher, reader, tokens, true, true, 1, seen, candidates);
        }
        if (candidates.size() < maxResults && tokens.size() > 1) {
            collect(searcher, reader, tokens, true, false, 2, seen, candidates);
        }

        String query = fold(queryText);
        for (Candidate candidate : candidates) {
            candidate.rank = matchBonus(candidate.document, query, tokens)
                    + importance(candidate.document);
        }
        // A stable sort, so that within a pass places that Lucene liked equally keep the order
        // it found them in, and a later pass never climbs above an earlier one it tied with.
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.pass != b.pass) {
                    return a.pass - b.pass;
                }
                return Float.compare(b.rank, a.rank);
            }
        });

        for (Candidate candidate : candidates) {
            if (results.size() >= maxResults) {
                break;
            }
            results.add(candidate.document);
        }
        return results;
    }

    /** One place Lucene turned up, with the pass that found it and the rank given to it here. */
    private static final class Candidate {
        final Document document;
        final int pass;
        float rank;

        Candidate(Document document, int pass) {
            this.document = document;
            this.pass = pass;
        }
    }

    /** Runs one pass and adds whatever it found that no earlier pass had. */
    private static void collect(IndexSearcher searcher, IndexReader reader, List<String> tokens,
                                boolean fuzzy, boolean requireAll, int pass,
                                Map<Integer, Candidate> seen, List<Candidate> candidates) {
        try {
            Query query = buildQuery(reader, tokens, fuzzy, requireAll);
            if (query == null) {
                return;
            }
            TopDocs topDocs = searcher.search(query, CANDIDATE_POOL);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                if (seen.containsKey(scoreDoc.doc)) {
                    continue;
                }
                Candidate candidate = new Candidate(searcher.doc(scoreDoc.doc), pass);
                seen.put(scoreDoc.doc, candidate);
                candidates.add(candidate);
            }
        } catch (Throwable keepWhateverWasFound) {
            // A query too big to rewrite, a damaged index, anything: the box shows what the
            // passes before this one found rather than nothing at all.
        }
    }

    /**
     * One clause per typed word, each of them a choice between the word itself, the words it
     * begins, and - in the later passes - the words it could be a misspelling of.
     *
     * @param requireAll every typed word has to be matched, which is what keeps "Monte Ros" from
     *                   offering the ten thousand places with "Monte" in their name
     */
    private static Query buildQuery(IndexReader reader, List<String> tokens,
                                    boolean fuzzy, boolean requireAll) {
        BooleanQuery root = new BooleanQuery();
        int clauses = 0;
        for (String token : tokens) {
            BooleanQuery alternatives = new BooleanQuery(true);
            TermQuery exact = new TermQuery(new Term(FIELD, token));
            exact.setBoost(4f);
            alternatives.add(exact, BooleanClause.Occur.SHOULD);

            if (token.length() >= MIN_PREFIX_LENGTH) {
                Query prefix = prefixAlternatives(reader, token);
                if (prefix != null) {
                    alternatives.add(prefix, BooleanClause.Occur.SHOULD);
                }
            }
            if (fuzzy && token.length() >= MIN_FUZZY_LENGTH) {
                // A prefix length of one: a misspelling of the first letter is rare, and
                // allowing for it costs a walk through every word in the index.
                FuzzyQuery typo = new FuzzyQuery(new Term(FIELD, token), FUZZY_SIMILARITY, 1);
                typo.setBoost(0.5f);
                alternatives.add(typo, BooleanClause.Occur.SHOULD);
            }
            root.add(alternatives, requireAll
                    ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
            clauses++;
        }
        return clauses == 0 ? null : root;
    }

    /**
     * The words the index holds that begin with what was typed, as one query. Reading them
     * straight out of the term dictionary rather than handing Lucene a {@code PrefixQuery} is
     * what keeps the size boost working: Lucene rewrites a prefix into a constant score, which
     * would leave every place beginning with "matterh" tied, and into a query too wide to
     * rewrite at all once the prefix is short.
     */
    private static Query prefixAlternatives(IndexReader reader, String token) {
        List<String> words = new ArrayList<>();
        TermEnum terms = null;
        try {
            terms = reader.terms(new Term(FIELD, token));
            do {
                Term term = terms.term();
                if (term == null || !FIELD.equals(term.field())
                        || !term.text().startsWith(token)) {
                    break;
                }
                if (!term.text().equals(token)) {   // the word itself is already a clause
                    words.add(term.text());
                }
            } while (terms.next());
        } catch (Throwable useWhatWasRead) {
            // An unreadable term dictionary leaves the exact-word clause to answer alone.
        } finally {
            if (terms != null) {
                try {
                    terms.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (words.isEmpty()) {
            return null;
        }
        if (words.size() > MAX_PREFIX_TERMS) {
            Collections.sort(words, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int byLength = a.length() - b.length();
                    return byLength != 0 ? byLength : a.compareTo(b);
                }
            });
            words = words.subList(0, MAX_PREFIX_TERMS);
        }
        BooleanQuery prefix = new BooleanQuery(true);
        for (String word : words) {
            TermQuery term = new TermQuery(new Term(FIELD, word));
            // The less of the word is left to type, the better a guess it is at what was meant.
            term.setBoost((float) token.length() / word.length());
            prefix.add(term, BooleanClause.Occur.SHOULD);
        }
        return prefix;
    }

    /**
     * How much of the name was typed, from a whole name down to a match this place owes to one
     * of its alternate names or to a corrected misspelling. Only the name the list will show is
     * read: the alternate names are indexed but not stored, so a place that answers to "Roma"
     * while calling itself Rome is scored here as if it had not matched by name at all - and
     * still comes first, because two million Romans outweigh the difference.
     */
    static float matchBonus(Document document, String foldedQuery, List<String> tokens) {
        float best = 0f;
        for (String field : new String[]{"name", "asciiname"}) {
            String value = document.get(field);
            if (value == null) {
                continue;
            }
            String name = fold(value);
            if (name.equals(foldedQuery)) {
                return 1f;
            }
            if (name.startsWith(foldedQuery)) {
                best = Math.max(best, 0.6f);
            } else if (wordsBeginWith(name, tokens)) {
                best = Math.max(best, 0.3f);
            }
        }
        return best;
    }

    /** True when every typed word begins a word of this name, each one a different word. */
    private static boolean wordsBeginWith(String name, List<String> tokens) {
        List<String> words = new ArrayList<>();
        for (String word : name.split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        for (String token : tokens) {
            boolean found = false;
            for (int i = 0; i < words.size(); i++) {
                if (words.get(i).startsWith(token)) {
                    words.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * How much of a landmark the place is, on one scale for towns and summits alike: the number
     * of digits in the population, and kilometres of height. It puts London above a hamlet
     * called London, the Matterhorn above Klein Matterhorn, and - in an app for the mountains -
     * a four-thousander above a village of ten thousand.
     */
    static float importance(Document document) {
        int elevation = readInt(document.get("ele_store"));
        if ("peak".equals(document.get("type_store")) && elevation > 0) {
            return Math.min(elevation / 1000f + 0.5f, 5f);
        }
        int population = readInt(document.get("population_store"));
        if (population > 0) {
            return (float) (Math.log(population) / Math.log(10));
        }
        return 0f;
    }

    private static int readInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** The typed words, cut the way the index cut the names it holds. */
    static List<String> analyze(String text) {
        List<String> tokens = new ArrayList<>();
        TokenStream stream = null;
        try {
            stream = new StandardAnalyzer(Version.LUCENE_36)
                    .tokenStream(FIELD, new StringReader(text));
            CharTermAttribute word = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String token = word.toString();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            stream.end();
        } catch (Throwable useWhatWasRead) {
            // Nothing typed into a search box may crash the app - but it must not disappear
            // either. Swallowing this in silence is how a missing analyzer class went three
            // TestFlight builds looking like "the offline search finds nothing": no words
            // means no places, and nothing said why. Logged once per failure, then carry on
            // with whatever was read.
            Gdx.app.error("PeakNav", "search: cannot split \"" + text + "\" into words: "
                    + useWhatWasRead);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return tokens;
    }

    /**
     * A name as it is compared here: lower case, without accents, and without the spacing an
     * ASCII keyboard cannot reproduce - so that "Zurich" is read as the start of "Zürich".
     */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.trim(), Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char letter = decomposed.charAt(i);
            if (Character.getType(letter) == Character.NON_SPACING_MARK) {
                continue;
            }
            switch (letter) {
                case 'ß': folded.append("ss"); break;
                case 'ø': case 'Ø': folded.append('o'); break;
                case 'đ': case 'ð': case 'Đ': case 'Ð': folded.append('d'); break;
                case 'ł': case 'Ł': folded.append('l'); break;
                case 'æ': case 'Æ': folded.append("ae"); break;
                case 'œ': case 'Œ': folded.append("oe"); break;
                case 'þ': case 'Þ': folded.append("th"); break;
                default: folded.append(letter);
            }
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }
}
