package com.peaknav.headless;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * A small REST server over {@link PeakNavRenderer}, so the renderer can be driven from
 * any language that can speak HTTP - the Python package under {@code python/} is one
 * such client, and {@code curl} is another.
 *
 * <pre>
 * peaknav-headless --lat 46 --lon 7.7 --serve 0
 *   PEAKNAV_SERVE port=41123          # printed once the server is up; 0 picked a port
 * </pre>
 *
 * <p>Built on the JDK's own {@code com.sun.net.httpserver} and libGDX's JSON classes,
 * both already on the classpath: an API server should not be the reason the app gains
 * a web framework. It binds to 127.0.0.1 only - it executes camera movements and file
 * writes on behalf of the caller, which is nothing to offer a network. The API is
 * described by the OpenAPI document at {@code /openapi.json}, which is also the file
 * Swagger UI or any generator can be pointed at.
 *
 * <p>Requests are serialised: the renderer is one camera and one framebuffer, so two
 * concurrent "render this view" calls could only interleave into nonsense. A
 * single-threaded executor makes each request see the world the previous one left.
 */
final class RestServer {

    private final PeakNavRenderer renderer;
    private final String imageFormat;
    private HttpServer server;

    RestServer(PeakNavRenderer renderer, String imageFormat) {
        this.renderer = renderer;
        this.imageFormat = imageFormat == null ? "png" : imageFormat;
    }

    /** Starts listening; port 0 lets the OS pick. Returns the actual port. */
    int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/", this::route);
        server.start();
        return server.getAddress().getPort();
    }

    /** Stops listening (the renderer stays up); for tests that start a server of their own. */
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void route(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        try {
            if ("GET".equals(method) && "/status".equals(path)) {
                json(x, 200, "{\"ok\":true,\"orbiting\":" + renderer.isOrbiting()
                        + "," + renderer.labelDiagnostics() + "," + renderer.quietDiagnostics() + "}");
            } else if ("GET".equals(method) && "/openapi.json".equals(path)) {
                resource(x, "openapi.json", "application/json");
            } else if ("POST".equals(method) && "/position".equals(path)) {
                position(x);
            } else if ("POST".equals(method) && "/camera".equals(path)) {
                camera(x);
            } else if ("POST".equals(method) && "/view".equals(path)) {
                view(x);
            } else if ("POST".equals(method) && "/wait".equals(path)) {
                waitQuiet(x);
            } else if ("POST".equals(method) && "/gpx".equals(path)) {
                gpx(x);
            } else if ("GET".equals(method) && "/providers".equals(path)) {
                providers(x);
            } else if ("GET".equals(method) && "/objects".equals(path)) {
                objects(x);
            } else if ("POST".equals(method) && "/photo".equals(path)) {
                photo(x);
            } else if ("DELETE".equals(method) && "/photo".equals(path)) {
                renderer.clearPhoto();
                json(x, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/photo/match".equals(path)) {
                photoMatch(x);
            } else if ("POST".equals(method) && "/photo/overlay".equals(path)) {
                photoOverlay(x);
            } else if ("POST".equals(method) && "/photo/pin".equals(path)) {
                JsonValue body = body(x);
                renderer.pinPhoto(require(body, "x").asFloat(), require(body, "y").asFloat());
                json(x, 200, "{\"ok\":true}");
            } else if ("DELETE".equals(method) && "/photo/pin".equals(path)) {
                renderer.unpinPhoto();
                json(x, 200, "{\"ok\":true}");
            } else if ("POST".equals(method) && "/tap".equals(path)) {
                JsonValue body = body(x);
                renderer.tap(require(body, "x").asInt(), require(body, "y").asInt());
                renderer.settle(200);
                json(x, 200, "{\"ok\":true}");
            } else if ("GET".equals(method) && "/widgets".equals(path)) {
                json(x, 200, renderer.widgetsJson());
            } else if ("GET".equals(method) && "/frame".equals(path)) {
                frame(x);
            } else if ("POST".equals(method) && "/shutdown".equals(path)) {
                json(x, 200, "{\"ok\":true}");
                // The JVM cannot outlive this by the normal route: the app's thread
                // pools are non-daemon (see RenderCli.main, which halts for the same
                // reason).
                new Thread(() -> {
                    renderer.close();
                    Runtime.getRuntime().halt(0);
                }).start();
            } else {
                json(x, 404, "{\"error\":\"no such endpoint: " + method + " " + path + "\"}");
            }
        } catch (IllegalArgumentException bad) {
            json(x, 400, "{\"error\":" + quote(bad.getMessage()) + "}");
        } catch (Exception failed) {
            json(x, 500, "{\"error\":" + quote(String.valueOf(failed)) + "}");
        }
    }

    /** Move the map; optionally download what is missing there and wait for quiet. */
    private void position(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        double lat = require(body, "lat").asDouble();
        double lon = require(body, "lon").asDouble();
        renderer.moveTo(lat, lon);
        boolean downloaded = true;
        if (body.has("download_timeout_ms")) {
            downloaded = renderer.downloadMissingData(
                    lat, lon, body.getLong("download_timeout_ms"));
        }
        boolean quiet = true;
        if (body.has("await_tiles_ms")) {
            quiet = renderer.awaitTilesLoaded(body.getLong("await_tiles_ms"));
        }
        json(x, 200, "{\"ok\":true,\"downloaded\":" + downloaded + ",\"quiet\":" + quiet + "}");
    }

    /** Point and place the camera. Height is one of three explicit forms, never mixed. */
    private void camera(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        if (body.has("bearing_deg") || body.has("pitch_deg") || body.has("roll_deg")) {
            renderer.aim(body.getFloat("bearing_deg", 0f), body.getFloat("pitch_deg", 0f),
                    body.getFloat("roll_deg", 0f));
        }
        int heights = (body.has("altitude_asl_m") ? 1 : 0)
                + (body.has("elevation_above_ground_m") ? 1 : 0)
                + (body.has("elevation_bar") ? 1 : 0);
        if (heights > 1) {
            throw new IllegalArgumentException(
                    "give one of altitude_asl_m, elevation_above_ground_m, elevation_bar");
        }
        if (body.has("altitude_asl_m")) {
            renderer.setAltitudeMeters(body.getDouble("altitude_asl_m"));
        } else if (body.has("elevation_above_ground_m")) {
            renderer.setElevationMeters(body.getDouble("elevation_above_ground_m"));
        } else if (body.has("elevation_bar")) {
            renderer.setElevation(body.getFloat("elevation_bar"));
        }
        json(x, 200, "{\"ok\":true}");
    }

    /** Every display toggle in one endpoint, all optional - set what you name. */
    private void view(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        setIf(body, "sky", renderer::setSky);
        if (body.has("sky_mode")) {
            String mode = body.getString("sky_mode");
            int value = "day".equals(mode) ? 1 : "night".equals(mode) ? 2
                    : "local".equals(mode) ? 0 : -1;
            if (value < 0) {
                throw new IllegalArgumentException("sky_mode wants local, day or night");
            }
            renderer.setSkyMode(value);
        }
        setIf(body, "constellations", renderer::setConstellations);
        setIf(body, "star_names", renderer::setStarNames);
        setIf(body, "sky_labels", renderer::setSkyLabels);
        setIf(body, "sky_grid", renderer::setSkyGrid);
        setIf(body, "ecliptic", renderer::setSkyEcliptic);
        setIf(body, "sky_time_label", renderer::setSkyTimeLabel);
        setIf(body, "sun_shading", renderer::setSunShading);
        setIf(body, "label_auto_update", renderer::setLabelAutoUpdate);
        if (body.has("refresh_labels") && body.getBoolean("refresh_labels")) {
            renderer.refreshLabels();
        }
        setIf(body, "horizon_compass", renderer::setHorizonCompass);
        setIf(body, "coordinates", renderer::setShowCoordinates);
        setIf(body, "corner_compass", renderer::setCornerCompass);
        if (body.has("sky_time")) {
            renderer.setSkyTimeMillis(java.time.Instant
                    .parse(body.getString("sky_time")).toEpochMilli());
        }
        if (body.has("fov")) {
            renderer.setFieldOfView(body.getFloat("fov"));
        }
        if (body.has("options_pane")) {
            renderer.setOptionsPane(body.getBoolean("options_pane"));
        }
        // Imagery source. By id for one the app knows, or by template for anything else -
        // any XYZ tile server, which is how a caller points the renderer at OpenStreetMap
        // or at a server of their own. Handled before "labels" only because it is a
        // heavier change; the two are independent.
        if (body.has("satellite_template")) {
            String template = body.getString("satellite_template");
            String name = body.getString("satellite_name", "Custom");
            String attribution = body.getString("satellite_attribution", "");
            String refused = renderer.setCustomSatelliteProvider(template, name, attribution);
            if (refused != null) {
                throw new IllegalArgumentException(refused);
            }
        } else if (body.has("satellite_provider")) {
            String id = body.getString("satellite_provider");
            if (!renderer.setSatelliteProvider(id)) {
                throw new IllegalArgumentException("no imagery provider with id: " + id
                        + " (GET /providers lists them)");
            }
        }
        if (body.has("labels")) {
            // A whitelist, like the CLI's --labels: everything off, the named ones on.
            renderer.clearLabels();
            for (JsonValue v = body.get("labels").child; v != null; v = v.next) {
                try {
                    renderer.setLabel(PeakNavRenderer.Label.valueOf(
                            v.asString().toUpperCase(Locale.ROOT)), true);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("unknown label: " + v.asString());
                }
            }
        }
        json(x, 200, "{\"ok\":true}");
    }

    private void waitQuiet(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        boolean quiet = true;
        if (body.has("tiles_timeout_ms")) {
            quiet = renderer.awaitTilesLoaded(body.getLong("tiles_timeout_ms"));
        }
        if (body.has("settle_ms")) {
            renderer.settle(body.getLong("settle_ms"));
        }
        json(x, 200, "{\"ok\":true,\"quiet\":" + quiet + "}");
    }

    /** The imagery sources this renderer can be pointed at, id and name. */
    private void providers(HttpExchange x) throws IOException {
        StringBuilder json = new StringBuilder("{\"providers\":[");
        boolean first = true;
        for (String line : renderer.satelliteProviders().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(quote(parts[0])).append(",\"name\":")
                    .append(quote(parts.length > 1 ? parts[1] : parts[0])).append('}');
        }
        json.append("]}");
        json(x, 200, json.toString());
    }

    /**
     * The loaded objects - peaks, places, alpine huts, pistes, area names - with where their
     * labels sit on the frame. {@code ?scope=all} widens from the labelling candidates to
     * every loaded POI; {@code ?drawn=true} narrows to what the last frame actually drew.
     */
    private void objects(HttpExchange x) throws IOException {
        java.util.Map<String, String> q = query(x);
        String scope = q.containsKey("scope") ? q.get("scope") : "displayable";
        boolean drawn = "true".equalsIgnoreCase(q.get("drawn"));
        json(x, 200, renderer.objectsJson(scope, drawn));
    }

    /**
     * Puts a photograph behind the terrain: {@code {"path": "/photo.jpg"}} naming a file the
     * renderer can read, or {@code {"image_base64": "..."}} with the JPEG or PNG inline. With
     * {@code go_to_exif} (default true) and a GPS position in the file, the viewpoint moves
     * there first, downloading and waiting as /position does when asked.
     */
    private void photo(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        byte[] bytes;
        if (body.has("image_base64")) {
            bytes = java.util.Base64.getDecoder().decode(body.getString("image_base64"));
        } else if (body.has("path")) {
            bytes = Files.readAllBytes(new File(body.getString("path")).toPath());
        } else {
            throw new IllegalArgumentException("give path or image_base64");
        }
        renderer.loadPhoto(bytes, body.getLong("load_timeout_ms", 60_000L));
        double[] location = renderer.photoLocation();
        boolean moved = false, downloaded = true, quiet = true;
        if (location != null && body.getBoolean("go_to_exif", true)) {
            renderer.moveTo(location[0], location[1]);
            moved = true;
            if (body.has("download_timeout_ms")) {
                downloaded = renderer.downloadMissingData(location[0], location[1], body.getLong("download_timeout_ms"));
            }
            if (body.has("await_tiles_ms")) {
                quiet = renderer.awaitTilesLoaded(body.getLong("await_tiles_ms"));
            }
        }
        float[] size = renderer.photoSize();
        StringBuilder json = new StringBuilder("{\"ok\":true");
        if (size != null) {
            json.append(",\"width\":").append((int) size[0]).append(",\"height\":").append((int) size[1]);
            json.append(",\"vertical_fov_deg\":").append(Float.isNaN(size[2]) ? "null" : String.valueOf(size[2]));
        }
        json.append(",\"location\":").append(location == null ? "null"
                : String.format(Locale.ENGLISH, "{\"lat\":%.6f,\"lon\":%.6f}", location[0], location[1]));
        json.append(",\"moved\":").append(moved).append(",\"downloaded\":").append(downloaded)
                .append(",\"quiet\":").append(quiet).append('}');
        json(x, 200, json.toString());
    }

    /**
     * Runs the skyline matcher for the loaded photo at the current position and turns the
     * camera to the best pose; {@code attempts} (default 3) retries a few seconds apart
     * while the terrain around the viewpoint is still streaming in.
     */
    private void photoMatch(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        if (!renderer.hasPhoto()) {
            throw new IllegalArgumentException("no photo loaded (POST /photo first)");
        }
        com.peaknav.skyline.SkylineMatcher.Match m = renderer.matchPhoto(body.getInt("attempts", 3));
        if (m == null) {
            json(x, 200, "{\"ok\":true,\"matched\":false,\"error\":\"no position, or the terrain never loaded far enough\"}");
            return;
        }
        json(x, 200, String.format(Locale.ENGLISH,
                "{\"ok\":true,\"matched\":true,\"bearing_deg\":%.2f,\"pitch_deg\":%.2f,\"vertical_fov_deg\":%.2f,"
                        + "\"roll_deg\":%.2f,\"cost\":%.6f,\"ratio\":%.3f,\"relief_deg\":%.2f,\"confident\":%s}",
                m.bearingDeg, m.pitchDeg, m.verticalFovDeg, m.rollDeg, m.cost, m.ratio(), m.reliefDeg, m.isConfident()));
    }

    /** How the terrain is drawn over the photo: {@code outline_alpha} and {@code terrain_alpha}, 0..1. */
    private void photoOverlay(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        Float outline = body.has("outline_alpha") ? Float.valueOf(body.getFloat("outline_alpha")) : null;
        Float terrain = body.has("terrain_alpha") ? Float.valueOf(body.getFloat("terrain_alpha")) : null;
        renderer.setPhotoOverlay(outline, terrain);
        json(x, 200, "{\"ok\":true}");
    }

    /** The current view as an image - the response body IS the picture. */
    private void frame(HttpExchange x) throws IOException {
        java.util.Map<String, String> q = query(x);
        String format = q.containsKey("format") ? q.get("format") : imageFormat;
        boolean ui = "true".equalsIgnoreCase(q.get("ui"));
        if (!format.equals("png") && !format.equals("jpg")) {
            throw new IllegalArgumentException("format wants png or jpg");
        }
        File tmp = File.createTempFile("peaknav-frame", "." + format);
        try {
            renderer.setImageFormat(format);
            if (ui) {
                renderer.captureWithUi(tmp);
            } else {
                renderer.capture(tmp);
            }
            byte[] bytes = Files.readAllBytes(tmp.toPath());
            x.getResponseHeaders().set("Content-Type",
                    format.equals("png") ? "image/png" : "image/jpeg");
            x.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface BoolSetter {
        void set(boolean value);
    }

    /**
     * Draws the paths of a GPX document: {@code {"xml": "<gpx ...>"}} with the document inline,
     * or {@code {"path": "/file.gpx"}} naming a file the renderer can read. Loads add to what is
     * drawn; the camera is left where it is.
     */
    private void gpx(HttpExchange x) throws IOException {
        JsonValue body = body(x);
        String xml;
        if (body.has("xml")) {
            xml = body.getString("xml");
        } else if (body.has("path")) {
            xml = new String(Files.readAllBytes(new File(body.getString("path")).toPath()),
                    StandardCharsets.UTF_8);
        } else {
            json(x, 400, "{\"ok\":false,\"error\":\"give xml or path\"}");
            return;
        }
        int paths = renderer.loadGpx(xml);
        json(x, 200, "{\"ok\":true,\"paths\":" + paths + "}");
    }

    private static void setIf(JsonValue body, String key, BoolSetter setter) {
        if (body.has(key)) {
            setter.set(body.getBoolean(key));
        }
    }

    private static java.util.Map<String, String> query(HttpExchange x) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String query = x.getRequestURI().getQuery();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(pair, "");
            } else {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    private static JsonValue require(JsonValue body, String key) {
        JsonValue v = body.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return v;
    }

    private static JsonValue body(HttpExchange x) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = x.getRequestBody()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
        }
        String text = buffer.toString("UTF-8");
        if (text.trim().isEmpty()) {
            return new JsonValue(JsonValue.ValueType.object);
        }
        try {
            return new JsonReader().parse(text);
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException("request body is not JSON: " + bad.getMessage());
        }
    }

    private static void json(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void resource(HttpExchange x, String name, String contentType) throws IOException {
        try (InputStream in = RestServer.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                json(x, 500, "{\"error\":\"resource missing from jar: " + name + "\"}");
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            byte[] bytes = buffer.toByteArray();
            x.getResponseHeaders().set("Content-Type", contentType);
            x.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String quote(String s) {
        return "\"" + String.valueOf(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
