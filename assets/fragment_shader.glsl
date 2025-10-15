#ifdef GL_ES
precision highp float;
#else
#endif

uniform vec2 a_texCoord0;
uniform int u_whiteBackground;
uniform int u_roadsSet;

varying vec2 v_texCoord0;

varying vec3 v_normal;
varying float distance;

uniform sampler2D u_textureSatellite;
uniform sampler2D u_textureSatBlock;
uniform sampler2D u_textureRoads;
uniform vec4 u_cameraDirection;

#define MAX_ROAD_DISTANCE (0.2)
const float min = 0.03125;  // = pow(2.0, -5.0);

void main() {

    if (u_whiteBackground == 0) {
        vec4 satellite = texture2D(u_textureSatellite, v_texCoord0).rgba;
        gl_FragColor = satellite;
    } else if (u_whiteBackground == 1) {
        gl_FragColor.rgba = vec4(1.0);

        // float grey = max(0.5, dot(normalize(vec3(0.5, 0.5, 0.5)), v_normal));
        float grey = 0.5 + 0.5*dot(normalize(vec3(0.5, 0.5, 0.5)), v_normal);

        gl_FragColor.x = grey;
        gl_FragColor.y = grey;
        gl_FragColor.z = grey;
        gl_FragColor.a = 1.0;
    }

    if (u_roadsSet == 1) {
        vec4 roads = texture2D(u_textureRoads, v_texCoord0).rgba;
        if (roads.a > 0.01) {
            if (distance > MAX_ROAD_DISTANCE)
                return;
            float r = distance / MAX_ROAD_DISTANCE;
            // libgdx equivalent: Interpolation.Exp5In
            roads.a *= (1.0 - pow(2.0, 5.0*(r-1.0)))/(1.0-min);
            gl_FragColor = gl_FragColor * (1.0 - roads.a) + roads * roads.a;
            return;
        }
    }

}
