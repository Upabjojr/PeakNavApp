attribute vec3 a_position;
uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;
uniform vec4 u_cameraPosition;

attribute vec2 a_texCoord0;
varying vec2 v_texCoord0;
attribute vec3 a_normal;
varying vec3 v_normal;
varying float distance;
// Height above sea level in metres, for the contour lines. The mesh's z has the round Earth's
// drop taken off it (MapTile.correctForRoundEarth) so that far terrain sinks the way the real
// horizon does; it is put back here, from the same formula and the same reference point, so a
// contour follows the real elevation however far away the terrain is.
varying float v_elevation;
uniform vec2 u_roundEarthRef;   // the point the tile was flattened around, in world units

#define near (0.001)
#define far (15.0)
// The world's units are degrees of latitude ("latits"): METERS_PER_LATIT metres each, and the
// Earth's radius RADIUS_LATITS of them (180 / pi). Both as the fragment shader and Units have them.
#define METERS_PER_LATIT (111195.0)
#define RADIUS_LATITS (57.29578)
#define DEG2RAD (0.017453292519943295)

void main() {
    v_texCoord0 = a_texCoord0;
    v_normal = a_normal;

    vec4 pos = u_worldTrans * vec4(a_position, 1.0);

    distance = float(length(u_cameraPosition.xyz - pos.xyz));

    // The drop R(sec theta - 1) as its series to the fourth power: the direct form takes the
    // small difference of two numbers near 1, which single precision cannot hold.
    float theta = length(pos.xy - u_roundEarthRef) * DEG2RAD;
    float t2 = theta * theta;
    float drop = RADIUS_LATITS * (0.5 * t2 + (5.0 / 24.0) * t2 * t2);
    v_elevation = (pos.z + drop) * METERS_PER_LATIT;

    vec4 clipPosition = u_projViewTrans * pos;
    float w = clipPosition.w;
    clipPosition.z = (2.0 * log2(near * w + 1.0) / log2(near * far + 1.0) - 1.0) * w;

    gl_Position = clipPosition;
}
