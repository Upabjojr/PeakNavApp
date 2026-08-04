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
            } else if ("GET".equals(method) && "/providers".equals(path)) {
                providers(x);
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
        if (body.has("bearing_deg") || body.has("pitch_deg")) {
            renderer.aim(body.getFloat("bearing_deg", 0f), body.getFloat("pitch_deg", 0f));
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

    /** The current view as an image - the response body IS the picture. */
    private void frame(HttpExchange x) throws IOException {
        String query = x.getRequestURI().getQuery();
        String format = imageFormat;
        if (query != null && query.startsWith("format=")) {
            format = query.substring("format=".length());
        }
        if (!format.equals("png") && !format.equals("jpg")) {
            throw new IllegalArgumentException("format wants png or jpg");
        }
        File tmp = File.createTempFile("peaknav-frame", "." + format);
        try {
            renderer.setImageFormat(format);
            renderer.capture(tmp);
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

    private static void setIf(JsonValue body, String key, BoolSetter setter) {
        if (body.has(key)) {
            setter.set(body.getBoolean(key));
        }
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
