#ifdef GL_ES
precision highp float;
#else
#endif

varying vec3 v_pointing;

#define latToMeterConst (111194.9266)

void main() {
    float meters = length(v_pointing) * latToMeterConst;

    // Reflected (Gray code like) base-256 encoding of the distance in meters: every
    // second run of the low byte is mirrored, so two neighbouring distances always
    // land on two neighbouring colors and a rounding error costs a meter instead of
    // flipping a whole byte. fragment_shader_outlines.glsl decodes it back.
    float mid = floor(meters / 256.0);
    float d3 = floor(meters / 65536.0);
    float d2 = mid - d3 * 256.0;
    float d1 = min(meters - mid * 256.0, 255.0);

    // fract(x*0.5) is 0.0 for an even x and 0.5 for an odd one.
    if (fract(d3 * 0.5) > 0.25) {
        d2 = 255.0 - d2;
    }
    if (fract(d2 * 0.5) > 0.25) {
        d1 = 255.0 - d1;
    }

    gl_FragColor = vec4(d1, d2, d3, 255.0) / 255.0;
}
