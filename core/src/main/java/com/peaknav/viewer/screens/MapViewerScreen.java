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
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

import com.badlogic.gdx.utils.Pools;
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
import com.peaknav.viewer.widgets.KeyboardHelpOverlay;
import com.peaknav.viewer.widgets.WidgetGetter;


public class MapViewerScreen implements Screen {

	public final MapApp mapApp;

	public PerspectiveCameraExt cam;
    public Button buttonPinLoc;
	public WidgetGetter.TableLocation tableLocation;
	public Table tableWatermark;
	public WidgetGetter.TableTool tableTool;
	private KeyboardHelpOverlay keyboardHelpOverlay;

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
	public com.peaknav.viewer.renderer_gdx.SkyRenderer skyRenderer;
	private final float[] skyColorTmp = new float[3];
	private final float[] skySunDir = new float[3];
	private TileBatchRenderer tileBatchRenderer;
	private volatile GpxFrameRequest pendingGpxFrame;

	/** Low/high points of a just-loaded GPX, so the next location settle can frame the track. */
	private static final class GpxFrameRequest {
		final double lowLat, lowLon, highLat, highLon;
		final float lowEleMeters, highEleMeters; // NaN when the GPX had no elevation
		GpxFrameRequest(double lowLat, double lowLon, float lowEleMeters,
						double highLat, double highLon, float highEleMeters) {
			this.lowLat = lowLat;
			this.lowLon = lowLon;
			this.lowEleMeters = lowEleMeters;
			this.highLat = highLat;
			this.highLon = highLon;
			this.highEleMeters = highEleMeters;
		}
	}

	public void requestGpxFraming(double lowLat, double lowLon, float lowEleMeters,
								  double highLat, double highLon, float highEleMeters) {
		pendingGpxFrame = new GpxFrameRequest(lowLat, lowLon, lowEleMeters,
				highLat, highLon, highEleMeters);
	}

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

		// The observer moved: recompute Sun/Moon/planet/star positions for the new location.
		getC().skyModel.invalidate();

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

		boolean gpxFramed = false;        // suppress the default "drop camera on target" bar handling
		boolean appliedGpxFraming = false; // actually (re)computed the framing on this call
		if (pendingGpxFrame != null) {
			// A GPX was just loaded: instead of dropping the camera on the target, frame the whole
			// track from a high vantage and fly there.
			GpxFrameRequest request = pendingGpxFrame;
			pendingGpxFrame = null;
			applyGpxFraming(request);
			// Hold the framing: as the destination's tiles stream in, this callback re-fires for
			// the same target; without this those re-fires would snap the camera onto the target
			// (with its mid-fly heading) and wreck the framing — worst when coming from far, where
			// more tiles load. Remember the target so a genuine user move still moves the camera.
			gpxFrameHoldUntilMs = System.currentTimeMillis() + GPX_FRAME_HOLD_MS;
			gpxFrameLat = latitude;
			gpxFrameLon = longitude;
			gpxFramed = true;
			appliedGpxFraming = true;
		} else if (System.currentTimeMillis() < gpxFrameHoldUntilMs
				&& Math.abs(latitude - gpxFrameLat) < 1e-4
				&& Math.abs(longitude - gpxFrameLon) < 1e-4) {
			// Same GPX target re-firing during the fly/settle: leave the camera and bar alone (the
			// position listener below keeps the bar synced, preserving any manual elevation change).
			gpxFramed = true;
		} else {
			gpxFrameHoldUntilMs = 0L;
			moveCameraAction.setCameraVectors(
					// TODO: which latitude? Why not getC().L.getCurrentTargetLatitude() ?
					new Vector3((float)convertLonitsToLatits(longitude, latitude),
							(float)latitude,
							(float)(elevation)),
					cam.direction,
					Vector3.Z,
					true
			);
		}

		if (!gpxFramed) {
			float percz = convertUnitsZ2ElevationBar((float)elevation);
			tableTool.sliderElevation.setVisualPercent(percz);
		}

		for (PositionChangeListener positionChangeListener : positionChangeListeners) {
			positionChangeListener.onCameraPositionChanged(cam.position.cpy());
			positionChangeListener.onCameraDirectionChanged(cam.direction.cpy(), cam.up.cpy());
		}

		tableLocation.setButtonHereFromGps();

		if (appliedGpxFraming) {
			// Align the bar with the framed camera height (the camera is still flying there, so its
			// current z isn't it yet). Done after the listeners so it is the final word; the ground
			// reference it measures against was set in applyGpxFraming.
			tableTool.sliderElevation.setVisualPercent(convertUnitsZ2ElevationBar(gpxCamPos.z));
		}

		getC().dataRetrieveThreadManager.triggerUpdateVisibilityPositionChanged();

	}

	private final Vector3 gpxCamPos = new Vector3();
	private final Vector3 gpxLookDir = new Vector3();
	private final Vector3 gpxLowW = new Vector3();
	private final Vector3 gpxHighW = new Vector3();

	/** Seconds of the smooth fly-and-rotate into the GPX framing. */
	private static final float GPX_FLY_SECONDS = 2.6f;
	/** Minimum camera height above the track's low point, so the whole path is seen from high up. */
	private static final float GPX_MIN_HEIGHT_METERS = 5000f;
	/** How long, after a GPX framing, to ignore re-fired location callbacks for the same target. */
	private static final long GPX_FRAME_HOLD_MS = 9000L;

	private long gpxFrameHoldUntilMs = 0L;
	private double gpxFrameLat, gpxFrameLon;

	/**
	 * Frames the loaded track vertically: the low point on the bottom edge of the screen, the high
	 * point on the top edge, from a camera at least {@link #GPX_MIN_HEIGHT_METERS} above the low
	 * point (higher for long tracks). It then flies there smoothly, rotating as it goes.
	 *
	 * <p>The camera, low point and high point are coplanar (the vertical plane through the low point
	 * along the low->high heading), so it solves in that plane: the camera sits at height H behind
	 * the low point; the look pitch is fixed to put the low point on the bottom frustum edge, and
	 * the back-distance B is solved so the high point lands on the top edge.
	 */
	private void applyGpxFraming(GpxFrameRequest r) {
		gpxWorld(r.lowLat, r.lowLon, r.lowEleMeters, gpxLowW);
		gpxWorld(r.highLat, r.highLon, r.highEleMeters, gpxHighW);

		float dhx = gpxHighW.x - gpxLowW.x;
		float dhy = gpxHighW.y - gpxLowW.y;
		float dh = (float) Math.sqrt(dhx * dhx + dhy * dhy); // horizontal separation
		float dz = gpxHighW.z - gpxLowW.z;                   // elevation gain (latits)
		float len = (float) Math.sqrt(dh * dh + dz * dz);    // low->high 3D distance

		// Horizontal heading low->high; if the two are vertically stacked, stand off to the south.
		float ux, uy;
		if (dh < 1e-7f) {
			ux = 0f;
			uy = -1f;
		} else {
			ux = dhx / dh;
			uy = dhy / dh;
		}

		float theta = (float) Math.toRadians(cam.fieldOfView); // vertical field of view
		// At least 5000 m above the low point, and higher for a long track so it isn't cramped.
		float height = Math.max(0.5f * len, Units.convertMetersToLatits(GPX_MIN_HEIGHT_METERS));
		float back = solveGpxBack(dh, dz, height, theta);

		gpxCamPos.set(gpxLowW.x - ux * back, gpxLowW.y - uy * back, gpxLowW.z + height);

		// Look pitch: depression down to the low point, raised by half the FOV so the low point sits
		// exactly on the bottom frustum edge.
		float depression = (float) Math.atan2(height, back);
		float af = -depression + theta * 0.5f;
		float cosF = (float) Math.cos(af);
		float sinF = (float) Math.sin(af);
		gpxLookDir.set(ux * cosF, uy * cosF, sinF).nor();

		// The elevation bar measures altitude above the ground under the camera, but the camera is
		// no longer over the map target — so point that ground reference at the camera's own spot.
		// Read the terrain there if it's loaded; otherwise use the track's high point, a safe floor
		// that keeps scrolling the bar down from diving underground.
		float camLat = gpxCamPos.y;
		float camLon = Units.convertLatitsToLonits(gpxCamPos.x, camLat);
		Float sampled = com.peaknav.elevation.ElevationUtils.getElevationLatitsFromMaxCoords(
				camLon, camLat, false);
		float groundZ;
		if (sampled != null) {
			groundZ = sampled - com.peaknav.elevation.ElevationUtils
					.getElevationCorrectionForRoundEarth(camLat, camLon);
		} else {
			groundZ = Math.max(gpxLowW.z, gpxHighW.z);
		}
		getC().L.setCurrentTerrainEleQuiet(groundZ);

		flyToGpxFraming();
	}

	private void flyToGpxFraming() {
		// Smooth ease-in-out fly, position and heading interpolating together over the whole move.
		moveCameraAction.setCameraVectors(gpxCamPos, gpxLookDir, Vector3.Z,
				false, Interpolation.smooth, false, 0f, 1f, GPX_FLY_SECONDS);
	}

	/**
	 * With the camera at (-B, H) in the vertical plane (low=(0,0), high=(dh,dz)) and the look pitch
	 * pinned so the low point is on the bottom edge, the high point lands on the top edge when
	 * atan2(dz-H, dh+B) + atan2(H, B) = theta. Solve that for the back-distance B by bisection.
	 */
	private static float solveGpxBack(float dh, float dz, float height, float theta) {
		float lo = 1e-6f;
		float hi = 1.0f; // latits; ~100 km, plenty of range
		float glo = gpxBackResidual(lo, dh, dz, height, theta);
		float ghi = gpxBackResidual(hi, dh, dz, height, theta);
		if ((glo < 0f) == (ghi < 0f)) {
			return height; // no bracket (near-vertical/degenerate): fall back to a 45-degree look
		}
		for (int i = 0; i < 40; i++) {
			float mid = 0.5f * (lo + hi);
			float gm = gpxBackResidual(mid, dh, dz, height, theta);
			if ((gm < 0f) == (glo < 0f)) {
				lo = mid;
				glo = gm;
			} else {
				hi = mid;
			}
		}
		return 0.5f * (lo + hi);
	}

	private static float gpxBackResidual(float back, float dh, float dz, float height, float theta) {
		return (float) Math.atan2(dz - height, dh + back)
				+ (float) Math.atan2(height, back) - theta;
	}

	// --- Cinematic GPX tour: fly along the track from above, then orbit its end 360 degrees. ---
	private static final float GPX_TOUR_HEIGHT_METERS = 700f;   // camera height above the track
	private static final float GPX_TOUR_BACK_METERS = 500f;     // camera set back behind each point
	private static final float GPX_TOUR_INTRO_SECONDS = 2.5f;   // ease-in from the current view
	private static final float GPX_TOUR_SECONDS = 22f;          // total time flying along the track
	private static final float GPX_ORBIT_RADIUS_METERS = 900f;
	private static final float GPX_ORBIT_HEIGHT_METERS = 500f;
	private static final int GPX_ORBIT_STEPS = 24;
	private static final float GPX_ORBIT_STEP_SECONDS = 0.28f;  // ~6.7 s for the full circle

	/**
	 * Plays a cinematic tour of the loaded GPX track: the camera flies along it from above (looking
	 * a little ahead so the mountains around the path show), then circles its end point through a
	 * full 360 degrees. Queued as a sequence of camera moves, so it plays hands-free.
	 */
	public void startGpxFlythrough() {
		com.peaknav.gpx.GpxTrack track = null;
		for (com.peaknav.gpx.GpxTrack t : getC().gpxManager.getTracks()) {
			if (track == null || t.size() > track.size()) {
				track = t;
			}
		}
		if (track == null || track.size() < 2) {
			return;
		}
		java.util.List<com.peaknav.gpx.GpxTrack.Point> pts = track.getPoints();
		int n = pts.size();

		// Subsample to at most ~40 waypoints, always keeping the exact last point.
		int step = Math.max(1, (int) Math.ceil(n / 40.0));
		java.util.List<Vector3> wp = new java.util.ArrayList<>();
		for (int i = 0; i < n; i += step) {
			wp.add(gpxTourWorld(pts.get(i)));
		}
		Vector3 lastW = gpxTourWorld(pts.get(n - 1));
		if (wp.isEmpty() || wp.get(wp.size() - 1).dst(lastW) > 1e-6f) {
			wp.add(lastW);
		}
		int m = wp.size();
		if (m < 2) {
			return;
		}

		float heightAbove = Units.convertMetersToLatits(GPX_TOUR_HEIGHT_METERS);
		float backDist = Units.convertMetersToLatits(GPX_TOUR_BACK_METERS);
		float totalLen = 0f;
		for (int i = 0; i + 1 < m; i++) {
			totalLen += gpxHoriz(wp.get(i), wp.get(i + 1));
		}
		if (totalLen < 1e-9f) {
			return;
		}

		// Fly along: above and behind each waypoint, looking a couple of waypoints ahead.
		Vector3 lastCamPos = new Vector3();
		for (int i = 0; i < m; i++) {
			Vector3 cur = wp.get(i);
			Vector3 fwd = gpxForward(wp, i);
			Vector3 camPos = new Vector3(cur.x - fwd.x * backDist, cur.y - fwd.y * backDist,
					cur.z + heightAbove);
			Vector3 aheadPt = wp.get(Math.min(i + 2, m - 1));
			Vector3 lookDir = new Vector3(aheadPt).sub(camPos).nor();
			lastCamPos.set(camPos);
			if (i == 0) {
				moveCameraAction.setCameraVectors(camPos, lookDir, Vector3.Z,
						true, Interpolation.smooth, false, 0f, 1f, GPX_TOUR_INTRO_SECONDS);
			} else {
				float segSec = Math.max(0.12f,
						gpxHoriz(wp.get(i - 1), cur) / totalLen * GPX_TOUR_SECONDS);
				moveCameraAction.setCameraVectors(camPos, lookDir, Vector3.Z,
						false, Interpolation.linear, false, 0f, 1f, segSec);
			}
		}

		// 360-degree orbit around the end point, starting where the fly-along left off.
		Vector3 endW = wp.get(m - 1);
		float orbitR = Units.convertMetersToLatits(GPX_ORBIT_RADIUS_METERS);
		float orbitH = Units.convertMetersToLatits(GPX_ORBIT_HEIGHT_METERS);
		Vector3 lookAt = new Vector3(endW.x, endW.y, endW.z + Units.convertMetersToLatits(60f));
		float startAng = (float) Math.atan2(lastCamPos.y - endW.y, lastCamPos.x - endW.x);
		for (int s = 1; s <= GPX_ORBIT_STEPS; s++) {
			float ang = startAng + (float) (2.0 * Math.PI * s / GPX_ORBIT_STEPS);
			Vector3 op = new Vector3(endW.x + (float) Math.cos(ang) * orbitR,
					endW.y + (float) Math.sin(ang) * orbitR, endW.z + orbitH);
			Vector3 od = new Vector3(lookAt).sub(op).nor();
			moveCameraAction.setCameraVectors(op, od, Vector3.Z,
					false, Interpolation.linear, false, 0f, 1f, GPX_ORBIT_STEP_SECONDS);
		}

		// Keep re-fired location callbacks from snapping the camera for the whole tour.
		float tourSeconds = GPX_TOUR_INTRO_SECONDS + GPX_TOUR_SECONDS
				+ GPX_ORBIT_STEPS * GPX_ORBIT_STEP_SECONDS + 2f;
		gpxFrameHoldUntilMs = System.currentTimeMillis() + (long) (tourSeconds * 1000f);
		gpxFrameLat = getC().L.getTargetLatitude();
		gpxFrameLon = getC().L.getTargetLongitude();
	}

	private Vector3 gpxTourWorld(com.peaknav.gpx.GpxTrack.Point p) {
		Vector3 v = new Vector3();
		gpxWorld(p.lat, p.lon, p.hasElevation ? p.eleMeters : Float.NaN, v);
		return v;
	}

	private static float gpxHoriz(Vector3 a, Vector3 b) {
		float dx = b.x - a.x;
		float dy = b.y - a.y;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	/** Unit horizontal heading of the track at waypoint i. */
	private static Vector3 gpxForward(java.util.List<Vector3> wp, int i) {
		int m = wp.size();
		Vector3 a = (i == m - 1) ? wp.get(i - 1) : wp.get(i);
		Vector3 b = (i == m - 1) ? wp.get(i) : wp.get(i + 1);
		Vector3 f = new Vector3(b.x - a.x, b.y - a.y, 0f);
		return (f.len() < 1e-9f) ? new Vector3(0f, 1f, 0f) : f.nor();
	}

	private void gpxWorld(double lat, double lon, float eleMeters, Vector3 out) {
		float ele = Float.isNaN(eleMeters)
				? Units.convertLatitsToMeters((float) getC().L.getCurrentTerrainEle())
				: eleMeters;
		float corr = com.peaknav.elevation.ElevationUtils.getElevationCorrectionForRoundEarth(
				(float) lat, (float) lon);
		// Scale longitude by cos(targetLat), the single reference the terrain and POIs use, so this
		// world position lines up with them (not each point's own latitude — see GpxPathRenderer).
		out.set((float) convertLonitsToLatits(lon, getC().L.getTargetLatitude()),
				(float) lat,
				Units.convertMetersToLatits(ele) - corr);
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

	/**
	 * Moves the elevation bar by a fraction of its full travel, positive upwards. Goes
	 * through the slider rather than straight to the camera so that the knob, the
	 * toast and the elevation itself stay in step, exactly as when the bar is dragged.
	 *
	 * @param deltaPercent how far to move, 1.0 being the whole bar
	 */
	public void nudgeCameraElevationBar(float deltaPercent) {
		if (tableTool == null)
			return;
		Slider slider = tableTool.sliderElevation;
		slider.setVisualPercent(MathUtils.clamp(slider.getVisualPercent() + deltaPercent, 0f, 1f));
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

	/**
	 * Shows the keyboard-controls overlay. Kept separate from the tutorial: it is
	 * raised when the user presses a key that has no binding (see
	 * {@link com.peaknav.gesture.MountainInputController}). The overlay is itself gated
	 * on a hardware keyboard being present, so this is a no-op on touch-only devices.
	 */
	public void showKeyboardControls() {
		if (keyboardHelpOverlay != null) {
			keyboardHelpOverlay.show();
		}
	}

	public void hideKeyboardControls() {
		if (keyboardHelpOverlay != null) {
			keyboardHelpOverlay.hide();
		}
	}

	public boolean isKeyboardControlsVisible() {
		return keyboardHelpOverlay != null && keyboardHelpOverlay.isVisible();
	}

	/**
	 * Fires the "?" icon button as if it had been clicked, so a keyboard "?" and the
	 * on-screen button run the exact same action on every platform.
	 */
	public void activateHelpButton() {
		if (tableLocation == null || tableLocation.helpButton == null) {
			return;
		}
		ChangeListener.ChangeEvent changeEvent = Pools.obtain(ChangeListener.ChangeEvent.class);
		tableLocation.helpButton.fire(changeEvent);
		Pools.free(changeEvent);
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
		stage.addActor(optionPane.getSelectBoxDownloadSource());
		stage.addActor(optionPane.getSelectBoxUnits());
		stage.addActor(optionPane.getSelectInfoOpts());
		stage.addActor(optionPane.getSelectGpx());
		stage.addActor(optionPane.getSelectLabels());
		stage.addActor(optionPane.getSelectSky());
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

		// Added last so its scrim draws on top of every other widget when shown.
		keyboardHelpOverlay = new KeyboardHelpOverlay(widgetUnitStep);
		stage.addActor(keyboardHelpOverlay.getRoot());

		labelRenderer = new LabelRenderer(
				spriteBatch, shapeRenderer, new Texture(Gdx.files.internal("icons/icon_compass.png")),
				widgetUnitStep);
		skyRenderer = new com.peaknav.viewer.renderer_gdx.SkyRenderer(spriteBatch, shapeRenderer);
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

	/** When the camera last rotated, and whether a settle-time overlap pass is still owed. */
	private long lastCameraRotationMillis = 0L;
	private boolean overlapSettlePending = false;
	/** How long the camera must sit still after rotating before the overlap pass is re-run. */
	private static final long OVERLAP_SETTLE_DELAY_MILLIS = 250L;

	/**
	 * Keeps the sky model and the terrain relief light in step with the observer and the clock.
	 * When the sky view is on, the terrain is lit from the real Sun (dim ambient at night); when it
	 * is off, the fixed NW/45° cartographic light is restored for legibility.
	 */
	private void updateSky() {
		if (!P.isSkyView()) {
			getC().sunLight.setFromAzimuthAltitude(
					com.peaknav.viewer.SunLight.DEFAULT_AZIMUTH_DEGREES,
					com.peaknav.viewer.SunLight.DEFAULT_ALTITUDE_DEGREES);
			return;
		}
		getC().skyModel.update(getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude(),
				System.currentTimeMillis());
		getC().skyModel.getSunDirection(skySunDir);
		getC().sunLight.setDirection(skySunDir[0], skySunDir[1], skySunDir[2]);
	}

	private void clearScreen() {
		// Sky color: day-blue by default, or the astronomically-tinted sky (blue by day, dark at
		// night, dusk in between) driven by the computed Sun altitude when the sky view is on.
		if (P.isSkyView()) {
			com.peaknav.viewer.renderer_gdx.SkyRenderer.skyColor(
					getC().skyModel.getSunAltitudeDeg(), skyColorTmp);
			Gdx.gl.glClearColor(skyColorTmp[0], skyColorTmp[1], skyColorTmp[2], 1);
		} else {
			Gdx.gl.glClearColor(135/255f, 206/255f, 250/255f, 1);
		}
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

		if (tableLocation != null && tableLocation.buttonGpxFly != null) {
			tableLocation.buttonGpxFly.setVisible(!getC().gpxManager.isEmpty());
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

		updateSky();

		clearScreen();

		if (mapApp.isPaused()) {
			return;
		}

		if (!moveCameraAction.isComplete()) {
			moveCameraAction.act(deltaTime);
		}

		// updateCameraInputController() ends in controller.update(), so calling it here
		// too would advance every held key twice per frame.
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
			lastCameraRotationMillis = timeNow;
			overlapSettlePending = true;
			flagChange = true;
		}

		// The rotation overlap pass only fires every few degrees, so a final small turn can
		// leave labels overlapping once the camera stops. When it has been still briefly, run
		// one more pass to de-overlap the orientation the user actually landed on.
		if (overlapSettlePending && !flagChange
				&& System.currentTimeMillis() - lastCameraRotationMillis > OVERLAP_SETTLE_DELAY_MILLIS) {
			overlapSettlePending = false;
			getC().dataRetrieveThreadManager.triggerUpdateVisibilityLabelOverlap();
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
			// Sky objects are drawn before the terrain so opaque terrain occludes anything below a
			// ridge (correct horizon hiding for free).
			if (P.isSkyView()) {
				skyRenderer.render();
			}
			// TODO: this prevents roads from being displayed in snapshot mode!
			tileBatchRenderer.render();
		}

		// The order of these two cannot be changed, otherwise bad outlines will appear!
		tileBatchRenderer.renderPseudodistancesGeographical(impactPixmap);
		tileBatchRenderer.renderPseudodistancesIfNeeded(flagChange);

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
		if (controller != null) {
			// No keyUp arrives for a key that was held when the window went away.
			controller.clearKeyboardLook();
		}
	}

	@Override
	public void resume() {
		paused = false;
		if (tileBatchRenderer != null) {
			// Android drops the contents of every frame buffer when the GL context goes
			// away, so the cached pseudodistances cannot be reused across a resume.
			tileBatchRenderer.invalidatePseudodistances();
		}
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
