attribute vec3 a_position;
uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;
uniform vec4 u_cameraPosition;

attribute vec2 a_texCoord0;
varying vec2 v_texCoord0;
attribute vec3 a_normal;
varying vec3 v_normal;
varying float distance;

#define near (0.001)
#define far (15.0)

void main() {
    v_texCoord0 = a_texCoord0;
    v_normal = a_normal;

    vec4 pos = u_worldTrans * vec4(a_position, 1.0);

    distance = float(length(u_cameraPosition.xyz - pos.xyz));

    vec4 clipPosition = u_projViewTrans * pos;
    float w = clipPosition.w;
    clipPosition.z = (2.0 * log2(near * w + 1.0) / log2(near * far + 1.0) - 1.0) * w;

    gl_Position = clipPosition;
}
