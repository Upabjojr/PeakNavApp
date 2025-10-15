package com.peaknav.viewer.controller;

import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;

public class StaticData {
    public final VertexAttributes mapTileVertexAttributes = MeshBuilder.createAttributes(
            VertexAttributes.Usage.Position
                    | VertexAttributes.Usage.TextureCoordinates
                    | VertexAttributes.Usage.Normal);
}
