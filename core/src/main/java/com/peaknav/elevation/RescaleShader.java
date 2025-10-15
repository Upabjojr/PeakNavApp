package com.peaknav.elevation;

import static com.badlogic.gdx.graphics.g3d.utils.DefaultTextureBinder.ROUNDROBIN;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
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

public class RescaleShader {

    private final RenderContext renderContext;
    private final SpriteBatch batch;
    private final BaseShader shader;
    private final Mesh mesh;
    private Texture textureEleJpg = null;
    private Texture textureElePng = null;

    public RescaleShader() {
        renderContext = new RenderContext(
                new DefaultTextureBinder(ROUNDROBIN)
        );

        final Renderable renderable = new Renderable();
        mesh = getMesh();
        renderable.meshPart.mesh = mesh;

        shader = new BaseShader() {
            @Override
            public void init() {
                program = new ShaderProgram(
                        Gdx.files.internal("vertex_shader_rescale.glsl").readString(),
                        Gdx.files.internal("fragment_shader_rescale.glsl").readString());
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

        BaseShader.Uniform u_eleTextureJpg = new BaseShader.Uniform("u_eleTextureJpg");
        shader.register(u_eleTextureJpg, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                RescaleUserData rud = (RescaleUserData) renderable.userData;
                TextureDescriptor textureDescriptor = new TextureDescriptor(rud.texElevationJpg);
                final int unit = shader.context.textureBinder.bind(textureDescriptor);
                shader.set(inputID, unit);
            }
        });

        BaseShader.Uniform u_eleTexturePng = new BaseShader.Uniform("u_eleTexturePng");
        shader.register(u_eleTexturePng, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                RescaleUserData rud = (RescaleUserData) renderable.userData;
                TextureDescriptor textureDescriptor = new TextureDescriptor(rud.texElevationPng);
                final int unit = shader.context.textureBinder.bind(textureDescriptor);
                shader.set(inputID, unit);
            }
        });

        final BaseShader.Uniform u_rescaleFactor = new BaseShader.Uniform("u_rescaleFactor");
        shader.register(u_rescaleFactor, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                RescaleUserData rud = (RescaleUserData) renderable.userData;
                shader.program.setUniformi(u_rescaleFactor.alias, rud.rescaleFactor);
            }
        });

        final BaseShader.Uniform u_edgeLength = new BaseShader.Uniform("u_edgeLength");
        shader.register(u_edgeLength, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader shader, int inputID, Renderable renderable, Attributes combinedAttributes) {
                RescaleUserData rud = (RescaleUserData) renderable.userData;
                shader.program.setUniformi(u_edgeLength.alias, rud.texElevationJpg.getWidth());
            }
        });

        shader.init();
        batch = new SpriteBatch(1000, shader.program);

    }

    private Mesh getMesh() {
        final int w1 = 2;
        final int h1 = 2;
        float[] vertices = new float[2 * w1 * h1];
        short[] indices = new short[6*(w1-1)*(h1-1)];
        int pos = 0;
        for (int y = 0; y < h1; y++) {
            for (int x = 0; x < w1; x++, pos += 2) {
                vertices[pos] = 1.f*x;
                vertices[pos + 1] = 1.f*y;
            }
        }

        indices[0] = (short) 0;
        indices[1] = (short) 1;
        indices[2] = (short) 3;
        indices[3] = (short) 0;
        indices[4] = (short) 3;
        indices[5] = (short) 2;

        Mesh mesh = new Mesh(true, w1*h1, indices.length,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE));
        mesh.setVertices(vertices);
        mesh.setIndices(indices);
        return mesh;
    }

    public void setPixmaps(Pixmap eleJpg, Pixmap elePng) {
        disposeTextures();
        textureEleJpg = new Texture(eleJpg);
        textureElePng = new Texture(elePng);
    }

    private Renderable getRenderable(int rescaleFactor) {
        Renderable renderable = new Renderable();

        renderable.meshPart.mesh = this.mesh;
        renderable.meshPart.size = this.mesh.getNumIndices(); // max of total number of vertices and indices
        renderable.meshPart.primitiveType = GL20.GL_TRIANGLES;
        renderable.userData = new RescaleUserData(
                textureEleJpg,
                textureElePng,
                rescaleFactor);
        // TODO: bitmap.dispose();
        return renderable;
    }

    private Pixmap getRescaledPixmap(int rescaleFactor) {
        Renderable renderable = getRenderable(rescaleFactor);
        return getRenderedPixmap(renderable);
    }

    public ElevationPixmapPair getRescaledPixmapPair(int rescaleFactor) {
        Pixmap fullPixmap = getRescaledPixmap(rescaleFactor);
        int w = fullPixmap.getWidth();
        int h = fullPixmap.getHeight();
        Pixmap eleJpg = new Pixmap(w, h, Pixmap.Format.Alpha);
        Pixmap elePng = new Pixmap(w, h, Pixmap.Format.Alpha);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int pixel = fullPixmap.getPixel(x, y);
                int r = pixel >>> 24;
                int g = (pixel & 0xFF0000) >>> 16;
                eleJpg.drawPixel(x, y, r);
                elePng.drawPixel(x, y, g);
            }
        }
        return new ElevationPixmapPair(eleJpg, elePng);
    }

    public static class ElevationPixmapPair {
        public final Pixmap eleJpg;
        public final Pixmap elePng;

        public ElevationPixmapPair(Pixmap eleJpg, Pixmap elePng) {
            this.eleJpg = eleJpg;
            this.elePng = elePng;
        }
    }

    public static class RescaleUserData {
        public final Texture texElevationJpg;
        public final Texture texElevationPng;
        public final int rescaleFactor;

        public RescaleUserData(Texture texElevationJpg, Texture texElevationPng, int rescaleFactor) {
            this.texElevationJpg = texElevationJpg;
            this.texElevationPng = texElevationPng;
            this.rescaleFactor = rescaleFactor;
        }
    }

    private Pixmap getRenderedPixmap(Renderable renderable) {
        RescaleUserData rud = (RescaleUserData) renderable.userData;
        int w = (rud.texElevationJpg.getWidth()-1)/rud.rescaleFactor + 1;
        int h = (rud.texElevationJpg.getHeight()-1)/rud.rescaleFactor + 1;
        FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, true);

        fbo.begin();
        ScreenUtils.clear(0, 0, 0, 1);

        shader.begin(null, renderContext);
        shader.render(renderable);
        // batch.draw(rud.texElevationJpg, 0, 0, w, h, 0, 0, w, h, false, true);
        shader.end();

        Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, w, h);
        fbo.end();
        fbo.dispose();
        return pixmap;
    }

    public void disposeTextures() {
        if (textureEleJpg != null) {
            textureEleJpg.dispose();
            textureEleJpg = null;
        }
        if (textureElePng != null) {
            textureElePng.dispose();
            textureElePng = null;
        }
    }

    public void dispose() {
        batch.dispose();
        disposeTextures();
    }
}
