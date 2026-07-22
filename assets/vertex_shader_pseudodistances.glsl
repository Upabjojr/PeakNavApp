#ifdef GL_ES
precision highp float;
#else
#endif

attribute vec3 a_position;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

uniform vec4 u_cameraPosition;

// The only thing the fragment shader needs is the camera-to-vertex vector: keeping
// the position and the normal around as well used to cost two extra interpolators
// per fragment for nothing.
varying vec3 v_pointing;

#define near (0.001)
#define far (15.0)

void main() {

    vec4 pos = u_worldTrans * vec4(a_position, 1.0);

    v_pointing = u_cameraPosition.xyz - pos.xyz;

    vec4 clipPosition = u_projViewTrans * pos;
    float w = clipPosition.w;
    clipPosition.z = (2.0 * log2(near * w + 1.0) / log2(near * far + 1.0) - 1.0) * w;

    gl_Position = clipPosition;
}
