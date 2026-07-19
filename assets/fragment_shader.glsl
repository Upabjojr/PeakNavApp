#ifdef GL_ES
precision highp float;
#else
#endif

uniform vec2 a_texCoord0;
uniform int u_whiteBackground;
uniform int u_roadsSet;

varying vec2 v_texCoord0;

varying vec3 v_normal;
varying float distance;

uniform sampler2D u_textureSatellite;
uniform sampler2D u_textureSatBlock;
uniform sampler2D u_textureRoads;
uniform sampler2D u_textureGpx;
uniform int u_gpxSet;
// Seconds, ever-increasing (wrapped), for the animated GPX flow. Set from TileBatchRenderer.
uniform float u_time;
uniform vec4 u_cameraDirection;

// Unit vector pointing towards the sun, in terrain space (x east, y north, z up).
// Set from com.peaknav.viewer.SunLight, see TileBatchRenderer.
uniform vec3 u_sunDirection;
// 0 turns the sun off, leaving flat non-directional light. Toggled from the options menu.
uniform int u_sunEnabled;

#define MAX_ROAD_DISTANCE (0.2)
const float min = 0.03125;  // = pow(2.0, -5.0);

// Softens the terminator. A plain dot() drops every slope facing away from the sun to the same
// flat black, which loses all the shape on the shaded side of a ridge.
const float SUN_WRAP = 0.35;
// Hemispheric ambient: open sky above, weaker bounced light from the ground below.
const float AMBIENT_SKY = 0.28;
const float AMBIENT_GROUND = 0.10;
const float DIFFUSE = 0.75;
// How strongly relief may modulate satellite imagery. The photo already carries its own
// illumination, so this only shapes it; at 1.0 the terrain gets shaded twice and goes muddy.
const float SATELLITE_RELIEF = 0.8;
// Stands in for the directional term when the sun is switched off. It is roughly what flat ground
// receives with the sun on, so turning the sun off removes the shading without also dimming the
// whole map. Slopes still vary a little through the sky term, which keeps the terrain readable
// instead of collapsing it into one flat silhouette.
const float SUN_OFF_LEVEL = 0.72;

float terrainLight(vec3 normal) {
    // Interpolating per-vertex normals shortens them, so without normalising, a fragment in the
    // middle of a triangle comes out darker than the same surface at its corners.
    vec3 n = normalize(normal);

    float sun = SUN_OFF_LEVEL;
    if (u_sunEnabled == 1) {
        sun = clamp((dot(n, u_sunDirection) + SUN_WRAP) / (1.0 + SUN_WRAP), 0.0, 1.0);
    }
    float sky = 0.5 + 0.5 * n.z;                    // 1 facing straight up, 0 facing down
    float ambient = mix(AMBIENT_GROUND, AMBIENT_SKY, sky);

    return ambient + DIFFUSE * sun;
}

void main() {

    float light = terrainLight(v_normal);

    if (u_whiteBackground == 0) {
        vec4 satellite = texture2D(u_textureSatellite, v_texCoord0).rgba;

        // Relief relative to flat ground, so the imagery keeps its overall brightness and only
        // the slopes go lighter or darker. Multiplying by the light directly would darken
        // everything, since flat ground is never lit at full strength.
        float flatLight = terrainLight(vec3(0.0, 0.0, 1.0));
        float relief = mix(1.0, light / flatLight, SATELLITE_RELIEF);

        gl_FragColor = vec4(satellite.rgb * relief, satellite.a);
    } else if (u_whiteBackground == 1) {
        gl_FragColor = vec4(vec3(light), 1.0);
    }

    // GPX path, painted onto the tile surface (over the lit terrain, under the roads).
    // GpxTileRasterizer stores, per texel: the flow phase as sine (r) and cosine (g) so it can be
    // filtered across its wrap, and in alpha the coverage, which ramps down across the edge of the
    // line instead of switching off. Both are read with a linear filter, so the line keeps a
    // smooth edge and a smooth flow when the fly-over camera comes close, rather than breaking up
    // into texel blocks.
    if (u_gpxSet == 1) {
        vec4 gpx = texture2D(u_textureGpx, v_texCoord0);
        // Antialiased edge: pull a soft threshold out of the coverage ramp. Widening the band
        // here softens the outline; narrowing it sharpens.
        float cov = smoothstep(0.18, 0.62, gpx.a);
        if (cov > 0.002) {
            // Recover the phase angle from the filtered sine/cosine pair. Their magnitude sags
            // where the filter blends neighbours, but the direction — all we want — survives.
            vec2 sc = gpx.rg * 2.0 - 1.0;
            float phase = atan(sc.x, sc.y) * 0.15915494; // / (2*pi) -> [-0.5, 0.5]
            float m = fract(phase - u_time * 0.8);
            // A soft comet head with a faint trailing glow, so the pattern reads as flowing
            // rather than as a hard repeating stripe.
            float comet = smoothstep(0.55, 1.0, m);
            float trail = 0.35 * smoothstep(0.0, 0.55, m);
            vec3 gpxCol = mix(vec3(0.10, 0.45, 0.90), vec3(0.85, 1.0, 0.95), comet);
            gpxCol += vec3(0.05, 0.12, 0.18) * trail;
            // A darker rim where coverage is partial keeps the line legible over bright terrain.
            gpxCol = mix(vec3(0.03, 0.12, 0.28), gpxCol, smoothstep(0.35, 0.85, gpx.a));
            gl_FragColor = vec4(mix(gl_FragColor.rgb, gpxCol, cov), 1.0);
        }
    }

    if (u_roadsSet == 1) {
        vec4 roads = texture2D(u_textureRoads, v_texCoord0).rgba;
        if (roads.a > 0.01) {
            if (distance > MAX_ROAD_DISTANCE)
                return;
            float r = distance / MAX_ROAD_DISTANCE;
            // libgdx equivalent: Interpolation.Exp5In
            roads.a *= (1.0 - pow(2.0, 5.0*(r-1.0)))/(1.0-min);
            gl_FragColor = gl_FragColor * (1.0 - roads.a) + roads * roads.a;
            return;
        }
    }

}
