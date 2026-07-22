#ifdef GL_ES
precision highp float;
#else
#endif

varying vec2 v_texCoords;
uniform sampler2D u_texture;

uniform float u_backgroundAlpha;
uniform float u_textureWidth;
uniform float u_textureHeight;

uniform float u_polyXa;
uniform float u_polyXc;
uniform float u_polyYa;
uniform float u_polyYc;

/*
Get distance back in Python:

d1, d2, d3, _ = img[240, 320, :]
pd = 256*d3 + ((256 - d1) if d3 % 2 == 1 else d1)
find = 5*(pd/256**2/0.1)**2
Radi = 6300000
lati = 2*3.14159*Radi/360
lati*find
*/

// Decodes the reflected base-256 distance written by
// fragment_shader_pseudodistances.glsl back into meters.
float get_distance_from_color(vec4 color) {
    // Round to the byte that was actually written. Truncating instead (int(255.0*c))
    // decodes a 200.99998 as 200, which inverts the parity tests below, un-mirrors a
    // byte that was never mirrored and so misreads the distance by up to 255 m at
    // every 256 m boundary. That is what painted evenly spaced contour lines over the
    // terrain on GPUs whose texture fetch does not land exactly on n/255.
    float d1 = floor(color.x * 255.0 + 0.5);
    float d2 = floor(color.y * 255.0 + 0.5);
    float d3 = floor(color.z * 255.0 + 0.5);

    // fract(x*0.5) is 0.0 for an even x and 0.5 for an odd one.
    if (fract(d2 * 0.5) > 0.25) {
        d1 = 255.0 - d1;
    }
    if (fract(d3 * 0.5) > 0.25) {
        d2 = 255.0 - d2;
    }

    // The bytes are base 256, not base 255: the old 255 factors under-read every
    // distance by 0.4% and dropped 255 m at each 65536 m boundary.
    return d1 + 256.0 * d2 + 65536.0 * d3;
}

const float outlineLimitFactor = 40.0;
const float outlineRatioLimLow = 0.8;
const float outlineRatioLimHigh = 1.2;

// True when the distance steps on either side of the pixel are both large and
// lopsided, i.e. an occlusion edge rather than a slope seen at a grazing angle.
// The ratio test is written as products so that a zero step on one side (a
// silhouette against the sky) stays well defined instead of leaning on the
// driver's choice of result for a division by zero.
bool isOutline(float dA, float dB, float limit) {
    if (dA <= limit && dB <= limit)
        return false;
    return (dA > outlineRatioLimHigh * dB) || (dA < outlineRatioLimLow * dB);
}

void main() {

    vec2 uv = v_texCoords.xy;

    float dist = get_distance_from_color(texture2D(u_texture, uv));

    // DRAW OUTLINES OF MOUNTAIN EDGES:

    if (dist <= 100.0)
        discard;

    float dx = 1.0 / u_textureWidth;
    float dy = 1.0 / u_textureHeight;

    // Only the four edge neighbours matter; the four diagonals used to be sampled
    // and decoded as well, and then thrown away.
    float angleX = u_polyXa * uv.x * uv.x + u_polyXc;
    float limitX = outlineLimitFactor * dist * angleX;

    float dXl = abs(get_distance_from_color(texture2D(u_texture, vec2(uv.x - dx, uv.y))) - dist);
    float dXr = abs(get_distance_from_color(texture2D(u_texture, vec2(uv.x + dx, uv.y))) - dist);

    if (!isOutline(dXl, dXr, limitX)) {

        float angleY = u_polyYa * uv.y * uv.y + u_polyYc;
        float limitY = outlineLimitFactor * dist * angleY;

        float dYd = abs(get_distance_from_color(texture2D(u_texture, vec2(uv.x, uv.y - dy))) - dist);
        float dYu = abs(get_distance_from_color(texture2D(u_texture, vec2(uv.x, uv.y + dy))) - dist);

        if (!isOutline(dYd, dYu, limitY))
            discard;
    }

    gl_FragColor = vec4(0.0, 0.0, 0.0, u_backgroundAlpha);
}
