package com.peaknav.elevation;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Renderable;

public interface SpecialShader {

    Pixmap getRenderedPixmap(Renderable renderable);
    void dispose();
}
