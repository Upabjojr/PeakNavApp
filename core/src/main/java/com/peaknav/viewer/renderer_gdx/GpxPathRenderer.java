package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.peaknav.elevation.ElevationUtils;
import com.peaknav.gpx.GpxManager;
import com.peaknav.gpx.GpxTrack;
import com.peaknav.utils.Units;
import com.peaknav.viewer.PerspectiveCameraExt;

import java.util.List;

/**
 * Draws the loaded GPX paths onto the 3D terrain as flat ribbons draped on the surface (sampling
 * the loaded elevation, or the GPX's own elevation where terrain isn't loaded yet).
 *
 * <p>The colour is an animated flow: a short repeating band pattern that marches along each track
 * toward its end, so the direction — and the motion — of travel read at a glance. Each vertex
 * carries a monotonic "flow" value; where the GPX has timestamps that value is the elapsed time
 * along the track (so the animation replays the real timing), otherwise it is distance travelled.
 *
 * <p>The mesh is rebuilt on the render thread whenever the set of paths changes ({@link
 * GpxManager#getVersion()}) or the map target moves (which shifts the round-earth correction), and
 * refined for a while afterwards as more terrain tiles finish loading. Only the {@code u_time}
 * uniform changes per frame, so the animation itself costs nothing to keep running.
 */
public class GpxPathRenderer {

    private static final String VERTEX_SHADER =
            "attribute vec3 a_position;\n" +
            "attribute float a_flow;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "varying float v_flow;\n" +
            "void main() {\n" +
            "  v_flow = a_flow;\n" +
            "  gl_Position = u_projViewTrans * vec4(a_position, 1.0);\n" +
            "}\n";

    // highp (as the outline shader uses) so fract() of the accumulated flow/time stays smooth on a
    // long track instead of banding on mobile mediump.
    private static final String FRAGMENT_SHADER =
            "#ifdef GL_ES\n" +
            "precision highp float;\n" +
            "#endif\n" +
            "varying float v_flow;\n" +
            "uniform float u_time;\n" +
            "uniform float u_patternLength;\n" +
            "uniform float u_speed;\n" +
            "uniform vec3 u_colorBase;\n" +
            "uniform vec3 u_colorFlow;\n" +
            "void main() {\n" +
            // Repeating 0..1 ramp along the track; subtracting time slides it toward the end.
            "  float m = fract(v_flow / u_patternLength - u_time * u_speed);\n" +
            // A bright comet head near m=1 with a tail fading back toward the start.
            "  float comet = pow(m, 4.0);\n" +
            "  vec3 col = mix(u_colorBase, u_colorFlow, comet);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    // Base line colour and the bright flowing highlight.
    private static final float BASE_R = 0.10f, BASE_G = 0.38f, BASE_B = 0.85f;
    private static final float FLOW_R = 0.85f, FLOW_G = 1.00f, FLOW_B = 0.92f;

    private static final float HALF_WIDTH_METERS = 6f;      // ~12 m wide painted trail
    private static final float SURFACE_OFFSET_METERS = 3f;   // lift a touch so it sits on the ground

    // Band length + flow speed, per mode. Distance mode works in latits; time mode in seconds.
    private static final float DIST_PATTERN_LATITS = Units.convertMetersToLatits(110f);
    private static final float DIST_SPEED = 1.85f;           // ~93 m/s of highlight travel
    private static final float TIME_PATTERN_SECONDS = 10f;
    private static final float TIME_SPEED = 1.00f;           // ~10x realtime replay

    private final ShaderProgram shader;
    private Mesh mesh;
    private int vertexCount;
    private int meshCapacity;

    private int builtVersion = -1;
    private double lastTargetLat = Double.NaN;
    private double lastTargetLon = Double.NaN;
    private boolean lastBuildHadMissingTerrain;
    private int frame;

    private float animTime;
    private float flowPatternLength = DIST_PATTERN_LATITS;
    private float flowSpeed = DIST_SPEED;

    public GpxPathRenderer() {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            System.err.println("[GPX] path shader failed to compile: " + shader.getLog());
        }
    }

    public void render(PerspectiveCameraExt cam) {
        GpxManager manager = getC().gpxManager;
        if (manager == null || cam == null || !shader.isCompiled()) {
            return;
        }
        frame++;
        double targetLat = getC().L.getTargetLatitude();
        double targetLon = getC().L.getTargetLongitude();
        boolean targetMoved = (targetLat != lastTargetLat) || (targetLon != lastTargetLon);
        boolean refine = lastBuildHadMissingTerrain && (frame % 45 == 0);
        if (manager.getVersion() != builtVersion || targetMoved || refine) {
            rebuild(manager.getTracks(), targetLat, targetLon);
        }
        if (mesh == null || vertexCount == 0) {
            return;
        }
        // Advance the animation. Wrap on a whole number of pattern periods (1/speed seconds) so the
        // motion stays seamless and u_time never grows large enough to lose float precision.
        animTime += Gdx.graphics.getDeltaTime();
        float wrap = 256f / flowSpeed;
        if (animTime >= wrap) {
            animTime -= wrap;
        }

        // Draw over the terrain: the flat ribbon is back-facing from above (so culling off), and
        // GPS vs terrain elevation rarely match to the metre, so depth-testing would bury a track
        // recorded a little low. Drawing without it keeps the path always visible on the mountains.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        shader.bind();
        shader.setUniformMatrix("u_projViewTrans", cam.combined);
        shader.setUniformf("u_time", animTime);
        shader.setUniformf("u_patternLength", flowPatternLength);
        shader.setUniformf("u_speed", flowSpeed);
        shader.setUniformf("u_colorBase", BASE_R, BASE_G, BASE_B);
        shader.setUniformf("u_colorFlow", FLOW_R, FLOW_G, FLOW_B);
        mesh.render(shader, GL20.GL_TRIANGLES, 0, vertexCount);
    }

    private void rebuild(List<GpxTrack> tracks, double targetLat, double targetLon) {
        builtVersion = getC().gpxManager.getVersion();
        lastTargetLat = targetLat;
        lastTargetLon = targetLon;
        lastBuildHadMissingTerrain = false;

        int totalVerts = 0;
        for (GpxTrack track : tracks) {
            if (track.size() >= 2) {
                totalVerts += (track.size() - 1) * 6; // two triangles per segment
            }
        }
        vertexCount = totalVerts;
        if (totalVerts == 0) {
            return;
        }
        ensureMeshCapacity(totalVerts);

        // Time mode only when every drawn track is fully timestamped; otherwise flow by distance.
        boolean timeMode = allTracksTimed(tracks);
        flowPatternLength = timeMode ? TIME_PATTERN_SECONDS : DIST_PATTERN_LATITS;
        flowSpeed = timeMode ? TIME_SPEED : DIST_SPEED;

        float halfWidth = Units.convertMetersToLatits(HALF_WIDTH_METERS);
        float offset = Units.convertMetersToLatits(SURFACE_OFFSET_METERS);
        float[] verts = new float[totalVerts * 4]; // x, y, z, flow
        int v = 0;
        for (GpxTrack track : tracks) {
            List<GpxTrack.Point> pts = track.getPoints();
            int n = pts.size();
            if (n < 2) {
                continue;
            }
            float[] wx = new float[n];
            float[] wy = new float[n];
            float[] wz = new float[n];
            for (int i = 0; i < n; i++) {
                GpxTrack.Point p = pts.get(i);
                // Longitude is scaled by cos(targetLat) — a single reference latitude — exactly as
                // the terrain mesh and POIs do (see ElevationImageAbstract / PoiObject). Using each
                // point's own latitude instead skews x by lon*(cos(pointLat)-cos(targetLat)), a
                // large error since lon is absolute, which stretched the path away from the start.
                wx[i] = (float) Units.convertLonitsToLatits(p.lon, targetLat);
                wy[i] = p.lat;
                wz[i] = drapeZ(p) + offset;
            }
            float[] flow = flowValues(pts, wx, wy, timeMode);

            for (int i = 0; i + 1 < n; i++) {
                float dx = wx[i + 1] - wx[i];
                float dy = wy[i + 1] - wy[i];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-9f) {
                    dx = 0f;
                    dy = 0f;
                } else {
                    dx /= len;
                    dy /= len;
                }
                float px = -dy * halfWidth; // horizontal perpendicular, half a ribbon wide
                float py = dx * halfWidth;
                float fa = flow[i];
                float fb = flow[i + 1];
                v = putVertex(verts, v, wx[i] + px, wy[i] + py, wz[i], fa);
                v = putVertex(verts, v, wx[i] - px, wy[i] - py, wz[i], fa);
                v = putVertex(verts, v, wx[i + 1] + px, wy[i + 1] + py, wz[i + 1], fb);
                v = putVertex(verts, v, wx[i + 1] + px, wy[i + 1] + py, wz[i + 1], fb);
                v = putVertex(verts, v, wx[i] - px, wy[i] - py, wz[i], fa);
                v = putVertex(verts, v, wx[i + 1] - px, wy[i + 1] - py, wz[i + 1], fb);
            }
        }
        mesh.setVertices(verts, 0, v);
    }

    private static boolean allTracksTimed(List<GpxTrack> tracks) {
        boolean any = false;
        for (GpxTrack track : tracks) {
            if (track.size() < 2) {
                continue;
            }
            any = true;
            for (GpxTrack.Point p : track.getPoints()) {
                if (!p.hasTime) {
                    return false;
                }
            }
        }
        return any;
    }

    /** Monotonic flow value per point: elapsed seconds from the track start in time mode, else the
     *  cumulative horizontal distance (latits) travelled. Both march the pattern toward the end. */
    private static float[] flowValues(List<GpxTrack.Point> pts, float[] wx, float[] wy,
                                      boolean timeMode) {
        int n = pts.size();
        float[] flow = new float[n];
        if (timeMode) {
            long t0 = pts.get(0).timeMillis;
            for (int i = 0; i < n; i++) {
                flow[i] = (pts.get(i).timeMillis - t0) / 1000f;
            }
        } else {
            flow[0] = 0f;
            for (int i = 1; i < n; i++) {
                float dx = wx[i] - wx[i - 1];
                float dy = wy[i] - wy[i - 1];
                flow[i] = flow[i - 1] + (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return flow;
    }

    private float drapeZ(GpxTrack.Point p) {
        // Rendered height = elevation (latits) minus the round-earth correction, exactly how POIs
        // are placed. Prefer the loaded terrain (so the path hugs the surface), but that isn't
        // always available; fall back to the GPX's own recorded elevation (which is on the
        // mountain anyway), and only as a last resort to the current terrain height so the path is
        // never dumped at sea level.
        float corr = ElevationUtils.getElevationCorrectionForRoundEarth(p.lat, p.lon);
        Float terrain = ElevationUtils.getElevationLatitsFromMaxCoords(p.lon, p.lat, false);
        if (terrain != null) {
            return terrain - corr;
        }
        if (p.hasElevation) {
            // Keep refining while terrain streams in, so we can later snap onto the surface.
            lastBuildHadMissingTerrain = true;
            return Units.convertMetersToLatits(p.eleMeters) - corr;
        }
        lastBuildHadMissingTerrain = true;
        return (float) getC().L.getCurrentTerrainEle() - corr;
    }

    private static int putVertex(float[] verts, int v, float x, float y, float z, float flow) {
        verts[v++] = x;
        verts[v++] = y;
        verts[v++] = z;
        verts[v++] = flow;
        return v;
    }

    private void ensureMeshCapacity(int neededVerts) {
        if (mesh != null && meshCapacity >= neededVerts) {
            return;
        }
        if (mesh != null) {
            mesh.dispose();
        }
        meshCapacity = Math.max(neededVerts, 1024);
        mesh = new Mesh(false, meshCapacity, 0,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
                new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_flow"));
    }

    public void dispose() {
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
        if (shader != null) {
            shader.dispose();
        }
    }
}
