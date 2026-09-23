package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.peaknav.viewer.labels.FeatureInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The pane a tapped label opens: what the thing it names is, and all the map data knows of it
 * (see {@link FeatureInfo}). At the top left, where the GPX pane sits and over it, in the same
 * dress: its name and kind beside a close button, then a line for each fact, the links among them
 * tappable, and under a header that folds them away, the map data's tags as they are. Whatever
 * does not fit above the bottom of the screen scrolls.
 */
public class FeatureInfoPane {

    private static final Color PANEL = new Color(0.03f, 0.08f, 0.14f, 0.82f);
    private static final Color KIND = new Color(0.82f, 0.88f, 1f, 1f);
    private static final Color FACT = new Color(0.70f, 0.78f, 0.90f, 1f);
    private static final Color LINK = new Color(0.55f, 0.80f, 1f, 1f);
    private static final Color SCROLL_KNOB = new Color(1f, 1f, 1f, 0.55f);
    private static final float PANE_UNITS = 5.6f;
    private static final float PAD_LEFT_UNITS = 1.5f;
    private static final float PAD_TOP_UNITS = 1.2f;
    private static final float PANEL_PAD_UNITS = 0.15f;
    private static final float CLOSE_UNITS = 0.7f;
    /** Kept clear under the pane, for the coordinates and the buttons at the bottom. */
    private static final float BOTTOM_CLEAR_UNITS = 2.2f;
    /** The share of the pane's width the facts' names take, their values the rest. */
    private static final float LABEL_SHARE = 0.36f;
    /** Kept clear on the right, for the two columns of buttons. */
    private static final float RIGHT_CLEAR_UNITS = 4.8f;
    private static final String ICON_CLOSE = "icons/icon_x.png";
    private static final String ICON_FOLD = "icons/icon_pane_fold.png";

    private final float widgetUnitStep;
    private final Table root = new Table();
    private final Table panel = new Table();
    private final Table body = new Table();
    private final ScrollPane scroll;
    private final Button closeButton;
    private final Label.LabelStyle titleStyle, kindStyle, factStyle, valueStyle, linkStyle;
    private final Table tagsHeader = new Table();
    private final Label tagsTitle;
    private final Image tagsChevron;
    private boolean tagsOpen = false;
    private FeatureInfo shown;
    /** The texts shown, one per line, "label: value", for tests. */
    private final List<String> shownLines = new ArrayList<>();

    public FeatureInfoPane(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;
        root.setFillParent(true);
        root.top().left().padTop(PAD_TOP_UNITS * widgetUnitStep).padLeft(PAD_LEFT_UNITS * widgetUnitStep);
        root.setVisible(false);
        root.setTouchable(Touchable.childrenOnly);

        panel.setBackground(getC().widgetTextures.getUniformDrawable(PANEL));
        panel.setTouchable(Touchable.enabled);
        panel.addListener(swallowingListener());

        titleStyle = new Label.LabelStyle();
        titleStyle.font = getC().styleSingleton.getBitmapFontSmallWhite();
        titleStyle.fontColor = Color.WHITE;
        valueStyle = new Label.LabelStyle();
        valueStyle.font = getC().styleSingleton.getBitmapFontVerySmallWhite();
        valueStyle.fontColor = Color.WHITE;
        kindStyle = new Label.LabelStyle(valueStyle);
        kindStyle.fontColor = KIND;
        factStyle = new Label.LabelStyle(valueStyle);
        factStyle.fontColor = FACT;
        linkStyle = new Label.LabelStyle(valueStyle);
        linkStyle.fontColor = LINK;

        closeButton = getC().widgetTextures.getButtonWithIcon(ICON_CLOSE, null);
        closeButton.setName("feature_info_close");
        closeButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                hide();
            }
        });

        tagsTitle = wrapped("", factStyle);
        tagsChevron = new Image(getC().widgetTextures.getTextureRegionDrawable(ICON_FOLD)) {
            @Override
            public void layout() {
                super.layout();
                setOrigin(Align.center);
            }
        };
        tagsHeader.setTouchable(Touchable.enabled);
        tagsHeader.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                setTagsOpen(!tagsOpen);
            }
        });

        ScrollPane.ScrollPaneStyle scrollStyle = new ScrollPane.ScrollPaneStyle();
        TextureRegionDrawable knob = new TextureRegionDrawable(getC().widgetTextures.getUniformDrawable(SCROLL_KNOB));
        knob.setMinWidth(0.08f * widgetUnitStep);
        knob.setMinHeight(0.5f * widgetUnitStep);
        scrollStyle.vScrollKnob = knob;
        scroll = new ScrollPane(body, scrollStyle);
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, false);
        scroll.setFadeScrollBars(false);
        scroll.setScrollbarsOnTop(true);

        root.add(panel);
    }

    public Table getTable() {
        return root;
    }

    public boolean isShown() {
        return root.isVisible() && shown != null;
    }

    public FeatureInfo getShown() {
        return isShown() ? shown : null;
    }

    public void show(FeatureInfo info) {
        shown = info;
        tagsOpen = false;
        layoutPanel();
        root.setVisible(true);
        scroll.setScrollY(0);
    }

    public void hide() {
        root.setVisible(false);
        shown = null;
    }

    public void setTagsOpen(boolean open) {
        tagsOpen = open;
        float y = scroll.getScrollY();
        layoutPanel();
        scroll.layout();
        scroll.setScrollY(y);
    }

    public boolean isTagsOpen() {
        return tagsOpen;
    }

    /** "title", "kind", then each line as "label: value", and the tags' when open, for tests. */
    public List<String> getShownLines() {
        return new ArrayList<>(shownLines);
    }

    private void layoutPanel() {
        float u = widgetUnitStep;
        float width = width();
        float gap = 0.12f * u;
        panel.clearChildren();
        body.clearChildren();
        shownLines.clear();
        if (shown == null) {
            return;
        }
        panel.pad(PANEL_PAD_UNITS * u);

        Table head = new Table();
        Label title = wrapped(shown.title, titleStyle);
        Label kind = wrapped(shown.kind, kindStyle);
        Table names = new Table();
        names.add(title).left().width(width - CLOSE_UNITS * u - gap).row();
        names.add(kind).left().width(width - CLOSE_UNITS * u - gap);
        head.add(names).left().top().expandX();
        head.add(closeButton).size(CLOSE_UNITS * u).right().top().padLeft(gap);
        shownLines.add(shown.title);
        shownLines.add(shown.kind);

        body.defaults().left().width(width);
        for (FeatureInfo.Row row : shown.rows) {
            body.add(row(row, width)).padTop(0.04f * u).row();
        }
        if (!shown.tags.isEmpty()) {
            float chevron = 0.4f * u;
            tagsTitle.setText(s("Feature_osm_tags") + " (" + shown.tags.size() + ")");
            tagsHeader.clearChildren();
            tagsHeader.add(tagsTitle).left().width(width - chevron - 0.1f * u);
            tagsHeader.add(tagsChevron).size(chevron).right().padLeft(0.1f * u);
            tagsChevron.setRotation(tagsOpen ? 0f : 180f);
            body.add(tagsHeader).padTop(0.15f * u).row();
            if (tagsOpen) {
                for (FeatureInfo.Row tag : shown.tags) {
                    Label line = wrapped(tag.label + " = " + tag.value, factStyle);
                    body.add(line).padTop(0.02f * u).row();
                    shownLines.add(tag.label + " = " + tag.value);
                }
            }
        }

        panel.add(head).width(width).row();
        panel.add(scroll).width(width).maxHeight(visibleHeight()).padTop(0.08f * u);
        panel.invalidateHierarchy();
    }

    /** "Elevation   2145 m", or a link, which opens where it leads when tapped. */
    private Table row(final FeatureInfo.Row row, float width) {
        float gap = 0.12f * widgetUnitStep;
        float labelWidth = LABEL_SHARE * width;
        Table cell = new Table();
        cell.add(wrapped(row.label, factStyle)).left().top().width(labelWidth);
        Label value = wrapped(row.value, row.url != null ? linkStyle : valueStyle);
        cell.add(value).left().top().width(width - labelWidth - gap).padLeft(gap);
        if (row.url != null) {
            cell.setTouchable(Touchable.enabled);
            cell.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    Gdx.net.openURI(row.url);
                }
            });
        }
        shownLines.add(row.label + ": " + row.value);
        return cell;
    }

    /**
     * Keeps a touch, a drag or a turn of the wheel on a pane from going through it to the map:
     * the stage passes on what no listener handled, and the pane's background has none of its
     * own, so a tap there picked a point, or another label, under the pane.
     */
    static InputListener swallowingListener() {
        return new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return true;
            }

            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                return true;
            }
        };
    }

    private static Label wrapped(String text, Label.LabelStyle style) {
        Label label = new Label(text, style);
        label.setAlignment(Align.left);
        label.setWrap(true);
        return label;
    }

    /** As wide as it may be without reaching the buttons on the right. */
    private float width() {
        float u = widgetUnitStep;
        if (root.getStage() == null) {
            return PANE_UNITS * u;
        }
        float room = root.getStage().getWidth() - PAD_LEFT_UNITS * u - RIGHT_CLEAR_UNITS * u
                - 2 * PANEL_PAD_UNITS * u;
        return Math.max(3f * u, Math.min(PANE_UNITS * u, room));
    }

    private float visibleHeight() {
        float u = widgetUnitStep;
        if (root.getStage() == null) {
            return 100f * u;
        }
        float room = root.getStage().getHeight() - PAD_TOP_UNITS * u - CLOSE_UNITS * u
                - 2 * PANEL_PAD_UNITS * u - BOTTOM_CLEAR_UNITS * u;
        return Math.max(2f * u, room);
    }
}
