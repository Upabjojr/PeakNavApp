package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public class WidgetTextures {

    private final ConcurrentHashMap<String, Texture> textures = new ConcurrentHashMap<>();

    public Button getButtonWithIcon(String internalPath) {
        return getButtonWithIcon(internalPath, null);
    }

    private Texture getTextureFromInternalPath(String internalPath) {
        Texture texture = textures.get(internalPath);
        if (texture == null) {
            texture = new Texture(internalPath);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            textures.put(internalPath, texture);
        }
        return texture;
    }

    public TextureRegionDrawable getTextureRegionDrawable(String internalPath) {
        return new TextureRegionDrawable(new TextureRegion(getTextureFromInternalPath(internalPath)));
    }

    public NinePatchDrawable getNinePatchDrawable(String internalPath) {
        Texture texture = getTextureFromInternalPath(internalPath);
        NinePatch ninePatch = new NinePatch(texture, 10, 10, 10, 10);
        return new NinePatchDrawable(ninePatch);
    }

    public Button getButtonWithIcon(String internalPath, String internalPathPressed) {
        Drawable buttonDrawable = getTextureRegionDrawable(internalPath);
        Drawable pressedDrawable = null;
        if (internalPathPressed != null) {
            pressedDrawable = getTextureRegionDrawable(internalPathPressed);
        }
        Button.ButtonStyle buttonStyle = new Button.ButtonStyle(buttonDrawable, null, pressedDrawable);

        return new Button(buttonStyle);
    }

    public BaseDrawable getTransparentDrawable() {
        return new BaseDrawable();
    }

    public TextureRegionDrawable getUniformDrawable(Color color) {
        String key = color.toString();
        Texture texture = textures.get(key);
        if (texture == null) {
            Pixmap pixmap = new Pixmap(10, 10, Pixmap.Format.RGBA8888);
            pixmap.setColor(color);
            pixmap.fill();
            texture = new Texture(pixmap);
            pixmap.dispose();
            textures.put(key, texture);
        }
        return new TextureRegionDrawable(texture);
    }

    public synchronized void dispose() {
        Queue<Texture> remaining = new LinkedList<>();
        remaining.addAll(textures.values());
        textures.clear();
        while (!remaining.isEmpty()) {
            Texture texture = remaining.poll();
            texture.dispose();
        }
    }
}
