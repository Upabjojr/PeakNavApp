#ifdef GL_ES
precision float;
#else
#endif
varying vec2 v_position;
uniform sampler2D u_eleTextureJpg;
uniform sampler2D u_eleTexturePng;

uniform int u_edgeLength;
uniform int u_rescaleFactor;


int modI(int a, int b) {
    int c = a / b;
    return a - b*c;
}

int convert_ele_2pixels_to_1pixel(float hjpg, float hpng) {
    int val = int(hjpg*255.0);
    int val2 = int(hpng*255.0 - 128.0);
    if (modI(val2, 2) == 1) {
        val = (255 - val);
    }
    val *= 4;
    val += 1024*val2;
    return val;
}

float getElevation(float px, float py) {
    vec2 pos = vec2(px, py);
    float hjpg = texture2D(u_eleTextureJpg, pos).w;
    float hpng = texture2D(u_eleTexturePng, pos).w;
    return float(convert_ele_2pixels_to_1pixel(hjpg, hpng));
}

vec3 convert_1pixel_to_2pixel(int elevation) {
    int a = modI(elevation, 1024) / 4;
    int b = elevation / 1024;
    if (modI(b, 2) == 1) {
        a = 255 - a;
    }
    int ipng = 128 + b;
    return vec3(float(a)/255.0, float(ipng)/255.0, 0.0);
}

vec3 do_rescale() {
    float dz = 1.0 / float(u_edgeLength - 1);
    float dx = dz;
    float dy = dz;
    vec2 p = v_position.xy;
    // p -= 1.0/255.0;
    // p *= 255.0/253.0;
    p *= float(u_edgeLength - u_rescaleFactor)/float(u_edgeLength - 1);
    float totalEle = 0.0;
    for (int x = 0; x < u_rescaleFactor; x++) {
        for (int y = 0; y < u_rescaleFactor; y++) {
            totalEle += getElevation(p.x + float(x)*dx, p.y + float(y)*dy);
        }
    }
    float rf = float(u_rescaleFactor);
    float rf2 = rf*rf;
    int totalEleI = int(totalEle/rf2);
    return convert_1pixel_to_2pixel(totalEleI);
}

void main() {
    gl_FragColor.xyz = do_rescale();
    gl_FragColor.a = 1.0;
}
