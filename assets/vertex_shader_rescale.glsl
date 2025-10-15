attribute vec4 a_position;
uniform mat4 u_projTrans;
varying vec2 v_position;
uniform int u_edgeLength;

void main()
{
    v_position = a_position.xy;
    gl_Position = vec4(
        2.0*a_position.x-1.0,
        2.0*a_position.y-1.0,
        0.5,
        1.0
    );
}
