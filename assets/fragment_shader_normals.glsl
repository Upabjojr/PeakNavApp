
uniform sampler2D u_eleTexture;
uniform int u_edgeLength;
uniform float u_Scale;

varying vec4 v_position;

float getElevation(int x, int y) {
    vec2 crdf = vec2(float(x)/float(u_edgeLength), float(y)/float(u_edgeLength));
    float p1 = texture2D(u_eleTexture, crdf).y;
    float p2 = texture2D(u_eleTexture, crdf).z;
    float ele = (p1 + p2/256.0)*255.0/256.0/10.0 - 0.01;
    return ele;
}

vec3 do_compute_normals() {
    float edgeLength = float(u_edgeLength);
    int x = int(v_position.x * edgeLength);
    int y = int(v_position.y * edgeLength);
    float eleD = getElevation(x, y-1);
    float eleU = getElevation(x, y+1);
    float eleR = getElevation(x-1, y);
    float eleL = getElevation(x+1, y);
    vec3 nrm = vec3(
        eleR - eleL,
        eleU - eleD,
        u_Scale
    );
    nrm = normalize(nrm);
    vec3 nrm2 = vec3(
        0.5*nrm.x + 0.5,
        0.5*nrm.y + 0.5,
        0.5*nrm.z + 0.5
    );
    return nrm2;
}


void main() {
    gl_FragColor.w = 1.0;
    gl_FragColor.xyz = do_compute_normals();
}
