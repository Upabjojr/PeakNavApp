package com.peaknav.viewer.panes;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

/**
 * A menu laid out twice - in two columns for a screen wider than it is tall, in one for a
 * screen held upright - of which the one that fits is shown, and swapped for the other when
 * the screen turns or the window changes shape.
 *
 * <p>Only the menus too tall for a screen held sideways in one column need it: the main menu,
 * the labels' submenu and the trails' (measured at a phone's 2340 x 1080: 1188 to 1307
 * pixels in one column; every other submenu stays under 850). The main menu followed the
 * screen before; the submenus kept the layout they were opened in, running off the screen.
 */
final class OrientedMenu {

    private final Table wide;
    private final Table tall;

    OrientedMenu(Table wide, Table tall) {
        this.wide = wide;
        this.tall = tall;
    }

    /** Whether the screen is wider than it is tall. */
    static boolean isWideScreen() {
        return Gdx.graphics.getWidth() > Gdx.graphics.getHeight();
    }

    /** Shows the layout that fits the screen, and hides the other. */
    void show() {
        boolean wideScreen = isWideScreen();
        wide.setVisible(wideScreen);
        tall.setVisible(!wideScreen);
    }

    void hide() {
        wide.setVisible(false);
        tall.setVisible(false);
    }

    boolean isVisible() {
        return wide.isVisible() || tall.isVisible();
    }

    /** After the screen changed shape: the layout that now fits, if the menu is on show. */
    void fit() {
        if (isVisible()) {
            show();
        }
    }
}
