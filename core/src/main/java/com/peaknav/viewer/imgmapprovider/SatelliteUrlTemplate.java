package com.peaknav.viewer.imgmapprovider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tile URL template, using the placeholder syntax of the OpenStreetMap in-browser editor (iD)
 * and the editor-layer-index it draws its imagery list from, so a URL copied from there can be
 * pasted straight in as a custom provider.
 *
 * <p>TMS tokens:
 * <ul>
 *   <li>{@code {zoom}} / {@code {z}}, {@code {x}}, {@code {y}} — tile coordinates</li>
 *   <li>{@code {-y}} / {@code {ty}} — flipped, TMS-style row</li>
 *   <li>{@code {switch:a,b,c}} / {@code {sw:a,b,c}} — DNS server multiplexing</li>
 *   <li>{@code {u}} — quadtile (Bing) scheme</li>
 *   <li>{@code {@2x}} / {@code {r}} — resolution scale factor</li>
 * </ul>
 *
 * <p>WMS tokens:
 * <ul>
 *   <li>{@code {proj}} — requested projection, always {@code EPSG:3857}</li>
 *   <li>{@code {wkid}} — the same without the {@code EPSG:} prefix</li>
 *   <li>{@code {width}}, {@code {height}} — requested image size, always 256</li>
 *   <li>{@code {bbox}} — the tile's bounding box as {@code minX,minY,maxX,maxY}</li>
 * </ul>
 *
 * <p>{@code {quadkey}} and {@code {subdomain}} are accepted too. They are not iD tokens, but
 * Bing-style URLs are commonly published using them, and rejecting a URL that is otherwise
 * perfectly usable helps nobody.
 *
 * <p>The template is tokenised once, when the provider is created, rather than re-scanned for
 * every tile. {@link #validate(String)} rejects unknown placeholders up-front: leaving them in
 * the URL would otherwise produce a request that silently fails.
 */
public final class SatelliteUrlTemplate {

    /** Matches any {...} group, so unknown placeholders can be reported rather than ignored. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");

    private static final String SWITCH_PREFIX = "switch:";
    private static final String SWITCH_SHORT_PREFIX = "sw:";

    /** The only projection these tokens are defined for. */
    private static final String PROJECTION = "EPSG:3857";
    private static final String PROJECTION_WKID = "3857";

    /** Tile edge in pixels; {width}/{height} are documented as 256 only. */
    private static final int TILE_SIZE = 256;

    /** Half the circumference of the earth in Web Mercator metres. */
    private static final double ORIGIN_SHIFT = 20037508.342789244;

    /**
     * Values used for a bare {@code {subdomain}}. Services that use it (Bing / Virtual Earth and
     * friends) shard over numbered hosts, e.g. {@code ecn.t0..t3}. A URL on its own carries no
     * list of subdomains, so when they are letters rather than digits use {@code {switch:a,b,c}}
     * instead, which states them explicitly.
     */
    private static final String[] DEFAULT_SUBDOMAINS = {"0", "1", "2", "3"};

    private static final String SUPPORTED_PLACEHOLDERS =
            "TMS: {zoom} {z} {x} {y} {-y} {ty} {switch:a,b,c} {u} {@2x} {r} | "
                    + "WMS: {proj} {wkid} {width} {height} {bbox}";

    private interface Part {
        void append(StringBuilder out, int z, int x, int y);
    }

    private static final class Literal implements Part {
        private final String text;
        Literal(String text) { this.text = text; }
        public void append(StringBuilder out, int z, int x, int y) { out.append(text); }
    }

    private static final class XPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) { out.append(x); }
    }

    private static final class YPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) { out.append(y); }
    }

    private static final class ZPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) { out.append(z); }
    }

    /** TMS row numbering: the y axis runs from the bottom instead of the top. */
    private static final class FlippedYPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) {
            out.append((1 << z) - 1 - y);
        }
    }

    private static final class QuadKeyPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) {
            appendQuadKey(out, z, x, y);
        }
    }

    private static final class SwitchPart implements Part {
        private final String[] options;
        SwitchPart(String[] options) { this.options = options; }
        public void append(StringBuilder out, int z, int x, int y) {
            // Spread tiles over the alternatives instead of always picking the first one.
            int index = Math.abs(x + y) % options.length;
            out.append(options[index]);
        }
    }

    private static final class BboxPart implements Part {
        public void append(StringBuilder out, int z, int x, int y) {
            appendBbox(out, z, x, y);
        }
    }

    private final String template;
    private final List<Part> parts;

    public SatelliteUrlTemplate(String template) {
        String error = validate(template);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        this.template = template;
        this.parts = tokenize(template);
    }

    public String getTemplate() {
        return template;
    }

    public String expand(int z, int x, int y) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        for (int i = 0; i < parts.size(); i++) {
            parts.get(i).append(out, z, x, y);
        }
        return out.toString();
    }

    /**
     * @return null when the template can be used, otherwise a human readable reason why not.
     */
    public static String validate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return "The URL template is empty.";
        }
        String trimmed = template.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "The URL template must start with http:// or https://";
        }
        if (com.peaknav.network.HttpsPolicy.isBlockedHttp(trimmed)) {
            return com.peaknav.network.HttpsPolicy.HTTP_BLOCKED_MESSAGE;
        }

        boolean hasX = false, hasY = false, hasZ = false, hasQuadKey = false, hasBbox = false;

        Matcher matcher = PLACEHOLDER.matcher(trimmed);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (isSwitch(name)) {
                if (splitSwitchOptions(name) == null) {
                    return "{" + name + "} needs at least one alternative, e.g. {switch:a,b,c}";
                }
                continue;
            }
            if ("x".equals(name)) { hasX = true; continue; }
            if ("y".equals(name)) { hasY = true; continue; }
            if ("-y".equals(name) || "ty".equals(name)) { hasY = true; continue; }
            if ("z".equals(name) || "zoom".equals(name)) { hasZ = true; continue; }
            if ("u".equals(name) || "quadkey".equals(name)) { hasQuadKey = true; continue; }
            if ("bbox".equals(name)) { hasBbox = true; continue; }
            // Tokens that expand to a fixed value and so place no requirement on the template.
            if ("subdomain".equals(name)
                    || "proj".equals(name) || "wkid".equals(name)
                    || "width".equals(name) || "height".equals(name)
                    || "@2x".equals(name) || "r".equals(name)) {
                continue;
            }
            return "Unsupported placeholder {" + name + "}. Supported: " + SUPPORTED_PLACEHOLDERS;
        }

        if (hasQuadKey || hasBbox) {
            // Quadkey and WMS bbox each already identify the tile on their own.
            return null;
        }
        if (!hasX || !hasY || !hasZ) {
            return "The URL template needs {x}, {y} and {z}, or {u}, or {bbox}.";
        }
        return null;
    }

    private static boolean isSwitch(String name) {
        return name.startsWith(SWITCH_PREFIX) || name.startsWith(SWITCH_SHORT_PREFIX);
    }

    /** @return the alternatives of a {@code switch:...} placeholder, or null when malformed. */
    private static String[] splitSwitchOptions(String placeholderName) {
        int prefixLength = placeholderName.startsWith(SWITCH_PREFIX)
                ? SWITCH_PREFIX.length() : SWITCH_SHORT_PREFIX.length();
        String list = placeholderName.substring(prefixLength);
        if (list.isEmpty()) {
            return null;
        }
        String[] options = list.split(",", -1);
        for (int i = 0; i < options.length; i++) {
            if (options[i].isEmpty()) {
                return null;
            }
        }
        return options;
    }

    private static List<Part> tokenize(String template) {
        List<Part> parts = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(new Literal(template.substring(last, matcher.start())));
            }
            String name = matcher.group(1);
            if (isSwitch(name)) {
                parts.add(new SwitchPart(splitSwitchOptions(name)));
            } else if ("subdomain".equals(name)) {
                parts.add(new SwitchPart(DEFAULT_SUBDOMAINS));
            } else if ("x".equals(name)) {
                parts.add(new XPart());
            } else if ("y".equals(name)) {
                parts.add(new YPart());
            } else if ("-y".equals(name) || "ty".equals(name)) {
                parts.add(new FlippedYPart());
            } else if ("u".equals(name) || "quadkey".equals(name)) {
                parts.add(new QuadKeyPart());
            } else if ("bbox".equals(name)) {
                parts.add(new BboxPart());
            } else if ("proj".equals(name)) {
                parts.add(new Literal(PROJECTION));
            } else if ("wkid".equals(name)) {
                parts.add(new Literal(PROJECTION_WKID));
            } else if ("width".equals(name) || "height".equals(name)) {
                parts.add(new Literal(Integer.toString(TILE_SIZE)));
            } else if ("@2x".equals(name) || "r".equals(name)) {
                // Tiles are always fetched at standard resolution, so the scale factor drops out.
                parts.add(new Literal(""));
            } else {
                // {z} and {zoom}; validate() has already rejected anything else.
                parts.add(new ZPart());
            }
            last = matcher.end();
        }
        if (last < template.length()) {
            parts.add(new Literal(template.substring(last)));
        }
        return parts;
    }

    private static final char[] QUAD_DIGITS = {'0', '1', '2', '3'};

    static void appendQuadKey(StringBuilder out, int z, int x, int y) {
        for (int i = z - 1; i >= 0; i--) {
            int mask = 1 << i;
            int digit = 0;
            if ((x & mask) != 0) digit += 1;
            if ((y & mask) != 0) digit += 2;
            out.append(QUAD_DIGITS[digit]);
        }
    }

    /**
     * Appends the tile's bounding box in Web Mercator metres as {@code minX,minY,maxX,maxY}.
     *
     * <p>Formatted with {@link Locale#ROOT} on purpose: under a locale that writes decimals with a
     * comma this would otherwise emit "11,25" and quietly corrupt the bbox parameter.
     */
    static void appendBbox(StringBuilder out, int z, int x, int y) {
        double span = 2.0 * ORIGIN_SHIFT / (double) (1 << z);
        double minX = -ORIGIN_SHIFT + x * span;
        double maxX = minX + span;
        double maxY = ORIGIN_SHIFT - y * span;
        double minY = maxY - span;

        appendMetres(out, minX);
        out.append(',');
        appendMetres(out, minY);
        out.append(',');
        appendMetres(out, maxX);
        out.append(',');
        appendMetres(out, maxY);
    }

    private static void appendMetres(StringBuilder out, double value) {
        out.append(String.format(Locale.ROOT, "%.6f", value));
    }

    /**
     * Best-effort image extension for a template, used to name cache files. Falls back to jpg:
     * plenty of perfectly good tile URLs carry no extension at all (the format is in the
     * response headers), and a custom provider must not be rejected just for that.
     */
    public static String guessImageExtension(String template) {
        String lower = template.toLowerCase(Locale.ROOT);
        // WMS states the format as a parameter rather than in the path.
        if (lower.contains("image/png")) {
            return "png";
        }
        if (lower.contains("image/jpeg") || lower.contains("image/jpg")) {
            return "jpg";
        }
        // Look at the path only; query strings often mention formats they do not return.
        int queryStart = lower.indexOf('?');
        String path = queryStart >= 0 ? lower.substring(0, queryStart) : lower;
        if (path.contains(".png")) {
            return "png";
        }
        if (path.contains(".jpg") || path.contains(".jpeg")) {
            return "jpg";
        }
        if (lower.contains("png")) {
            return "png";
        }
        return "jpg";
    }
}
