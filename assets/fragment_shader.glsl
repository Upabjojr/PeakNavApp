#ifdef GL_ES
#ifdef ROADS_DERIVATIVES
#extension GL_OES_standard_derivatives : enable
#endif
precision highp float;
#else
// Desktop GL: screen derivatives are part of the language.
#ifndef ROADS_DERIVATIVES
#define ROADS_DERIVATIVES
#endif
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
uniform sampler2D u_textureRoadsAux;
uniform sampler2D u_textureGpx;
uniform int u_gpxSet;
// Seconds, ever-increasing (wrapped), for the animated GPX flow and trail dashes. Set from
// TileBatchRenderer.
uniform float u_time;
uniform vec4 u_cameraDirection;

// Unit vector pointing towards the sun, in terrain space (x east, y north, z up).
// Set from com.peaknav.viewer.SunLight, see TileBatchRenderer.
uniform vec3 u_sunDirection;
// 0 turns the sun off, leaving flat non-directional light. Toggled from the options menu.
uniform int u_sunEnabled;

// ---- Roads, tracks, trails and pistes ------------------------------------------------------
// The road texture holds distances, not colours (see RoadTileRasterizer): R roads, G tracks,
// B trails, A pistes, each the distance in texels to the nearest line of that kind. The aux
// texture holds the nearest trail's dash phase (RG, as sine and cosine), the nearest piste's
// difficulty (B) and the nearest trail's difficulty (A). Everything a line looks like - width,
// colour, outline, dashes and their motion - is decided here, from the uniforms below, which
// come from RoadStyle and change without any tile being redrawn.
uniform float u_roadMetersPerTexel;  // ground metres covered by one texel of u_textureRoads
uniform float u_roadTexels;          // texels across u_textureRoads
uniform vec4 u_roadCore;
uniform vec4 u_trackCore;
uniform vec4 u_trailEasy;
uniform vec4 u_trailMountain;
uniform vec4 u_trailAlpine;
uniform vec3 u_dash;                 // x dashes per stored phase turn, y dash share, z cycles/s
uniform int u_pistesSet;
uniform float u_pixelAngle;          // radians per pixel; only without screen derivatives

// Must match RoadTileRasterizer.
const float ROAD_BAND = 8.0;
const float ROAD_BIAS = 2.0;
const float DASH_BASE_METERS = 240.0;
const float METERS_PER_LATIT = 111195.0;
// Below half a texel a distance field cannot hold a line: where the line passes between two
// texel centres the interpolated distance along it never drops under 0.5, so a thinner line
// survives only where it happens to cross a centre and breaks into a string of blobs. From
// 0.5 up the drawn edge is exact wherever the line lies, so every width starts from here.
const float MIN_HALF_TEXELS = 0.6;

// Softens the terminator. A plain dot() drops every slope facing away from the sun to the same
// flat black, which loses all the shape on the shaded side of a ridge.
const float SUN_WRAP = 0.35;
// Hemispheric ambient: open sky above, weaker bounced light from the ground below.
const float AMBIENT_SKY = 0.28;
const float AMBIENT_GROUND = 0.10;
const float DIFFUSE = 0.75;
// How strongly relief may modulate satellite imagery. The photo already carries its own
// illumination, so this only shapes it; at 1.0 the terrain gets shaded twice and goes muddy.
const float SATELLITE_RELIEF = 0.8;
// Stands in for the directional term when the sun is switched off. It is roughly what flat ground
// receives with the sun on, so turning the sun off removes the shading without also dimming the
// whole map. Slopes still vary a little through the sky term, which keeps the terrain readable
// instead of collapsing it into one flat silhouette.
const float SUN_OFF_LEVEL = 0.72;
// How much the relief shades the drawn lines: a little, so a trail across a shaded slope does
// not glow, not so much that its colour is lost.
const float LINE_RELIEF = 0.3;

float terrainLight(vec3 normal) {
    // Interpolating per-vertex normals shortens them, so without normalising, a fragment in the
    // middle of a triangle comes out darker than the same surface at its corners.
    vec3 n = normalize(normal);

    float sun = SUN_OFF_LEVEL;
    if (u_sunEnabled == 1) {
        sun = clamp((dot(n, u_sunDirection) + SUN_WRAP) / (1.0 + SUN_WRAP), 0.0, 1.0);
    }
    float sky = 0.5 + 0.5 * n.z;                    // 1 facing straight up, 0 facing down
    float ambient = mix(AMBIENT_GROUND, AMBIENT_SKY, sky);

    return ambient + DIFFUSE * sun;
}

float roadDistance(float encoded) {
    return encoded * (ROAD_BAND + ROAD_BIAS) - ROAD_BIAS;
}

// How much of a pixel a line of half-width hw covers, at distance d from its centre, when one
// pixel spans px texels: a one-pixel ramp, so the edge is antialiased at any zoom.
/**
 * Lays a colour over what is already there, and keeps count of how much of the pixel the lines
 * have covered. Drawn into the terrain the coverage starts at 1 and this is exactly
 * mix(col, c, k); in the roads-only overlay pass it starts at 0, so what comes out is the lines
 * and their own alpha, and every pixel they do not touch stays transparent.
 */
void over(inout vec3 col, inout float a, vec3 c, float k) {
    float na = k + a * (1.0 - k);
    col = na > 0.0 ? (c * k + col * a * (1.0 - k)) / na : c;
    a = na;
}

float cover(float d, float hw, float px) {
    return clamp((hw - d) / px + 0.5, 0.0, 1.0);
}

// The outline around a line: dark around a light colour, light around a dark one, so whatever
// the user picks keeps an edge against the terrain (the same rule as RoadStyle.casingFor).
vec3 casingOf(vec3 c) {
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return luma > 0.45 ? c * 0.22 : mix(c, vec3(1.0), 0.8);
}

// The trail colour for a difficulty of 0 (hiking), 0.5 (mountain) or 1 (alpine); in between
// only where two trails meet, which makes the junction a short blend rather than a hard seam.
vec3 trailColor(float difficulty) {
    if (difficulty < 0.5) {
        return mix(u_trailEasy.rgb, u_trailMountain.rgb, difficulty * 2.0);
    }
    return mix(u_trailMountain.rgb, u_trailAlpine.rgb, difficulty * 2.0 - 1.0);
}

// Piste colours as every ski map has them: novice green, easy blue, intermediate red, advanced
// black, expert orange, and violet for cross-country. RoadFeature's PISTE_* constants.
vec3 pisteColor(float v) {
    float x = v * 5.0;
    vec3 c = mix(vec3(0.18, 0.70, 0.29), vec3(0.18, 0.42, 0.90), clamp(x, 0.0, 1.0));
    c = mix(c, vec3(0.90, 0.22, 0.21), clamp(x - 1.0, 0.0, 1.0));
    c = mix(c, vec3(0.10, 0.10, 0.11), clamp(x - 2.0, 0.0, 1.0));
    c = mix(c, vec3(1.00, 0.55, 0.10), clamp(x - 3.0, 0.0, 1.0));
    c = mix(c, vec3(0.56, 0.31, 0.88), clamp(x - 4.0, 0.0, 1.0));
    return c;
}

void main() {

    float light = terrainLight(v_normal);
    float flatLight = terrainLight(vec3(0.0, 0.0, 1.0));

    if (u_whiteBackground == 0) {
        vec4 satellite = texture2D(u_textureSatellite, v_texCoord0).rgba;

        // Relief relative to flat ground, so the imagery keeps its overall brightness and only
        // the slopes go lighter or darker. Multiplying by the light directly would darken
        // everything, since flat ground is never lit at full strength.
        float relief = mix(1.0, light / flatLight, SATELLITE_RELIEF);

        gl_FragColor = vec4(satellite.rgb * relief, satellite.a);
    } else if (u_whiteBackground == 1) {
        gl_FragColor = vec4(vec3(light), 1.0);
    }

    // What the roads add to this pixel on their own, and how much of it they cover. In the
    // overlay pass this is all that reaches the frame (see TileBatchRenderer.renderRoadsOverlay),
    // so the paths stay visible over a photograph however far down the terrain is faded.
    vec3 roadsCol = vec3(0.0);
    float roadsCov = 0.0;

    if (u_roadsSet == 1) {
        vec4 enc = texture2D(u_textureRoads, v_texCoord0);
        vec4 aux = texture2D(u_textureRoadsAux, v_texCoord0);
        float dRoad = roadDistance(enc.r);
        float dTrack = roadDistance(enc.g);
        float dTrail = roadDistance(enc.b);
        float dPiste = roadDistance(enc.a);

        // The dash phase, in cycles along the trail.
        vec2 sc = aux.rg * 2.0 - 1.0;
        float phaseMagnitude = length(sc);
        float cycles = atan(sc.x, sc.y) * 0.15915494 * u_dash.x;

        // How much of the road texture this pixel covers. Everything below is measured with it:
        // line widths never drop below a pixel or so, edges are antialiased over one pixel, and
        // lines fade out where they would only be noise. The view is almost always oblique, so
        // the pixel's footprint is a long thin ellipse: pxMin and pxMax are its two axes.
        // Derivatives are taken here, before anything branches on what the textures hold.
#ifdef ROADS_DERIVATIVES
        vec2 stx = dFdx(v_texCoord0 * u_roadTexels);
        vec2 sty = dFdy(v_texCoord0 * u_roadTexels);
        float fa = dot(stx, stx) + dot(sty, sty);
        float fdet = abs(stx.x * sty.y - stx.y * sty.x);
        float fdisc = sqrt(max(fa * fa - 4.0 * fdet * fdet, 0.0));
        float pxMax = sqrt(0.5 * (fa + fdisc));
        float pxMin = max(sqrt(max(0.5 * (fa - fdisc), 0.0)), 1e-3);
        // Across each line: the distance's own screen gradient, which is exactly the pixel's
        // extent in the direction that sets the line's apparent width; bounded by the ellipse
        // where the field is flat (far from any line) or folds (on a line's centre).
        float pxRoad = clamp(length(vec2(dFdx(dRoad), dFdy(dRoad))), pxMin, pxMax);
        float pxTrack = clamp(length(vec2(dFdx(dTrack), dFdy(dTrack))), pxMin, pxMax);
        float pxTrail = clamp(length(vec2(dFdx(dTrail), dFdy(dTrail))), pxMin, pxMax);
        float pxPiste = clamp(length(vec2(dFdx(dPiste), dFdy(dPiste))), pxMin, pxMax);
        // Dash cycles per pixel, from whichever of two copies of the phase has no wrap here.
        float cyclesPerPixel = min(fwidth(cycles), fwidth(fract(cycles + 0.5)));
#else
        float metersPerPixelEstimate = distance * METERS_PER_LATIT * u_pixelAngle;
        float pxMax = max(metersPerPixelEstimate / u_roadMetersPerTexel, 1e-3);
        float pxMin = pxMax * 0.4;
        float pxRoad = pxMax * 0.7;
        float pxTrack = pxRoad;
        float pxTrail = pxRoad;
        float pxPiste = pxRoad;
        float cyclesPerPixel = metersPerPixelEstimate * u_dash.x / DASH_BASE_METERS;
#endif
        float mpt = u_roadMetersPerTexel;
        // Ground metres per pixel, averaged over the footprint: what decides when a kind of way
        // is too fine to draw. Minor ways go first, the way a paper map drops them at a smaller
        // scale; and all of them go before a line would outgrow the distances the texture holds.
        float metersPerPixel = mpt * sqrt(pxMin * pxMax);
        float bandFade = 1.0 - smoothstep(2.4, 3.4, pxMax);
        float fadeRoad = (1.0 - smoothstep(35.0, 60.0, metersPerPixel)) * bandFade;
        float fadeTrack = (1.0 - smoothstep(16.0, 28.0, metersPerPixel)) * bandFade;
        float fadeTrail = (1.0 - smoothstep(14.0, 24.0, metersPerPixel)) * bandFade;
        float fadePiste = (1.0 - smoothstep(35.0, 60.0, metersPerPixel)) * bandFade;

        float lineLight = mix(1.0, light / flatLight, LINE_RELIEF);
#ifdef ROADS_OVERLAY
        vec3 col = vec3(0.0);
        float acc = 0.0;
#else
        vec3 col = gl_FragColor.rgb;
        float acc = 1.0;
#endif

        // Pistes, at the bottom: a translucent band with a thin line along the centre of a
        // piste drawn as a line, or around the edge of one drawn as an area.
        if (u_pistesSet == 1) {
            // Lifted a little towards white, so the band keeps its colour over dark forest
            // rather than muddying it; black runs stay dark enough to read as black.
            vec3 pc = mix(pisteColor(aux.b), vec3(1.0), 0.12) * lineLight;
            float hwBand = max(max(12.0 / mpt, 1.4 * pxPiste), MIN_HALF_TEXELS);
            float band = cover(dPiste, hwBand, pxPiste) * 0.42 * fadePiste;
            float edge = cover(abs(dPiste), max(max(0.9 / mpt, 0.55 * pxPiste), MIN_HALF_TEXELS), pxPiste) * 0.75 * fadePiste;
            over(col, acc, pc, band);
            over(col, acc, pc, edge);
        }

        // Roads: an outline and a core. A major road's extra width is already in its distance.
        {
            vec3 core = u_roadCore.rgb * lineLight;
            float hw = max(max(2.2 / mpt, 0.75 * pxRoad), MIN_HALF_TEXELS);
            float outline = max(0.9 / mpt, 0.8 * pxRoad);
            over(col, acc, casingOf(u_roadCore.rgb), cover(dRoad, hw + outline, pxRoad) * 0.9 * fadeRoad);
            over(col, acc, core, cover(dRoad, hw, pxRoad) * u_roadCore.a * fadeRoad);
        }

        // Tracks: the same, narrower.
        {
            vec3 core = u_trackCore.rgb * lineLight;
            float hw = max(max(1.6 / mpt, 0.65 * pxTrack), MIN_HALF_TEXELS);
            float outline = max(0.6 / mpt, 0.6 * pxTrack);
            over(col, acc, casingOf(u_trackCore.rgb), cover(dTrack, hw + outline, pxTrack) * 0.85 * fadeTrack);
            over(col, acc, core, cover(dTrack, hw, pxTrack) * u_trackCore.a * fadeTrack);
        }

        // Trails, on top: dashed, coloured by difficulty, each dash with a thin outline. The
        // dashes creep along the trail by u_dash.z cycles a second. Where the pattern gets finer
        // than a few pixels, or filtering has averaged the phase away (the small mip levels,
        // far off), it melts into a solid, slightly lighter line instead of shimmering.
        {
            float duty = u_dash.y;
            float cycle = fract(cycles - u_time * u_dash.z);
            float fromCentre = abs(fract(cycle - 0.5 * duty + 0.5) - 0.5);
            float on = clamp((0.5 * duty - fromCentre) / max(cyclesPerPixel, 1e-4) + 0.5, 0.0, 1.0);
            float resolved = (1.0 - smoothstep(0.18, 0.4, cyclesPerPixel))
                    * smoothstep(0.3, 0.6, phaseMagnitude);
            float dash = mix(0.8, on, resolved);

            vec3 tc = trailColor(aux.a);
            float hw = max(max(1.1 / mpt, 0.7 * pxTrail), MIN_HALF_TEXELS);
            float outline = max(0.5 / mpt, 0.6 * pxTrail);
            float alpha = dash * fadeTrail;
            // A quiet dark edge whatever the colour: casingOf would give red and blue a light
            // one, which over dark forest makes them glow pink and pale instead of standing out.
            over(col, acc, tc * 0.3, cover(dTrail, hw + outline, pxTrail) * 0.6 * alpha);
            over(col, acc, tc * lineLight, cover(dTrail, hw, pxTrail) * alpha);
        }

        roadsCol = col;
        roadsCov = acc;
        gl_FragColor = vec4(col, gl_FragColor.a);
    }

    // GPX path, painted onto the tile surface (over the lit terrain and the roads, so a track
    // following a road stays visible on top of it).
    // GpxTileRasterizer stores, per texel: the flow phase as sine (r) and cosine (g) so it can be
    // filtered across its wrap, and in alpha the coverage, which ramps down across the edge of the
    // line instead of switching off. Both are read with a linear filter, so the line keeps a
    // smooth edge and a smooth flow when the fly-over camera comes close, rather than breaking up
    // into texel blocks.
    if (u_gpxSet == 1) {
        vec4 gpx = texture2D(u_textureGpx, v_texCoord0);
        // Antialiased edge: pull a soft threshold out of the coverage ramp. Widening the band
        // here softens the outline; narrowing it sharpens.
        float cov = smoothstep(0.18, 0.62, gpx.a);
        if (cov > 0.002) {
            // Recover the phase angle from the filtered sine/cosine pair. Their magnitude sags
            // where the filter blends neighbours, but the direction — all we want — survives.
            vec2 gsc = gpx.rg * 2.0 - 1.0;
            float phase = atan(gsc.x, gsc.y) * 0.15915494; // / (2*pi) -> [-0.5, 0.5]
            float m = fract(phase - u_time * 0.8);
            // A soft comet head with a faint trailing glow, so the pattern reads as flowing
            // rather than as a hard repeating stripe.
            float comet = smoothstep(0.55, 1.0, m);
            float trail = 0.35 * smoothstep(0.0, 0.55, m);
            vec3 gpxCol = mix(vec3(0.10, 0.45, 0.90), vec3(0.85, 1.0, 0.95), comet);
            gpxCol += vec3(0.05, 0.12, 0.18) * trail;
            // A darker rim where coverage is partial keeps the line legible over bright terrain.
            gpxCol = mix(vec3(0.03, 0.12, 0.28), gpxCol, smoothstep(0.35, 0.85, gpx.a));
            gl_FragColor = vec4(mix(gl_FragColor.rgb, gpxCol, cov), 1.0);
        }
    }

#ifdef ROADS_OVERLAY
    // The overlay pass: the lines and nothing else, so they can be blended over a photograph.
    gl_FragColor = vec4(roadsCol, roadsCov);
#endif
}
