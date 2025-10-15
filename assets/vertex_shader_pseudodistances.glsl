#ifdef GL_ES
precision highp float;
#else
#endif

attribute vec3 a_position;
attribute vec3 a_normal;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

varying vec3 v_position;
varying vec3 v_pointing;
varying vec3 v_normal;

uniform vec4 u_cameraPosition;
uniform vec4 u_cameraDirection;

#define near (0.001)
#define far (15.0)

void main() {

    vec4 pos = u_worldTrans * vec4(a_position, 1.0);

    v_position = pos.xyz;

    v_pointing = u_cameraPosition.xyz - v_position.xyz;
    v_normal = a_normal;

    // vertexDirection = normalize(pos.xyz - u_cameraPosition.xyz);

    // vec3 vertexDirection1 = pos.xyz - u_cameraPosition.xyz;
    // vertexDirection = vertexDirection1;

    vec4 clipPosition = u_projViewTrans * pos;
    float w = clipPosition.w;
    clipPosition.z = (2.0 * log2(near * w + 1.0) / log2(near * far + 1.0) - 1.0) * w;

    gl_Position = clipPosition;

    /*
    #ifdef specularFlag
    v_lightSpecular = vec3(0.0);
    vec3 viewVec = normalize(u_cameraPosition.xyz - pos.xyz);
    #endif // specularFlag
    */
}
