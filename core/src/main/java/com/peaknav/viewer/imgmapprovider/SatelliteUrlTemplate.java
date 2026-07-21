package com.peaknav.viewer.imgmapprovider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tile URL template, using the placeholder syntax of the OpenStreetMap in-browser editor (iD)
 * and the editor-layer-index it draws its imagery list from. That means a URL copied from there
 * can be pasted straight in as a custom provider.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>{@code {x}}, {@code {y}} — tile column and row</li>
 *   <li>{@code {z}} / {@code {zoom}} — zoom level</li>
 *   <li>{@code {-y}} — row counted from the bottom, for TMS services</li>
 *   <li>{@code {u}} — quadkey, as used by Bing-style services</li>
 *   <li>{@code {switch:a,b,c}} — picks one of the alternatives (typically server subdomains),
 *       spread over tiles so requests do not all hit the same host</li>
 * </ul>
 *
 * <p>The template is tokenised once, when the provider is created, rather than re-scanned for
 * every tile. {@link #validate(String)} rejects unknown placeholders up-front: leaving them in
 * the URL would otherwise produce a request that silently fails.
 */
public final class SatelliteUrlTemplate {

    /** Matches any {...} group, so unknown placeholders can be reported rather than ignored. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");

    private static final String SWITCH_PREFIX = "switch:";

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
        StringBuilder out = new StringBuilder(template.length() + 16);
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

        boolean hasX = false, hasY = false, hasZ = false, hasQuadKey = false;

        Matcher matcher = PLACEHOLDER.matcher(trimmed);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.startsWith(SWITCH_PREFIX)) {
                if (splitSwitchOptions(name) == null) {
                    return "{" + name + "} needs at least one alternative, e.g. {switch:a,b,c}";
                }
                continue;
            }
            if ("x".equals(name)) { hasX = true; continue; }
            if ("y".equals(name)) { hasY = true; continue; }
            if ("-y".equals(name)) { hasY = true; continue; }
            if ("z".equals(name) || "zoom".equals(name)) { hasZ = true; continue; }
            if ("u".equals(name)) { hasQuadKey = true; continue; }
            return "Unsupported placeholder {" + name + "}. "
                    + "Supported: {x} {y} {-y} {z} {zoom} {u} {switch:a,b,c}";
        }

        if (hasQuadKey) {
            return null;
        }
        if (!hasX || !hasY || !hasZ) {
            return "The URL template needs {x}, {y} and {z} (or {u} for a quadkey service).";
        }
        return null;
    }

    /** @return the alternatives of a {@code switch:...} placeholder, or null when malformed. */
    private static String[] splitSwitchOptions(String placeholderName) {
        String list = placeholderName.substring(SWITCH_PREFIX.length());
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
            if (name.startsWith(SWITCH_PREFIX)) {
                parts.add(new SwitchPart(splitSwitchOptions(name)));
            } else if ("x".equals(name)) {
                parts.add(new XPart());
            } else if ("y".equals(name)) {
                parts.add(new YPart());
            } else if ("-y".equals(name)) {
                parts.add(new FlippedYPart());
            } else if ("u".equals(name)) {
                parts.add(new QuadKeyPart());
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
     * Best-effort image extension for a template, used to name cache files. Falls back to jpg:
     * plenty of perfectly good tile URLs carry no extension at all (the format is in the
     * response headers), and a custom provider must not be rejected just for that.
     */
    public static String guessImageExtension(String template) {
        String lower = template.toLowerCase();
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
