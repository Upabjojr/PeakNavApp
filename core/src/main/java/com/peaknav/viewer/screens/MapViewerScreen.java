package com.peaknav.viewer.screens;


import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.utils.Units.convertLonitsToLatits;
import static com.peaknav.viewer.screens.LabelLoading.State.LOADING;
import static com.peaknav.viewer.screens.LabelLoading.State.NO_DATA;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

import com.badlogic.gdx.utils.viewport.ExtendViewport;

import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.*;

import com.peaknav.database.CheckMissingData;
import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.database.MapSqlite;
import com.peaknav.utils.CacheDirManager;
import com.peaknav.utils.PreferencesManager;
import com.peaknav.viewer.DataRetrieveThreadManager;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.panes.OptionPane;
import com.peaknav.viewer.render_tiles.ImpactPixmap;
import com.peaknav.viewer.renderer_gdx.LabelRenderer;
import com.peaknav.viewer.renderer_gdx.TileBatchRenderer;
import com.peaknav.gesture.MountainInputController;
import com.peaknav.gesture.PositionChangeListener;
import com.peaknav.utils.Units;
import com.peaknav.viewer.tiles.MapTile;
import com.peaknav.viewer.widgets.WidgetGetter;


public class MapViewerScreen implements Screen {

	public final MapApp mapApp;

	public PerspectiveCameraExt cam;
    public Button buttonPinLoc;
	public WidgetGetter.TableLocation tableLocation;
	public Table tableWatermark;
	public WidgetGetter.TableTool tableTool;

	public MountainInputController controller;
	private final float baseFieldOfView;
	public volatile boolean needToBeShown = true;
	private InputMultiplexer multiplexer;
	public Label labelElevationChange;
	public Table tableCenter;
	public long lastElevationChange = 0;
	private Window window = null;
	public Vector3 impact = null;

	private Viewport stageViewport;
	private Viewport stageNavigationViewport;
	private final float sidebarProp = 0.2f;

	public final int targetWidth = 750;

	private final ArrayList<PositionChangeListener> positionChangeListeners = new ArrayList<>();
	private Runnable disposeRunnable = null;
	private volatile boolean triggerElevationChanged = false;
	private SpriteBatch spriteBatch;
	private SpriteBatch spriteBatchOutlines;
	private ShapeRenderer shapeRenderer;
	private WidgetGetter.TableDownloadData tableDownloadData;
	public LabelRenderer labelRenderer;
	private TileBatchRenderer tileBatchRenderer;

	public final MoveCameraAction moveCameraAction = new MoveCameraAction();
	public volatile ImpactPixmap impactPixmap;
	public LabelLoading labelLoading;
	public OptionPane optionPane;
	public final BackgroundPicManager backgroundPicManager = new BackgroundPicManager();
	private volatile boolean flagTakeSnapshot = false;
	private volatile boolean paused = false;
	private Integer impactDistanceMeters = null;
	private boolean searchLocationOpen = false;
	private float searchLocationOpenTimeCounter = 0f;
	private long timeLastSaveCameraOrientation = 0L;


	public MapViewerScreen(MapApp mapApp) {
		this.mapApp = mapApp;

		baseFieldOfView = 30f;

		addPositionChangeListener(new PositionChangeListener() {
			@Override
			public void onCameraPositionChanged(Vector3 position) {
				float sb = convertUnitsZ2ElevationBar(position.z);
				tableTool.sliderElevation.setVisualPercent(sb);
			}

			@Override
			public void onZoomChanged(float fieldOfView) {
				getC().dataRetrieveThreadManager.triggerUpdateVisibilityByZooming();
			}

			@Override
			public void onCameraDirectionChanged(Vector3 direction, Vector3 up) {

			}
		});
	}

	public Vector3 detectClicked3DPosition(float screenX, float screenY) {
		if (impactPixmap == null)
			return null;
		Vector3 impact = impactPixmap.findPointOfImpactForScreenCoords((int) screenX, (int) screenY);
		return impact;
	}

	public void addPositionChangeListener(PositionChangeListener positionChangeListener) {
		this.positionChangeListeners.add(positionChangeListener);
	}

	// TODO: this should only be called from ElevationImageProviderManager:
	public void setCurrentCoordLocation(double longitude, double latitude, double elevation) {
		elevation += LIFT_ELEV;

		tableTool.setRefreshNeeded(true);

		boolean missingData = getC().checkMissingData.checkMissingIfNotDismissed(latitude, longitude);
		tableDownloadData.getTable().setVisible(missingData);

		boolean missingElev = CheckMissingData.checkMissingElevationForCoord(latitude, longitude);
		if (missingElev) {
			labelLoading.setState(NO_DATA);
		} else {
			labelLoading.setState(LOADING);
		}

		if (P.getCoordinatesFirstTime())
			return;

		cam.smoothDirection();

		moveCameraAction.setCameraVectors(
				// TODO: which latitude? Why not getC().L.getCurrentTargetLatitude() ?
				new Vector3((float)convertLonitsToLatits(longitude, latitude),
						(float)latitude,
						(float)(elevation)),
				cam.direction,
				Vector3.Z,
				true
		);

		float percz = convertUnitsZ2ElevationBar((float)elevation);
		tableTool.sliderElevation.setVisualPercent(percz);

		for (PositionChangeListener positionChangeListener : positionChangeListeners) {
			positionChangeListener.onCameraPositionChanged(cam.position.cpy());
			positionChangeListener.onCameraDirectionChanged(cam.direction.cpy(), cam.up.cpy());
		}

		tableLocation.setButtonHereFromGps();

		getC().dataRetrieveThreadManager.triggerUpdateVisibilityPositionChanged();

	}

	public void pointCameraForGyroscope(float xDir, float yDir, float zDir,
										float xUp, float yUp, float zUp, boolean landscape, boolean upsideDown) {
		Vector3 dir = new Vector3(xDir, yDir, zDir);
		Vector3 up = new Vector3(xUp, yUp, zUp);
		if (!landscape) {
			up.crs(dir);
		}
		if (upsideDown) {
			up.scl(-1);
		}
		moveCameraAction.setCameraVectors(
				null,
				dir,
				up,
				true
		);
	}

	public final float LIFT_ELEV = Units.convertMetersToLatits(20);
	public final float MAX_ELEV_BAR_ELEV = Units.convertMetersToLatits(25000);

	public double convertUnitsElevationBar2Z(float visualPerc) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		return baseEle + (MAX_ELEV_BAR_ELEV - baseEle)*Interpolation.exp5In.apply(visualPerc);
	}

	public float convertUnitsZ2ElevationBar(float z) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		return Interpolation.exp5Out.apply((z - baseEle)/(MAX_ELEV_BAR_ELEV - baseEle));
	}

	public void setCameraElevationBar(float elevation) {
		double newElevation = convertUnitsElevationBar2Z(elevation);
		moveCameraAction.camQueueLock.writeLock().lock();
		try {
			cam.position.z = (float) newElevation;
			cam.update();
			triggerElevationChanged = true;
		} finally {
			moveCameraAction.camQueueLock.writeLock().unlock();
		}
	}

	public void toast(String text) {
		labelElevationChange.setText(text);
		tableCenter.setVisible(true);
		lastElevationChange = System.currentTimeMillis();
	}

	public void takeSnapshot() {
		flagTakeSnapshot = true;
	}

	private Stage stage;
	private Stage stageCopyright;
	private Stage stageNavigationOverview;

	public void showOnceGraphics() {
		spriteBatch = new SpriteBatch();
		spriteBatchOutlines = new SpriteBatch();
		spriteBatchOutlines.setShader(new ShaderProgram(
				Gdx.files.internal("vertex_shader_outlines.glsl"),
				Gdx.files.internal("fragment_shader_outlines.glsl")
		));

		shapeRenderer = new ShapeRenderer();
		// shapeRenderer.setAutoShapeType(true);

		cam = new PerspectiveCameraExt(baseFieldOfView, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		cam.near = 0.0001f;
		cam.far = 15f;
		cam.direction.set(P.getLastCameraDirectionFlat());
		cam.up.set(P.getLastCameraUp());
		cam.update();
		moveCameraAction.setCameraToActUpon(cam);

		impactPixmap = new ImpactPixmap(cam);

		// These call are necessary to generate the bitmap fonts
		// and have enough time to cache them:
		getC().styleSingleton.updateMinSize();
		getC().styleSingleton.generateAllFonts();

		createStagesOnce();

		// BitmapFont bitmapFont = new BitmapFont();

		createTablesOnces();
	}

	private void createStagesOnce() {
		// stageViewport = new ScreenViewport(new OrthographicCamera());
		int minSize = Integer.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		stageViewport = new ExtendViewport(minSize, minSize, new OrthographicCamera());
		stage = new Stage(stageViewport);
		stageCopyright = new Stage(stageViewport);
		// stageNavigationViewport = new FitViewport(100, 800, new OrthographicCamera());
		stageNavigationViewport = new FitViewport(sidebarProp*Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), new OrthographicCamera());
		stageNavigationOverview = new Stage(stageNavigationViewport);

	}

	private void createTablesOnces() {
		float widgetUnitStep = Units.getWidgetUnitStep();

		WidgetGetter widgetGetter = new WidgetGetter(mapApp, widgetUnitStep);
		getC().widgetGetter = widgetGetter;

		tableWatermark = widgetGetter.getTableWatermark();

		tableLocation = widgetGetter.getTableLocation();
		stage.addActor(tableLocation.getTable());

		Table tableCopyright = widgetGetter.getTableCopyright();
		stageCopyright.addActor(tableCopyright);

		stage.addActor(tableLocation.progressBarTable);

		optionPane = new OptionPane(tableLocation.optionsButton, widgetUnitStep);
		stage.addActor(optionPane.getTable());
		stage.addActor(optionPane.getTableOneColumn());
		stage.addActor(optionPane.getSelectBoxSatelliteSource());
		stage.addActor(optionPane.getSelectBoxUnits());
		stage.addActor(optionPane.getSelectInfoOpts());
		// stage.addActor(optionPane.getTableAppInfo());
		optionPane.hide();

		buttonPinLoc = new ImageButton(getC().widgetTextures.getTextureRegionDrawable("icons/icon_loc_pin.png"));
		buttonPinLoc.setWidth(25);
		buttonPinLoc.setHeight(50);
		buttonPinLoc.addListener(new ChangeListener() {
			@Override
			public void changed(ChangeEvent event, Actor actor) {
				buttonPinLoc.setVisible(false);
				tableLocation.tableCancelGoToDest.setVisible(false);
			}
		});
		buttonPinLoc.setVisible(false);
		tableLocation.tableCancelGoToDest.setVisible(false);
		stage.addActor(buttonPinLoc);

		tableDownloadData = widgetGetter.getTableDownloadData();
		stage.addActor(tableDownloadData.getTable());

		tableTool = widgetGetter.getTableTool();

		tableCenter = new Table();
		tableCenter.setFillParent(true);
		tableCenter.setVisible(false);
		tableCenter.center();
		labelElevationChange = new Label("", getC().styleSingleton.getLabelStyle());
		// labelElevationChange.setFontScale(3f);
		tableCenter.add(labelElevationChange).height(widgetUnitStep).row();

		stage.addActor(tableTool.getTable());
		stage.addActor(tableCenter);

		labelLoading = new LabelLoading(widgetUnitStep);
		stage.addActor(labelLoading.getTableCenterNoData());

		labelRenderer = new LabelRenderer(
				spriteBatch, shapeRenderer, new Texture(Gdx.files.internal("icons/icon_compass.png")),
				widgetUnitStep);
		labelRenderer.setBackgroundAlpha(
				tableTool.sliderCameraAlpha.getVisualPercent()
		);
	}

	public void showOnce() {

		P = new PreferencesManager();

		getC().createI18NifNeeded();

		MapSqlite mapSqlite = getC().mapSqlite;

		if (!mapSqlite.isConnectionOpen()) {
			mapSqlite.openConnection();
			mapSqlite.createTables();
		}

		mapSqlite.cleanQueue();

		getC().tileManager.tileRenderer.initialize();
		getC().cacheDirManager = new CacheDirManager();

		getC().checkMissingData = new CheckMissingData(mapSqlite);

		showOnceGraphics();

		createControllerOnce();

		//controller.pinchZoomFactor;
		//controller.scrollFactor;

		// stage.addActor(createPopupWindow());

		getC().dataRetrieveThreadManager = new DataRetrieveThreadManager(getC());

		// Starting threads after the current location object created and preferences have been read
		// ( getC().L = new CurrentLocation(C); + getC().L.loadCoordsFromLastPreferences(); )
		controller.target = new Vector3(0, 0, 0.05f);

		getC().luceneGeonameSearch = new LuceneGeonameSearch();

		getC().L.loadCoordsFromLastPreferences();

		// TODO: load camera direction from preferences. How to save camera direction?

		environment = new Environment();
		final float amL = 1f;
		environment.set(new ColorAttribute(ColorAttribute.AmbientLight, amL, amL, amL, 1f));

		tileBatchRenderer = new TileBatchRenderer(cam, environment);

		resetMultiplexerOnce();

		needToBeShown = false;
	}

	private void resetMultiplexerOnce() {
		multiplexer = new InputMultiplexer();
		multiplexer.addProcessor(stage);
		multiplexer.addProcessor(stageNavigationOverview);
		multiplexer.addProcessor(controller);

		Gdx.input.setInputProcessor(multiplexer);
	}

	private void createControllerOnce() {

		controller = MountainInputController.getInstance(cam, positionChangeListeners, this);
		controller.pinchZoomFactor = 0.03f;
		controller.translateUnits = 0.15f;
	}

	@Override
	public void show() {
		Gdx.input.setInputProcessor(multiplexer);
		// Gdx.input.setCatchBackKey(true);
		// TODO: this should be applied to the other screens as well?
		Gdx.input.setCatchKey(Input.Keys.BACK, false);
	}

	@Override
	public void resize(int width, int height) {
		// spriteBatch = new SpriteBatch();
		Gdx.app.postRunnable(() -> {
			cam.viewportWidth = width;
			cam.viewportHeight = height;
			cam.update();
			cam.resizeFieldOfViewToBounds();

			stageViewport.update(width, height, true);
			stageNavigationViewport.update(width, height, true);

			labelRenderer.resize(width, height);

			spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
			spriteBatchOutlines.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
			shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());
			Gdx.gl.glViewport(0, 0, width, height);
			tileBatchRenderer.resize(width, height);

			getC().dataRetrieveThreadManager.triggerUpdateVisibilityByZooming();

			if (optionPane.isVisible()) {
				optionPane.show();
			}
			backgroundPicManager.recomputeSizes();
		});
	}

	Environment environment;

	private void updateCameraInputController() {
		float z = cam.position.z;

		controller.pinchZoomFactor = z/10.f;
		controller.translateUnits = z;
		controller.rotationFactor = controller.rotationFactorBase*cam.fieldOfView/baseFieldOfView;
		controller.update();
	}

	private final Vector3 prev_position = new Vector3();
	private final Matrix4 prev_combined = new Matrix4();

	private void clearScreen() {
		// Sky color (not really necessary, will be reset by GLSL script):
		Gdx.gl.glClearColor(135/255f, 206/255f, 250/255f, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
		Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

		// Gdx.gl20.glDepthRangef(0.0001f, 100.0f);

		Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL);
		Gdx.gl20.glPolygonOffset(0.01f, 1.0f);

		// Gdx.gl20.glDepthFunc(GL20.GL_GREATER);
		// Gdx.gl30.glDepthFunc(GL30.GL_GREATER);
		// Gdx.gl20.glCullFace(GL20.GL_FRONT);

		Gdx.gl.glDepthMask(true);
	}

	@Override
	public void render(float deltaTime) {

		if (paused) {
			return;
		}

		float targetLat = getC().L.getTargetLatitude();
		float targetLon = getC().L.getTargetLongitude();

		if (-0.01 <= cam.position.x && cam.position.x <= 0.01 &&
				-0.01 <= cam.position.y && cam.position.y <= 0.01 &&
				!(-0.01 <= targetLat && targetLat <= 0.01) &&
				!(-0.01 <= targetLon && targetLon <= 0.01)
		) {
			cam.position.x = (float) convertLonitsToLatits(targetLon, targetLat);
			cam.position.y = targetLat;
			cam.position.z = 0.001f;
			cam.update();
		}

		if (P.getCoordinatesFirstTime() && getC().L.isCurrentLocationNotSet() && (!searchLocationOpen)) {
			searchLocationOpenTimeCounter += deltaTime;
			if (-0.01 <= targetLat && targetLat <= 0.01 &&
					-0.01 <= targetLon && targetLon <= 0.01 &&
					searchLocationOpenTimeCounter > 3.0f) {
				// if more than 3 seconds have elapse, open the screen location chooser.
				getNativeScreenCaller().openScreenSearchLocation(null);
				searchLocationOpen = true;
			}
		}

		tileBatchRenderer.startElevationRetrievalAndAssignmentThreads();

		clearScreen();

		if (mapApp.isPaused()) {
			return;
		}

		if (!moveCameraAction.isComplete()) {
			moveCameraAction.act(deltaTime);
		}

		controller.update();

		updateCameraInputController();

		boolean flagChange = false;
		if (triggerElevationChanged) {
			getC().dataRetrieveThreadManager.triggerUpdateVisibilityElevationChanged();
			triggerElevationChanged = false;
			flagChange = true;
		} else if (prev_position.x != cam.position.x || prev_position.y != cam.position.y) {
			getC().dataRetrieveThreadManager.triggerUpdateVisibilityPositionChanged();
			prev_position.set(cam.position);
			flagChange = true;
		} else if (!Arrays.equals(prev_combined.getValues(), cam.combined.getValues())) {
			getC().dataRetrieveThreadManager.triggerUpdateVisibilityCameraRotated();
			long timeNow = System.currentTimeMillis();
			if (timeNow - timeLastSaveCameraOrientation > 250) {
				timeLastSaveCameraOrientation = timeNow;
				getC().L.executorSavePreferences.execute(() -> {
					P.setLastCameraOrientation(cam);
				});
			}
			prev_combined.set(cam.combined);
			flagChange = true;
		}

		/*
		if (flagChange) {
			getAppState().setLastAnyMapTileUpdateTimeToNow();
		}
		 */

		// getC().tileManager.startDrawLayerThread();

		if (tableCenter.isVisible()) {
			long currentTime = System.currentTimeMillis();
			if (currentTime - lastElevationChange > 1000) {
				tableCenter.setVisible(false);
			}
		}

		if (disposeRunnable != null) {
			disposeRunnable.run();
			disposeRunnable = null;
		}


		getC().mapTilePixmapToTexturesHandler.renderTextureJoinerAllTiles();

		if (backgroundPicManager.getBackgroundPixmap() != null) {
			if (tableTool.isRefreshNeeded()) {
				tileBatchRenderer.render();
				boolean refreshNeeded = false;
				for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
					switch (mapTile.getMapTileState()) {
						case ELEVATION_DATA_NOT_LOADED:
						case CAN_DRAW:
							refreshNeeded = true;
							break;
						default:
					}
				}
				tableTool.setRefreshNeeded(refreshNeeded);
			}
			labelRenderer.renderBackgroundPixmap();
		} else {
			// TODO: this prevents roads from being displayed in snapshot mode!
			tileBatchRenderer.render();
		}

		// The order of these two cannot be changed, otherwise bad outlines will appear!
		tileBatchRenderer.renderPseudodistancesGeographical(impactPixmap);
		tileBatchRenderer.renderPseudodistances();

		// tileBatchRenderer.renderPseudodistancesNoFrameBuffer();

		if (flagChange) {
			updateImpact();
		}

		Texture sobelTexture = tileBatchRenderer.getSobelTexture();
		renderSobelOutlines(sobelTexture);

		if (flagChange) {
			getC().O.iterateOverDisplayablePois(poiObject -> poiObject.drawLabel.updatePosition());
		}

		labelRenderer.render(deltaTime);

		if (flagTakeSnapshot) {
			flagTakeSnapshot = false;
			/*
			stageCopyright.act();
			try {
				stageCopyright.draw();
			} catch (IllegalStateException illegalStateException) {
				if (stageCopyright.getBatch().isDrawing())
					stageCopyright.getBatch().end();
			}
			 */
			Pixmap snapshot = getSnapshotForSharing();
			getC().submitExecutorGeneric(() -> {
				mapApp.nativeScreenCaller.shareSnapshot(snapshot);
			});
		}

		labelRenderer.renderLevelingLine();

		// stageViewport.apply();
		stage.act();
		try {
			stage.draw();
		} catch (IllegalStateException illegalStateException) {
			if (stage.getBatch().isDrawing())
				stage.getBatch().end();
		}

	}

	private Pixmap getSnapshotForSharing() {
		int sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
		Texture background = backgroundPicManager.getBackgroundTexture();
		Pixmap pixmap = Pixmap.createFromFrameBuffer(
				0, 0,
				sw, sh);
		if (background == null) {
			return pixmap;
		} else {
			int iw = backgroundPicManager.getWidth(), ih = backgroundPicManager.getHeight();
			Pixmap newPixmap = new Pixmap(iw, ih, pixmap.getFormat());
			newPixmap.drawPixmap(pixmap, 0, 0, (sw-iw)/2, (sh-ih)/2, sw, sh);
			pixmap.dispose();
			return newPixmap;
		}
	}

	void setOutlinePolyXUniforms(ShaderProgram shaderProgram) {
		shaderProgram.setUniformf("u_polyXa", cam.getPolyXa());
		shaderProgram.setUniformf("u_polyXc", cam.getPolyXc());
	}

	void setOutlinePolyYUniforms(ShaderProgram shaderProgram) {
		shaderProgram.setUniformf("u_polyYa", cam.getPolyYa());
		shaderProgram.setUniformf("u_polyYc", cam.getPolyYc());
	}

	private void renderSobelOutlines(Texture sobelTexture) {
		int w = sobelTexture.getWidth();
		int h = sobelTexture.getHeight();
		spriteBatchOutlines.begin();
		float backgroundAlpha = labelRenderer.getBackgroundAlpha();
		int pictureMode = (backgroundPicManager.getBackgroundPixmap() == null)? 0 : 1;
		if (pictureMode == 0)
			backgroundAlpha = 1.0f;
		// spriteBatchOutlines.getShader().setUniformi("u_pictureMode", pictureMode);
		spriteBatchOutlines.getShader().setUniformf("u_backgroundAlpha", backgroundAlpha);
		spriteBatchOutlines.getShader().setUniformf("u_textureWidth", w);
		spriteBatchOutlines.getShader().setUniformf("u_textureHeight", h);
		setOutlinePolyXUniforms(spriteBatchOutlines.getShader());
		setOutlinePolyYUniforms(spriteBatchOutlines.getShader());
		// spriteBatchOutlines.getShader().setUniformMatrix("u_invProjectionView", cam.invProjectionView);
		spriteBatchOutlines.draw(sobelTexture, 0, 0, w, h, 0, 0, w, h, false, true);
		spriteBatchOutlines.end();
	}

	@Override
	public void pause() {
		paused = true;
	}

	@Override
	public void resume() {
		paused = false;
	}

	@Override
	public void hide() {

	}

	@Override
	public void dispose() {
		labelRenderer.dispose();
	}

	public boolean updateImpact() {
		if (impact == null)
			return false;
		int distanceMeters = Units.computeDistanceBetweenWorldVectors(impact, cam.position);
		if (distanceMeters > 1000000) {
			impact = null;
			impactDistanceMeters = null;
			buttonPinLoc.setVisible(false);
			tableLocation.tableCancelGoToDest.setVisible(false);
			return false;
		} else {
			impactDistanceMeters = distanceMeters;
			return buttonPinLocUpdatePosition();
		}
	}

	public void impactToastDistance() {
		toast(" " + s("Distance") + " " +
				Units.formatDistanceToUnitSystem(
						impactDistanceMeters, 10000) + " ");
	}

	public boolean buttonPinLocUpdatePosition() {
		if (impact == null || buttonPinLoc == null)
			return false;
		Vector3 proj = cam.project(impact.cpy());
		if (proj.x < 0 || proj.x >= Gdx.graphics.getWidth() || proj.y < 0 || proj.y >= Gdx.graphics.getHeight()) {
			buttonPinLoc.setVisible(false);
			return false;
		}
		/*
		Vector3 otherImpact = detectClicked3DPosition(
				(int) proj.x,
				Gdx.graphics.getHeight() - (int) proj.y);
		if (otherImpact != null && Units.computeDistanceBetweenWorldVectors(impact, otherImpact) > 0.045f * impactDistanceMeters) {
			// toast("diff: " + Units.computeDistanceBetweenWorldVectors(impact, otherImpact));
			impact = null;
			impactDistanceMeters = null;
			buttonPinLoc.setVisible(false);
			tableLocation.tableCancelGoToDest.setVisible(false);
			return false;
		}
		 */
		buttonPinLoc.setVisible(true);
		tableLocation.tableCancelGoToDest.setVisible(true);
		buttonPinLoc.setPosition(
				proj.x - 0.5f*buttonPinLoc.getWidth(),
				proj.y);
		return true;
	}

	public void removeImpact() {
		buttonPinLoc.setVisible(false);
		tableLocation.tableCancelGoToDest.setVisible(false);
		impact = null;
		impactDistanceMeters = null;
	}
}
