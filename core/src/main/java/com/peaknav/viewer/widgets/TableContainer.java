package com.peaknav.viewer.widgets;

import com.badlogic.gdx.scenes.scene2d.ui.Table;

public abstract class TableContainer {
    protected final Table table;

    public TableContainer() {
        table = new Table();
        table.setDebug(false);
    }

    public Table getTable() {
        return table;
    }

}
