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
uniform vec4 u_cameraDirection;

// Unit vector pointing towards the sun, in terrain space (x east, y north, z up).
// Set from com.peaknav.viewer.SunLight, see TileBatchRenderer.
uniform vec3 u_sunDirection;

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

float terrainLight(vec3 normal) {
    // Interpolating per-vertex normals shortens them, so without normalising, a fragment in the
    // middle of a triangle comes out darker than the same surface at its corners.
    vec3 n = normalize(normal);

    float sun = clamp((dot(n, u_sunDirection) + SUN_WRAP) / (1.0 + SUN_WRAP), 0.0, 1.0);
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
