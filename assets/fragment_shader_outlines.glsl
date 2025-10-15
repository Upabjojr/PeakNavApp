#ifdef GL_ES
precision highp float;
#else
#endif

varying vec4 v_color;

varying vec2 v_texCoords;
uniform sampler2D u_texture;

uniform int u_pictureMode;
uniform float u_backgroundAlpha;
uniform float u_textureWidth;
uniform float u_textureHeight;

// uniform mat4 u_invProjectionView;

uniform float u_polyXa;
uniform float u_polyXc;
uniform float u_polyYa;
uniform float u_polyYc;

float threshold = 0.75;

int modI(int a, int b) {
    int c = a / b;
    return a - b*c;
}

int modF(float fa, float fb) {
    return modI(int(fa), int(fb));
}

float get_distance_from_color(vec4 color) {
    float d1 = 255.0*color.x;
    float d2 = 255.0*color.y;
    float d3 = 255.0*color.z;
    if (modF(d2, 2.0) == 1) {
        d1 = 255.0 - d1;
    }
    if (modF(d3, 2.0) == 1) {
        d2 = 255.0 - d2;
    }
    return d1 + 255.0*d2 + 255.0*255.0*d3;
}

vec4 k[9];

void fill_sobel_kernel(vec2 texCoord2) {
    float dx = 1.0 / u_textureWidth;
    float dy = 1.0 / u_textureHeight;

    k[0] = texture2D(u_texture, texCoord2 + vec2(-dx, -dy));
    k[1] = texture2D(u_texture, texCoord2 + vec2(0.0, -dy));
    k[2] = texture2D(u_texture, texCoord2 + vec2(dx, -dy));
    k[3] = texture2D(u_texture, texCoord2 + vec2(-dx, 0.0));
    k[4] = texture2D(u_texture, texCoord2);
    k[5] = texture2D(u_texture, texCoord2 + vec2(dx, 0.0));
    k[6] = texture2D(u_texture, texCoord2 + vec2(-dx, dy));
    k[7] = texture2D(u_texture, texCoord2 + vec2(0.0, dy));
    k[8] = texture2D(u_texture, texCoord2 + vec2(dx, dy));
}

/*
Get distance back in Python:

d1, d2, d3, _ = img[240, 320, :]
pd = 256*d3 + ((256 - d1) if d3 % 2 == 1 else d1)
find = 5*(pd/256**2/0.1)**2
Radi = 6300000
lati = 2*3.14159*Radi/360
lati*find
*/

float normcs(float x, float d) {
    return atan(x / log(d) / 5.0) / 3.14159 * 2.0;
}

void makeBlack() {
    gl_FragColor.x = 0.0;
    gl_FragColor.y = 0.0;
    gl_FragColor.z = 0.0;
    gl_FragColor.w = u_backgroundAlpha;
}

const float outlineLimitFactor = 40.0;
const float outlineRatioLimLow = 0.8;
const float outlineRatioLimHigh = 1.2;

const float dimmax = 4.0;
const float maxalpha = 0.3;

void main() {

    fill_sobel_kernel(v_texCoords.xy);

    float d[9];
    for (int i = 0; i < 9; i++) {
        d[i] = get_distance_from_color(k[i]);
    }

    float dist = d[4];

    // HIDE DISTANT TILES

    // if (dist > 400000.0 && u_pictureMode == 0) { // TODO: here?
        // make everything sky-colored:
        // gl_FragColor = vec4(0.529, 0.808, 0.980, 1.0);
        // return;
    // }

    // DRAW OUTLINES OF MOUNTAIN EDGES:

    float angleX = u_polyXa * v_texCoords.x * v_texCoords.x + u_polyXc;
    float angleY = u_polyYa * v_texCoords.y * v_texCoords.y + u_polyYc;

    float limitX = outlineLimitFactor * dist * angleX;
    float limitY = outlineLimitFactor * dist * angleY;

    if (dist > 100.0) {

        float dX1 = abs(d[3] - dist);
        float dX2 = abs(d[5] - dist);
        if (dX1 > limitX || dX2 > limitX) {
            float dX12 = dX1 / dX2;
            if (dX12 > outlineRatioLimHigh || dX12 < outlineRatioLimLow) {
                makeBlack();
                return;
            }
        }

        float dY1 = abs(d[1] - dist);
        float dY2 = abs(d[7] - dist);
        if (dY1 > limitY || dY2 > limitY) {
            float dY12 = dY1 / dY2;
            if (dY12 > outlineRatioLimHigh || dY12 < outlineRatioLimLow) {
                makeBlack();
                return;
            }
        }
    }

    gl_FragColor.x = 0.0;
    gl_FragColor.y = 0.0;
    gl_FragColor.z = 0.0;
    gl_FragColor.w = 0.0;

}
