package com.peaknav.elevation;


import static com.badlogic.gdx.graphics.g3d.utils.DefaultTextureBinder.ROUNDROBIN;
import static com.peaknav.utils.PeakNavUtils.convertImageBytesToElevationMeters;
import static com.peaknav.utils.Units.convertLatitsToTexture;
import static com.peaknav.utils.Units.convertMetersToLatits;
import static com.peaknav.utils.Units.radiusOfEarth;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultTextureBinder;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.ScreenUtils;
import com.peaknav.viewer.tiles.MapTile;

import java.nio.ByteBuffer;

public class NormalShader implements SpecialShader {

    private final RenderContext renderContext;
    private final SpriteBatch batch;
    private final BaseShader shader;

    public NormalShader() {
        renderContext = new RenderContext(
                new DefaultTextureBinder(ROUNDROBIN)
        );

        final Renderable renderable = new Renderable();
        renderable.meshPart.mesh = new Mesh(true, 4, 6, VertexAttribute.Position());

        shader = new BaseShader() {
            @Override
            public void init() {
                program = new ShaderProgram(
                        Gdx.files.internal("vertex_shader_normals.glsl").readString(),
                        Gdx.files.internal("fragment_shader_normals.glsl").readString());
                super.init(program, renderable);
            }

            @Override
            public int compareTo(Shader other) {
                return 0;
            }

            @Override
            public boolean canRender(Renderable instance) {
                return true;
            }
        };

        BaseShader.Uniform u_eleTexture = new BaseShader.Uniform("u_eleTexture");
        shader.register(u_eleTexture, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                NormalUserData nud = (NormalUserData) renderable.userData;
                TextureDescriptor textureDescriptor = new TextureDescriptor(nud.texElevation);
                final int unit = shader.context.textureBinder.bind(textureDescriptor);
                shader.set(inputID, unit);
            }
        });

        final BaseShader.Uniform u_Scale = new BaseShader.Uniform("u_Scale");
        shader.register(u_Scale, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                NormalUserData nud = (NormalUserData) renderable.userData;
                // pi*radiusOfEarth/90./cropFactor/edgeLengthM1
                int em1 = nud.texElevation.getWidth() - 1;
                shader.program.setUniformf(u_Scale.alias, (float) convertMetersToLatits(3.1415926f*radiusOfEarth/90/nud.cropFactor/em1));
            }
        });
        final BaseShader.Uniform u_edgeLength = new BaseShader.Uniform("u_edgeLength");
        shader.register(u_edgeLength, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                NormalUserData nud = (NormalUserData) renderable.userData;
                shader.program.setUniformi(u_edgeLength.alias, nud.texElevation.getWidth());
            }
        });

        shader.init();
        batch = new SpriteBatch(1000, shader.program);

    }

    @Override
    public void dispose() {
        batch.dispose();
    }

    public static class NormalUserData {
        public final Texture texElevation;
        public final int cropFactor;

        public NormalUserData(Texture texElevation, int cropFactor) {
            this.texElevation = texElevation;
            this.cropFactor = cropFactor;
        }
    }

    public Renderable getRenderable(Pixmap eleJpg, Pixmap elePng, int cropFactor) {
        Renderable renderable = new Renderable();

        int w = eleJpg.getWidth();
        int h = eleJpg.getHeight();

        float[] vertices = new float[3 * w * h];
        short[] indices = new short[6*(w-1)*(h-1)];
        int pos = 0;
        ByteBuffer bbJpg = eleJpg.getPixels();
        ByteBuffer bbPng = elePng.getPixels();
        Pixmap elePixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++, pos += 3) {
                vertices[pos] = 1.f/(w-1)*x;
                vertices[pos + 1] = 1.f/(h-1)*y;
                float ele = convertImageBytesToElevationMeters(bbJpg.get(pos/3), bbPng.get(pos/3));
                vertices[pos + 2] = ele;
                elePixmap.drawPixel(x, y, convertLatitsToTexture(convertMetersToLatits(ele)));
            }
        }
        pos = 0;
        for (short y = 0; y < h - 1; y++) {
            for (short x = 0; x < w - 1; x++, pos += 6) {
                indices[pos] = (short) (x + h*y);
                indices[pos + 1] = (short) (x + 1 + h*y);
                indices[pos + 2] = (short) (x + 1 + h*(y+1));
                indices[pos + 3] = (short) (x + h*y);
                indices[pos + 4] = (short) (x + 1 + h*(y+1));
                indices[pos + 5] = (short) (x + h*(y + 1));
            }
        }
        renderable.meshPart.mesh = new Mesh(true, vertices.length, indices.length, VertexAttribute.Position());
        renderable.meshPart.mesh.setVertices(vertices);
        renderable.meshPart.mesh.setIndices(indices);
        renderable.meshPart.size = Math.max(vertices.length, indices.length); // max of total number of vertices and indices
        renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
        Texture texElevation = new Texture(elePixmap);
        // TODO: elePixmap.dispose();
        renderable.userData = new NormalUserData(texElevation, cropFactor);
        return renderable;
    }

    @Override
    public Pixmap getRenderedPixmap(Renderable renderable) {
        NormalUserData nud = (NormalUserData) renderable.userData;
        FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, nud.texElevation.getWidth(), nud.texElevation.getHeight(), true);

        fbo.begin();
        ScreenUtils.clear(1, 0, 0, 1);

        shader.begin(null, renderContext);
        shader.render(renderable);
        shader.end();

        Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, nud.texElevation.getWidth(), nud.texElevation.getHeight());
        fbo.end();
        // fbo.dispose();
        return pixmap;
    }

}
