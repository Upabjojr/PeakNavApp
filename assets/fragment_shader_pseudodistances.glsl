#ifdef GL_ES
precision highp float;
#else
#endif

varying vec3 v_position;
varying vec3 v_pointing;
varying vec3 v_normal;

uniform vec4 u_cameraPosition;
uniform vec4 u_cameraDirection;



float modD(float a, float b) {
    // TODO: convert all to int?
    float fa = (a);
    float fb = (b);
    float fc = fa - fb*floor(fa/fb);
    return fc;
}

int reinvert(int val) {
    if (val >= 256)
        return 511 - val;
    else
        return val;
}

#define PI (3.141592636)
#define radiusOfEarth (6371000.0)
#define latToMeterConst (111194.9266)

float convertLatitsToMeters(float latit) {
    return (latit*latToMeterConst);
}

void main() {
    float distance = float(length(v_pointing));

    float meters = convertLatitsToMeters(distance);

    float d1 = modD(meters, 256.0);
    float d2 = modD(floor(meters/256.0), 256.0);
    float d3 = floor(meters/256.0/256.0);

    if (modD(d3, 2.0) >= 0.5) {
        d2 = 255.0 - d2;
    }
    if (modD(d2, 2.0) >= 0.5) {
        d1 = 255.0 - d1;
    }

    gl_FragColor.x = float(d1)/255.0;
    gl_FragColor.y = float(d2)/255.0;
    gl_FragColor.z = float(d3)/255.0;
    gl_FragColor.w = 1.0;

}
