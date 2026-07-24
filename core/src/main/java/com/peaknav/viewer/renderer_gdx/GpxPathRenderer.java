package com.peaknav.viewer.renderer_gdx;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
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
 * Draws the loaded GPX paths onto the 3D terrain. Each path is a flat ribbon draped on the terrain
 * surface (sampling the loaded elevation, or the GPX's own elevation where terrain isn't loaded
 * yet), coloured per track and depth-tested so mountains in front of it occlude it.
 *
 * <p>The mesh is rebuilt on the render thread whenever the set of paths changes ({@link
 * GpxManager#getVersion()}) or the map target moves (which shifts the round-earth correction), and
 * refined for a while afterwards as more terrain tiles finish loading.
 */
public class GpxPathRenderer {

    private static final String VERTEX_SHADER =
            "attribute vec3 a_position;\n" +
            "attribute vec4 a_color;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "  v_color = a_color;\n" +
            "  gl_Position = u_projViewTrans * vec4(a_position, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "  gl_FragColor = v_color;\n" +
            "}\n";

    // Distinct, readable colours cycled across successive tracks.
    private static final Color[] PALETTE = {
            new Color(0.94f, 0.28f, 0.22f, 1f),  // red
            new Color(0.16f, 0.55f, 0.96f, 1f),  // blue
            new Color(0.30f, 0.72f, 0.36f, 1f),  // green
            new Color(1.00f, 0.62f, 0.10f, 1f),  // orange
            new Color(0.64f, 0.30f, 0.86f, 1f),  // violet
    };

    private static final float HALF_WIDTH_METERS = 6f;     // ~12 m wide painted trail
    private static final float SURFACE_OFFSET_METERS = 3f;  // lift a touch so it sits on the ground

    private final ShaderProgram shader;
    private Mesh mesh;
    private int vertexCount;
    private int meshCapacity;

    private int builtVersion = -1;
    private double lastTargetLat = Double.NaN;
    private double lastTargetLon = Double.NaN;
    private boolean lastBuildHadMissingTerrain;
    private int frame;

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
        // Draw the path over the terrain. Two things kept it invisible before and both are handled
        // here: the flat ribbon is back-facing from above (so culling must be off), and GPS/terrain
        // elevations rarely match to the metre — with depth testing on, a track recorded a little
        // low vanishes under the surface. Drawing without the depth test keeps it always visible on
        // top of the mountains; the small surface offset keeps it hugging them.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        shader.bind();
        shader.setUniformMatrix("u_projViewTrans", cam.combined);
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

        float halfWidth = Units.convertMetersToLatits(HALF_WIDTH_METERS);
        float offset = Units.convertMetersToLatits(SURFACE_OFFSET_METERS);
        float[] verts = new float[totalVerts * 4]; // x, y, z, packed colour
        int v = 0;
        int trackIndex = 0;
        for (GpxTrack track : tracks) {
            List<GpxTrack.Point> pts = track.getPoints();
            int n = pts.size();
            if (n < 2) {
                trackIndex++;
                continue;
            }
            float packed = PALETTE[trackIndex % PALETTE.length].toFloatBits();
            float[] wx = new float[n];
            float[] wy = new float[n];
            float[] wz = new float[n];
            for (int i = 0; i < n; i++) {
                GpxTrack.Point p = pts.get(i);
                wx[i] = (float) Units.convertLonitsToLatits(p.lon, p.lat);
                wy[i] = p.lat;
                wz[i] = drapeZ(p) + offset;
            }
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
                v = putVertex(verts, v, wx[i] + px, wy[i] + py, wz[i], packed);
                v = putVertex(verts, v, wx[i] - px, wy[i] - py, wz[i], packed);
                v = putVertex(verts, v, wx[i + 1] + px, wy[i + 1] + py, wz[i + 1], packed);
                v = putVertex(verts, v, wx[i + 1] + px, wy[i + 1] + py, wz[i + 1], packed);
                v = putVertex(verts, v, wx[i] - px, wy[i] - py, wz[i], packed);
                v = putVertex(verts, v, wx[i + 1] - px, wy[i + 1] - py, wz[i + 1], packed);
            }
            trackIndex++;
        }
        mesh.setVertices(verts, 0, v);
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

    private static int putVertex(float[] verts, int v, float x, float y, float z, float packed) {
        verts[v++] = x;
        verts[v++] = y;
        verts[v++] = z;
        verts[v++] = packed;
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
                new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE));
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
