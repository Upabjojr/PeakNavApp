package com.peaknav.viewer.render_tiles;

import com.badlogic.gdx.Gdx;

import org.mapsforge.map.rendertheme.XmlThemeResourceProvider;

import java.io.IOException;
import java.io.InputStream;

public class CustomResourceProvider implements XmlThemeResourceProvider {

    @Override
    public InputStream createInputStream(String relativePath, String source) {
        String fullPath = relativePath;
        if (!fullPath.equals("") && !fullPath.endsWith("/"))
            fullPath += "/";
        if (source.startsWith("/"))
            fullPath = fullPath + source.substring(1);
        else
            fullPath = fullPath + source;
        return Gdx.files.internal(fullPath).read();
    }

}
