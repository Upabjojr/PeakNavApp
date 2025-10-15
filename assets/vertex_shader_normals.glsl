attribute vec4 a_position;
varying vec4 v_position;

uniform int u_edgeLength;

void main() {
    float e = float(u_edgeLength);
    v_position = a_position;
    gl_Position = vec4(
    2.0*a_position.x-1.0,
    2.0*a_position.y-1.0,
    0.5,
    1.0
    );
}
