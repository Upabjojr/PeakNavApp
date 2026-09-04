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
import com.badlogic.gdx.math.Vector2;
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
		// While label updates are held, the camera is scripted from outside frame by
		// frame - the renderer places it and nothing else may. This callback fires from
		// tile loads at times of the tiles' own choosing, and the fly it starts stole
		// single frames from rendered videos whenever a load completed mid-chunk.
		if (getC().dataRetrieveThreadManager.isLabelUpdatesHeld()) {
			return;
		}
		elevation += LIFT_ELEV;

		// The observer moved: recompute Sun/Moon/planet/star positions for the new location.
		getC().skyModel.invalidate();

		// A photo waiting for its location may now have terrain to be matched against.
		com.peaknav.viewer.PhotoSkylineAligner.onLocationSettled(latitude, longitude);

		tableTool.setRefreshNeeded(true);

		boolean missingData = getC().checkMissingData.checkMissingIfNotDismissed(latitude, longitude);
		tableDownloadData.getTable().setVisible(missingData);

		boolean missingElev = CheckMissingData.checkMissingElevationForCoord(latitude, longitude);
		if (missingElev) {
			labelLoading.setState(NO_DATA);
		} else {
			labelLoading.setState(LOADING);
		}

		// Nothing below this point makes sense before the user has a location at all: it would fly
		// the camera to the null island the app starts on. But the preference alone is the wrong
		// test for that. It is cleared on a background thread by saveCoordinatesToPreferences,
		// from inside the very call that then asks for the camera to be placed
		// (CurrentLocation.setCurrentFinalCoords), and it is read straight off disk with no cache
		// — so on a first run this still read "true" when the freshly downloaded elevation
		// arrived, the camera was never lowered onto the terrain, and it stayed off the ground
		// until the user next moved. currentLocationNotSet is cleared synchronously just before,
		// so requiring both closes that window while still holding for a genuinely fresh install.
		if (P.getCoordinatesFirstTime() && getC().L.isCurrentLocationNotSet())
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
	// The viewing distance is derived from the size of the track rather than fixed: a flat 700 m
	// above / 500 m behind sat so close to the path that the surrounding mountains were out of
	// frame. These bounds keep a short walk from being viewed from orbit and a long alpine route
	// from being followed with the peaks cropped away.
	private static final float GPX_TOUR_HEIGHT_FRACTION = 0.45f; // of the track's bounding span
	private static final float GPX_TOUR_HEIGHT_MIN_METERS = 2600f;
	private static final float GPX_TOUR_HEIGHT_MAX_METERS = 8000f;
	private static final float GPX_TOUR_BACK_FRACTION = 0.65f;
	private static final float GPX_TOUR_BACK_MIN_METERS = 4000f;
	private static final float GPX_TOUR_BACK_MAX_METERS = 12000f;
	/**
	 * Field of view while touring. The map's normal 30 degrees is a long lens: it crops to the
	 * path and cuts the peaks on either side out of frame no matter how far back the camera sits.
	 * Widening it for the tour is what actually brings the surrounding mountains into shot; the
	 * previous value is restored when the tour ends.
	 */
	private static final float GPX_TOUR_FIELD_OF_VIEW = 62f;
	private static final float GPX_TOUR_INTRO_SECONDS = 2.5f;   // ease-in from the current view
	private static final float GPX_TOUR_SECONDS = 22f;          // total time flying along the track
	private static final float GPX_TOUR_SEEK_SECONDS = 1.2f;    // ease-in after a scrub-bar jump
	/**
	 * Path smoothing. The track is resampled to this many evenly spaced points and then low-pass
	 * filtered: the window is a fraction of that count, so the amount of smoothing scales with the
	 * route instead of with how densely the receiver happened to log. Two passes of a box filter
	 * approximate a Gaussian, which is enough to turn GPS zig-zag into a trend line the camera can
	 * follow without jerking. The heading is measured over +/- the lookahead, so it changes over a
	 * long baseline and curves come in gradually.
	 */
	private static final int GPX_TOUR_SAMPLES = 160;
	private static final float GPX_SMOOTH_WINDOW_FRACTION = 0.10f;
	private static final int GPX_SMOOTH_PASSES = 2;
	private static final float GPX_LOOKAHEAD_FRACTION = 0.05f;
	/** Orbit radius/height as a multiple of the fly-along setback/height. */
	private static final float GPX_ORBIT_RADIUS_FACTOR = 1.0f;
	private static final float GPX_ORBIT_HEIGHT_FACTOR = 0.7f;
	// 10 degrees per step: fine enough that the circle reads as a smooth arc rather than a polygon.
	private static final int GPX_ORBIT_STEPS = 36;
	private static final float GPX_ORBIT_STEP_SECONDS = 0.34f;
	/** How much longer the final orbit step lasts than the first, so the circle eases to a stop. */
	private static final float GPX_ORBIT_SLOWDOWN = 2.2f;

	/**
	 * Plays a cinematic tour of the loaded GPX track: the camera flies along it from above and a
	 * little behind, looking ahead down the route so the surrounding mountains stay in frame, then
	 * makes one decelerating orbit of the end point.
	 *
	 * <p>The camera does NOT follow the recorded points directly. A GPS trace is noisy and unevenly
	 * spaced — sampling it as-is made the camera jitter and snap through every switchback. Instead
	 * the track is resampled at even spacing along its length and then low-pass filtered, so the
	 * camera flies the general trend of the route at a constant speed and the recorded wobble is
	 * averaged away. The heading is taken over a long baseline on that smoothed path rather than
	 * between neighbouring points, which is what keeps curves gradual.
	 *
	 * <p>The result is stored as keyframes so the tour can be replayed from any point
	 * ({@link #seekGpxTour}) when the user drags the scrub bar.
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

		// Every recorded point, in world space; the smoothing below needs the full resolution.
		java.util.List<Vector3> raw = new java.util.ArrayList<>(pts.size());
		for (int i = 0; i < pts.size(); i++) {
			Vector3 w = gpxTourWorld(pts.get(i));
			if (raw.isEmpty() || raw.get(raw.size() - 1).dst(w) > 1e-7f) {
				raw.add(w); // drop exact duplicates: they carry no heading
			}
		}
		if (raw.size() < 2) {
			return;
		}

		// De-noise, then space evenly, then polish — in that order, and it matters. Arc length
		// along a raw GPS trace is dominated by the jitter rather than by forward progress, so
		// resampling first spends samples wherever the receiver wobbled most and the camera still
		// speeds up and slows down. Smoothing the dense samples first gives a curve whose length
		// is real distance; only then does even spacing mean even speed.
		java.util.List<Vector3> path = gpxResampleByLength(raw, GPX_TOUR_SAMPLES * 3);
		int denseWindow = Math.max(3, (int) (path.size() * GPX_SMOOTH_WINDOW_FRACTION) | 1);
		path = gpxMovingAverage(path, denseWindow, GPX_SMOOTH_PASSES);
		path = gpxResampleByLength(path, GPX_TOUR_SAMPLES);
		int window = Math.max(3, (int) (path.size() * GPX_SMOOTH_WINDOW_FRACTION) | 1);
		path = gpxMovingAverage(path, window, GPX_SMOOTH_PASSES);
		int m = path.size();
		if (m < 2) {
			return;
		}

		// Size the viewing distance from the track's own extent (see the constants).
		float spanMeters = gpxSpanMeters(path);
		float heightMeters = MathUtils.clamp(GPX_TOUR_HEIGHT_FRACTION * spanMeters,
				GPX_TOUR_HEIGHT_MIN_METERS, GPX_TOUR_HEIGHT_MAX_METERS);
		float backMeters = MathUtils.clamp(GPX_TOUR_BACK_FRACTION * spanMeters,
				GPX_TOUR_BACK_MIN_METERS, GPX_TOUR_BACK_MAX_METERS);
		float heightAbove = Units.convertMetersToLatits(heightMeters);
		float backDist = Units.convertMetersToLatits(backMeters);

		// Camera positions: behind and above the smoothed path, using a heading measured over a
		// long baseline (+/- lookahead samples) rather than between neighbours.
		int lookahead = Math.max(2, Math.round(m * GPX_LOOKAHEAD_FRACTION));
		java.util.List<Vector3> camPath = new java.util.ArrayList<>(m);
		for (int i = 0; i < m; i++) {
			Vector3 cur = path.get(i);
			Vector3 a = path.get(Math.max(0, i - lookahead));
			Vector3 b = path.get(Math.min(m - 1, i + lookahead));
			Vector3 fwd = new Vector3(b.x - a.x, b.y - a.y, 0f);
			if (fwd.len() < 1e-9f) {
				fwd.set(0f, 1f, 0f);
			}
			fwd.nor();
			camPath.add(new Vector3(cur.x - fwd.x * backDist, cur.y - fwd.y * backDist,
					cur.z + heightAbove));
		}
		// One more pass over the camera track itself: the offset can still kink where the route
		// doubles back on itself.
		camPath = gpxMovingAverage(camPath, window, GPX_SMOOTH_PASSES);

		gpxTourFrames.clear();
		float stepSeconds = GPX_TOUR_SECONDS / Math.max(1, m - 1);
		for (int i = 0; i < m; i++) {
			Vector3 camPos = camPath.get(i);
			// Aim at a point further along the smoothed route, so the camera leads into curves.
			Vector3 aim = path.get(Math.min(m - 1, i + lookahead));
			Vector3 dir = new Vector3(aim).sub(camPos).nor();
			gpxTourFrames.add(new GpxTourFrame(camPos, dir,
					i == 0 ? GPX_TOUR_INTRO_SECONDS : stepSeconds, i == 0));
		}

		// Exactly one orbit of the end point, starting from wherever the fly-along left off.
		// Every frame re-aims at the end point, so the camera circles it while keeping it in the
		// middle of frame — it orbits the summit rather than spinning on the spot. The frames get
		// progressively longer so the circle eases to a stop instead of ending mid-swing.
		Vector3 endW = path.get(m - 1);
		Vector3 lastCamPos = camPath.get(m - 1);
		float orbitR = Units.convertMetersToLatits(backMeters * GPX_ORBIT_RADIUS_FACTOR);
		float orbitH = Units.convertMetersToLatits(heightMeters * GPX_ORBIT_HEIGHT_FACTOR);
		Vector3 lookAt = new Vector3(endW.x, endW.y, endW.z + Units.convertMetersToLatits(60f));
		float startAng = (float) Math.atan2(lastCamPos.y - endW.y, lastCamPos.x - endW.x);
		for (int s = 1; s <= GPX_ORBIT_STEPS; s++) {
			float ang = startAng + (float) (2.0 * Math.PI * s / GPX_ORBIT_STEPS);
			Vector3 op = new Vector3(endW.x + (float) Math.cos(ang) * orbitR,
					endW.y + (float) Math.sin(ang) * orbitR, endW.z + orbitH);
			Vector3 od = new Vector3(lookAt).sub(op).nor();
			float t = (float) s / GPX_ORBIT_STEPS;
			gpxTourFrames.add(new GpxTourFrame(op, od,
					GPX_ORBIT_STEP_SECONDS * (1f + (GPX_ORBIT_SLOWDOWN - 1f) * t * t), false));
		}

		queueGpxTourFrom(0);
	}

	/** One queued camera pose of the tour. Kept so the tour can be restarted from any point. */
	private static final class GpxTourFrame {
		final Vector3 pos;
		final Vector3 dir;
		final float seconds;
		final boolean intro; // the first frame eases in from wherever the camera currently is
		GpxTourFrame(Vector3 pos, Vector3 dir, float seconds, boolean intro) {
			this.pos = pos;
			this.dir = dir;
			this.seconds = seconds;
			this.intro = intro;
		}
	}

	private final java.util.List<GpxTourFrame> gpxTourFrames = new java.util.ArrayList<>();

	/** (Re)queues the tour from the given keyframe, replacing anything already queued. */
	private void queueGpxTourFrom(int firstFrame) {
		if (gpxTourFrames.isEmpty()) {
			return;
		}
		firstFrame = MathUtils.clamp(firstFrame, 0, gpxTourFrames.size() - 1);
		moveCameraAction.clearSteps();
		float total = 0f;
		for (int i = firstFrame; i < gpxTourFrames.size(); i++) {
			GpxTourFrame f = gpxTourFrames.get(i);
			total += f.seconds;
			if (i == firstFrame) {
				// Ease in from the current view rather than cutting to the new pose.
				moveCameraAction.setCameraVectors(f.pos, f.dir, Vector3.Z,
						true, Interpolation.smooth, false, 0f, 1f,
						f.intro ? GPX_TOUR_INTRO_SECONDS : GPX_TOUR_SEEK_SECONDS);
			} else {
				moveCameraAction.addFlatStep(f.pos, f.dir, Vector3.Z,
						Interpolation.linear, f.seconds);
			}
		}
		if (!gpxTourActive) {
			gpxFieldOfViewBeforeTour = cam.fieldOfView; // restored by stopGpxFlythrough
		}
		cam.fieldOfView = GPX_TOUR_FIELD_OF_VIEW;
		cam.update();
		gpxTourActive = true;
		// Keep re-fired location callbacks from snapping the camera for the rest of the tour.
		gpxFrameHoldUntilMs = System.currentTimeMillis() + (long) ((total + 2f) * 1000f);
		gpxFrameLat = getC().L.getTargetLatitude();
		gpxFrameLon = getC().L.getTargetLongitude();
	}

	/** Progress through the tour, 0..1, for the scrub bar. */
	public float getGpxTourProgress() {
		int total = gpxTourFrames.size();
		if (!gpxTourActive || total == 0) {
			return 0f;
		}
		int remaining = moveCameraAction.remainingSteps();
		return MathUtils.clamp((total - remaining) / (float) total, 0f, 1f);
	}

	/** Jumps the tour to a fraction of the way along and carries on from there. */
	public void seekGpxTour(float fraction) {
		if (gpxTourFrames.isEmpty()) {
			return;
		}
		boolean wasPaused = moveCameraAction.isPaused();
		queueGpxTourFrom(Math.round(MathUtils.clamp(fraction, 0f, 1f) * (gpxTourFrames.size() - 1)));
		moveCameraAction.setPaused(wasPaused);
	}

	/**
	 * Resamples a polyline to {@code n} points spaced evenly along its length, so the tour advances
	 * at a constant speed instead of lingering wherever the receiver logged points densely.
	 */
	private static java.util.List<Vector3> gpxResampleByLength(java.util.List<Vector3> src, int n) {
		int srcN = src.size();
		float[] cum = new float[srcN];
		for (int i = 1; i < srcN; i++) {
			cum[i] = cum[i - 1] + src.get(i - 1).dst(src.get(i));
		}
		float total = cum[srcN - 1];
		java.util.List<Vector3> out = new java.util.ArrayList<>(n);
		if (total < 1e-9f) {
			out.add(new Vector3(src.get(0)));
			out.add(new Vector3(src.get(srcN - 1)));
			return out;
		}
		int seg = 0;
		for (int i = 0; i < n; i++) {
			float want = total * i / (n - 1);
			while (seg < srcN - 2 && cum[seg + 1] < want) {
				seg++;
			}
			float segLen = cum[seg + 1] - cum[seg];
			float t = (segLen < 1e-9f) ? 0f : (want - cum[seg]) / segLen;
			out.add(new Vector3(src.get(seg)).lerp(src.get(seg + 1), t));
		}
		return out;
	}

	/**
	 * Centred moving average over the points; repeating it approximates a Gaussian blur, which is
	 * what turns the recorded zig-zag into the smooth trend line the camera follows. Endpoints keep
	 * their position (the window shrinks at the ends) so the tour still starts and finishes on the
	 * track.
	 */
	private static java.util.List<Vector3> gpxMovingAverage(
			java.util.List<Vector3> src, int window, int passes) {
		java.util.List<Vector3> cur = src;
		int half = window / 2;
		for (int p = 0; p < passes; p++) {
			java.util.List<Vector3> out = new java.util.ArrayList<>(cur.size());
			for (int i = 0; i < cur.size(); i++) {
				int lo = Math.max(0, i - half);
				int hi = Math.min(cur.size() - 1, i + half);
				// Shrink symmetrically at the ends, otherwise the average drags the first and last
				// points inward and the tour no longer lines up with the track.
				int reach = Math.min(i - lo, hi - i);
				float x = 0f, y = 0f, z = 0f;
				for (int k = i - reach; k <= i + reach; k++) {
					Vector3 v = cur.get(k);
					x += v.x;
					y += v.y;
					z += v.z;
				}
				int count = 2 * reach + 1;
				out.add(new Vector3(x / count, y / count, z / count));
			}
			cur = out;
		}
		return cur;
	}

	/** True from the moment a tour is queued until its last camera move finishes or it is stopped. */
	private boolean gpxTourActive = false;
	/** Which icon the play/pause button is currently showing, so it is only swapped on a change. */
	private boolean gpxButtonShowingPause = false;

	/**
	 * Shows the GPX buttons only while a track is loaded, and keeps the play/pause button showing
	 * the action it will perform: a pause bar while the tour runs, a play triangle otherwise.
	 */
	private void updateGpxButtons() {
		if (tableLocation == null || tableLocation.buttonGpxFly == null) {
			return;
		}
		if (gpxTourActive && moveCameraAction.isComplete()) {
			endGpxTour(); // the tour played out on its own
		}
		boolean hasGpx = !getC().gpxManager.isEmpty();
		tableLocation.buttonGpxFly.setVisible(hasGpx);
		if (tableLocation.buttonGpxClear != null) {
			tableLocation.buttonGpxClear.setVisible(hasGpx);
		}
		boolean showPause = isGpxTourPlaying();
		if (showPause != gpxButtonShowingPause) {
			gpxButtonShowingPause = showPause;
			tableLocation.buttonGpxFly.getStyle().up = getC().widgetTextures.getTextureRegionDrawable(
					showPause ? "icons/icon_gpx_pause.png" : "icons/icon_gpx_play.png");
		}

		// Scrub bar: visible for as long as a tour is loaded (playing or paused), tracking
		// progress except while the user has hold of the knob.
		boolean tourLive = gpxTourActive && !moveCameraAction.isComplete();
		tableLocation.gpxSeekTable.setVisible(tourLive);
		if (tourLive && !tableLocation.gpxSeekSlider.isDragging()) {
			gpxSeekSliderUpdating = true;
			try {
				tableLocation.gpxSeekSlider.setValue(getGpxTourProgress());
			} finally {
				gpxSeekSliderUpdating = false;
			}
		}
	}

	/** Guards the scrub bar's change listener while the code (not the user) moves the knob. */
	private boolean gpxSeekSliderUpdating = false;

	/** Whether a GPX tour is running or paused (i.e. the pause/play button should show pause). */
	public boolean isGpxTourPlaying() {
		return gpxTourActive && !moveCameraAction.isComplete() && !moveCameraAction.isPaused();
	}

	public boolean isGpxTourPaused() {
		return gpxTourActive && !moveCameraAction.isComplete() && moveCameraAction.isPaused();
	}

	/** Play / pause / restart, all from the one on-map button. */
	public void toggleGpxFlythrough() {
		if (isGpxTourPlaying()) {
			moveCameraAction.setPaused(true);
		} else if (isGpxTourPaused()) {
			moveCameraAction.setPaused(false);
			// The hold expires while paused; extend it so the resumed tour is not interrupted.
			gpxFrameHoldUntilMs = Math.max(gpxFrameHoldUntilMs,
					System.currentTimeMillis() + 60_000L);
		} else {
			startGpxFlythrough();
		}
	}

	/** Abandons a running tour and releases the camera (used when the GPX itself is cleared). */
	public void stopGpxFlythrough() {
		endGpxTour();
		moveCameraAction.clearSteps();
		gpxTourFrames.clear(); // don't keep keyframes for a track that is going away
		gpxFrameHoldUntilMs = 0L;
	}

	/** Field of view to put back when the tour finishes; NaN while no tour has widened it. */
	private float gpxFieldOfViewBeforeTour = Float.NaN;

	/** Marks the tour finished and restores the pre-tour field of view. */
	private void endGpxTour() {
		gpxTourActive = false;
		if (!Float.isNaN(gpxFieldOfViewBeforeTour)) {
			cam.fieldOfView = gpxFieldOfViewBeforeTour;
			gpxFieldOfViewBeforeTour = Float.NaN;
			cam.update();
		}
	}

	/** Largest horizontal extent of the track, in metres — drives how far back the camera sits. */
	private static float gpxSpanMeters(java.util.List<Vector3> wp) {
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < wp.size(); i++) {
			Vector3 v = wp.get(i);
			if (v.x < minX) minX = v.x;
			if (v.x > maxX) maxX = v.x;
			if (v.y < minY) minY = v.y;
			if (v.y > maxY) maxY = v.y;
		}
		float dx = maxX - minX;
		float dy = maxY - minY;
		return Units.convertLatitsToMeters((float) Math.sqrt(dx * dx + dy * dy));
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

	/** How far above the terrain the camera sits when the bar is at the bottom. */
	public static final double GROUND_CLEARANCE_METERS = 20;
	/** Ceiling of the elevation bar, in metres above sea level. */
	public static final double MAX_ELEV_BAR_METERS = 25000;

	public final float LIFT_ELEV = Units.convertMetersToLatits(GROUND_CLEARANCE_METERS);
	public final float MAX_ELEV_BAR_ELEV = Units.convertMetersToLatits(MAX_ELEV_BAR_METERS);

	// ---- Camera height ---------------------------------------------------------------------
	//
	// Two layers, deliberately separate:
	//
	//   metres  - setCameraElevationMeters / getCameraElevationMeters. A height above the
	//             ground, in the unit the thing actually has. This is the primitive, and what
	//             any caller that knows where it wants the camera should use.
	//   the bar - setCameraElevationBar and the convert* helpers, a wrapper over the metre
	//             layer that applies the slider's feel: exp5In, so dragging near the bottom
	//             moves the camera a few metres at a time and dragging near the top moves it
	//             kilometres. That curve belongs to the user interface, not to the camera.
	//
	// Keeping them apart matters because the curve is steep: bar 0.45 is about 2.5 km up, not
	// 450 m. Callers that reason in metres (scripted renders, tests, anything computing a
	// viewpoint) went through the bar and silently flew several times too high.

	/** Fraction of the way from the ground to the ceiling, as the bar's curve maps it. */
	private static float barToSpanFraction(float visualPerc) {
		return Interpolation.exp5In.apply(MathUtils.clamp(visualPerc, 0f, 1f));
	}

	/**
	 * The inverse of {@link #barToSpanFraction}. Not exp5Out: that is a different easing
	 * curve, not the inverse of exp5In, and using it here meant a camera height converted to
	 * a bar position and back moved by up to 11% of the bar's travel.
	 */
	private static float spanFractionToBar(float fraction) {
		final float min = 1f / 32f;                       // 2^-5, from Interpolation.exp5In
		fraction = MathUtils.clamp(fraction, 0f, 1f);
		return MathUtils.clamp((float)(1 + Math.log(fraction * (1 - min) + min)
				/ (5 * Math.log(2))), 0f, 1f);
	}

	/** Camera height above the terrain, in metres, for a bar position. */
	public double convertElevationBar2Meters(float visualPerc) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		return Units.convertLatitsToMeters(
				(MAX_ELEV_BAR_ELEV - baseEle) * barToSpanFraction(visualPerc));
	}

	/** The bar position that corresponds to a camera height above the terrain, in metres. */
	public float convertMeters2ElevationBar(double metersAboveGround) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		float span = MAX_ELEV_BAR_ELEV - baseEle;
		if (span <= 0)
			return 0f;
		return spanFractionToBar(Units.convertMetersToLatits(metersAboveGround) / span);
	}

	public double convertUnitsElevationBar2Z(float visualPerc) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		return baseEle + (MAX_ELEV_BAR_ELEV - baseEle) * barToSpanFraction(visualPerc);
	}

	public float convertUnitsZ2ElevationBar(float z) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		float span = MAX_ELEV_BAR_ELEV - baseEle;
		return span <= 0 ? 0f : spanFractionToBar((z - baseEle) / span);
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

	/**
	 * Puts the camera a given height above the terrain, in metres. The primitive: no easing
	 * curve, no bar, the number means what it says. Clamped to the bar's ceiling so the two
	 * modes cannot disagree about how high the camera may go.
	 *
	 * @param metersAboveGround height above the terrain at the current position
	 */
	public void setCameraElevationMeters(double metersAboveGround) {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		float z = baseEle + Units.convertMetersToLatits(Math.max(0, metersAboveGround));
		setCameraZ(Math.min(z, MAX_ELEV_BAR_ELEV));
	}

	/**
	 * Puts the camera at an absolute height above sea level, ignoring the ground beneath it.
	 *
	 * <p>The third mode, and the one a flight wants. Height above the terrain is right for a
	 * viewpoint - "stand 600 m up" - but wrong for a moving camera: as it crosses ridges and
	 * valleys the ground rises and falls beneath it, so a camera held at a constant height
	 * above ground rides up and down, and whatever it is pointed at bobs in the frame. An
	 * orbit holds this constant instead.
	 *
	 * @param metersAboveSeaLevel absolute altitude; raised if it would be underground
	 */
	public void setCameraAltitudeMeters(double metersAboveSeaLevel) {
		float ground = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		float z = Units.convertMetersToLatits(metersAboveSeaLevel);
		setCameraZ(Math.min(Math.max(z, ground), MAX_ELEV_BAR_ELEV));
	}

	/** Where the camera is now, in metres above sea level. */
	public double getCameraAltitudeMeters() {
		return Units.convertLatitsToMeters(cam.position.z);
	}

	/** Where the camera is now, in metres above the terrain beneath it. */
	public double getCameraElevationMeters() {
		float baseEle = (float)getC().L.getCurrentTerrainEle() + LIFT_ELEV;
		return Units.convertLatitsToMeters(cam.position.z - baseEle);
	}

	/**
	 * Puts the camera where the elevation bar says. A wrapper over
	 * {@link #setCameraElevationMeters}: it converts the bar's position through the slider
	 * curve first, so the interface keeps its feel - fine control low down, kilometres per
	 * drag up high - while the camera itself is still positioned in metres.
	 *
	 * @param elevation bar position, 0 (on the ground) to 1 (the ceiling)
	 */
	public void setCameraElevationBar(float elevation) {
		setCameraElevationMeters(convertElevationBar2Meters(elevation));
	}

	private void setCameraZ(float z) {
		moveCameraAction.camQueueLock.writeLock().lock();
		try {
			cam.position.z = z;
			cam.update();
			triggerElevationChanged = true;
			// An orbit re-imposes its own height on EVERY frame (see advanceOrbit), so
			// without this the next frame undid whatever the elevation bar had just done:
			// the camera twitched and snapped straight back, and changing height while
			// orbiting a pinned point simply did not work. This is the one place that can
			// tell the difference - every deliberate height change arrives here, and the
			// orbit's own per-frame pose does not (it goes through setCameraVectors). So a
			// height the user asked for becomes the height the orbit holds, while the
			// orbit still ignores the ground rising and falling beneath it.
			orbitHeight = z;
		} finally {
			moveCameraAction.camQueueLock.writeLock().unlock();
		}
	}

	public void toast(String text) {
		toast(text, TOAST_MILLIS);
	}

	/** A toast that stays for {@code millis} instead of the usual second. */
	public void toast(String text, long millis) {
		toastHeld = false;
		toastMillis = millis;
		setToastSpinning(false);
		showToast(text);
	}

	private static final long TOAST_MILLIS = 1000;
	private long toastMillis = TOAST_MILLIS;

	/**
	 * A toast that stays on screen until the next {@link #toast} or {@link #releaseToast}
	 * instead of fading after a second: for work in progress, so "Matching the photo..."
	 * is still there when the result replaces it, however long the matching takes.
	 */
	public void toastUntilReleased(String text) {
		showToast(text);
		toastHeld = true;
		setToastSpinning(true);
	}

	/** Lets a held toast fade as usual from now. */
	public void releaseToast() {
		toastHeld = false;
		toastMillis = TOAST_MILLIS;
		setToastSpinning(false);
		lastElevationChange = System.currentTimeMillis();
	}

	private boolean toastHeld;

	private void showToast(String text) {
		// Short texts (a distance, a height) keep the one-line pill; anything wider than the
		// screen wraps onto as many lines as it needs, instead of running off both edges.
		float maxWidth = 0.9f * Gdx.graphics.getWidth();
		toastGlyph.setText(labelElevationChange.getStyle().font, text);
		if (toastGlyph.width > maxWidth) {
			labelElevationChange.setWrap(true);
			labelElevationChange.setAlignment(com.badlogic.gdx.utils.Align.center);
			toastCell.width(maxWidth).height(com.badlogic.gdx.scenes.scene2d.ui.Value.prefHeight);
		} else {
			labelElevationChange.setWrap(false);
			toastCell.width(com.badlogic.gdx.scenes.scene2d.ui.Value.prefWidth).height(toastLineHeight);
		}
		labelElevationChange.setText(text);
		tableCenter.invalidate();
		tableCenter.setVisible(true);
		lastElevationChange = System.currentTimeMillis();
	}

	private final com.badlogic.gdx.graphics.g2d.GlyphLayout toastGlyph = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
	private com.badlogic.gdx.scenes.scene2d.ui.Cell<Label> toastCell;
	private float toastLineHeight;
	private com.badlogic.gdx.scenes.scene2d.ui.Image toastSpinner;
	private com.badlogic.gdx.scenes.scene2d.ui.Cell<com.badlogic.gdx.scenes.scene2d.ui.Image> toastSpinnerCell;

	/** Spins the ring beside the toast, or stops and hides it. */
	private void setToastSpinning(boolean spinning) {
		if (toastSpinner == null || toastSpinner.isVisible() == spinning) {
			return;
		}
		toastSpinner.clearActions();
		toastSpinner.setVisible(spinning);
		float size = spinning ? 0.7f * toastLineHeight : 0;
		toastSpinnerCell.size(size).padRight(spinning ? 0.3f * toastLineHeight : 0);
		if (spinning) {
			toastSpinner.setOrigin(size / 2, size / 2);
			toastSpinner.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(
					com.badlogic.gdx.scenes.scene2d.actions.Actions.rotateBy(-360, 1.6f)));
		} else {
			toastSpinner.setRotation(0);
		}
		tableCenter.invalidate();
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
		int minSize = Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
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
		stage.addActor(tableLocation.gpxSeekTable);
		tableLocation.gpxSeekSlider.addListener(new ChangeListener() {
			@Override
			public void changed(ChangeEvent event, Actor actor) {
				// Only react to the user dragging, not to the per-frame position updates below.
				if (gpxSeekSliderUpdating) {
					return;
				}
				seekGpxTour(tableLocation.gpxSeekSlider.getValue());
			}
		});

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
		stage.addActor(optionPane.getSelectCompass());
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
		toastLineHeight = widgetUnitStep;
		// The app's ring of blue balls, small and spinning before the text of a held
		// toast - the sign, while the matching runs, that the app is working and not
		// frozen. Its cell has no size until a toast is held.
		toastSpinner = new com.badlogic.gdx.scenes.scene2d.ui.Image(
				getC().widgetTextures.getTextureRegionDrawable("icons/icon_spinner.png"));
		toastSpinner.setVisible(false);
		toastSpinnerCell = tableCenter.add(toastSpinner).size(0);
		toastCell = tableCenter.add(labelElevationChange).height(widgetUnitStep);
		tableCenter.row();

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

	/**
	 * Sizes the scene2d viewport to the display's safe area, so no widget sits under
	 * the iPhone's Dynamic Island / notch or the home indicator. libGDX reports those
	 * exclusion zones as safe-area insets - in pixels here, matching HdpiMode.Pixels;
	 * zero on desktop and on Android, which does not draw under the camera cutout, so
	 * both are bit-identical to a plain {@code update(width, height, true)}. Only the
	 * widgets move: the 3D map still renders edge to edge underneath them.
	 */
	private void updateStageViewportInsideSafeArea(int width, int height) {
		int insetLeft = Gdx.graphics.getSafeInsetLeft();
		int insetRight = Gdx.graphics.getSafeInsetRight();
		int insetTop = Gdx.graphics.getSafeInsetTop();
		int insetBottom = Gdx.graphics.getSafeInsetBottom();
		stageViewport.update(
				Math.max(1, width - insetLeft - insetRight),
				Math.max(1, height - insetTop - insetBottom),
				true);
		stageViewport.setScreenPosition(insetLeft, insetBottom);
	}

	@Override
	public void resize(int width, int height) {
		// spriteBatch = new SpriteBatch();
		Gdx.app.postRunnable(() -> {
			cam.viewportWidth = width;
			cam.viewportHeight = height;
			cam.update();
			cam.resizeFieldOfViewToBounds();
			// The four 180° depth cameras must follow, or the depth pixmaps rebuilt below are
			// rendered/sampled with the old aspect and every occlusion lookup lands on the
			// wrong pixel from here on.
			cam.resizeGeographicCameras(width, height);

			updateStageViewportInsideSafeArea(width, height);
			stageNavigationViewport.update(width, height, true);

			labelRenderer.resize(width, height);

			spriteBatch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
			spriteBatchOutlines.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
			shapeRenderer.setProjectionMatrix(spriteBatch.getProjectionMatrix());
			Gdx.gl.glViewport(0, 0, width, height);
			tileBatchRenderer.resize(width, height);

			// The geographical depth pixmaps used for label/area occlusion are sized to the viewport.
			// A resize invalidates them: sampling now projects with the new viewport but reads the
			// old-size pixmaps out of bounds, so every area reads as occluded and all labels vanish
			// until the camera next moves. Request a fresh render so they are rebuilt at the new size.
			if (impactPixmap != null) {
				impactPixmap.impactPixmapNewRequested = true;
			}

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
		// Always compute the sky so the Sun is always positioned and drawn. Only when the sky view
		// is on do we light the terrain from the real Sun (and tint the sky day/night in clearScreen);
		// otherwise the map keeps its plain sky and the legible NW/45° relief light.
		getC().skyModel.update(getC().L.getCurrentLatitude(), getC().L.getCurrentLongitude(),
				getC().skyModel.currentTimeMillis());
		if (P.isSkyView()) {
			getC().skyModel.getSunDirection(skySunDir);
			getC().sunLight.setDirection(skySunDir[0], skySunDir[1], skySunDir[2]);
		} else {
			getC().sunLight.setFromAzimuthAltitude(
					com.peaknav.viewer.SunLight.DEFAULT_AZIMUTH_DEGREES,
					com.peaknav.viewer.SunLight.DEFAULT_ALTITUDE_DEGREES);
		}
	}

	private void clearScreen() {
		// Sky color: day-blue by default, or the astronomically-tinted sky (blue by day, dark at
		// night, dusk in between) driven by the computed Sun altitude when the sky view is on.
		if (P.isSkyView()) {
			com.peaknav.viewer.renderer_gdx.SkyRenderer.skyColor(
					com.peaknav.viewer.renderer_gdx.SkyRenderer.ambianceSunAltitude(
							getC().skyModel.getSunAltitudeDeg()), skyColorTmp);
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

		advanceOrbit(deltaTime);

		updateGpxButtons();

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

		if (tableCenter.isVisible() && !toastHeld) {
			long currentTime = System.currentTimeMillis();
			if (currentTime - lastElevationChange > toastMillis) {
				tableCenter.setVisible(false);
				toastMillis = TOAST_MILLIS;
			}
		}

		if (disposeRunnable != null) {
			disposeRunnable.run();
			disposeRunnable = null;
		}


		getC().mapTilePixmapToTexturesHandler.renderTextureJoinerAllTiles();

		if (backgroundPicManager.getBackgroundPixmap() != null) {
			float terrainAlpha = labelRenderer.getTerrainAlpha();
			if (terrainAlpha > 0f) {
				// The terrain-opacity bar is up: draw sky and terrain as usual and the photo
				// over them at the complementary opacity, which is the same picture as the
				// terrain at that opacity over the photo.
				skyRenderer.render();
				tileBatchRenderer.render();
			} else if (tableTool.isRefreshNeeded()) {
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
			labelRenderer.renderBackgroundPixmap(1f - terrainAlpha);
		} else {
			// Sky objects are drawn before the terrain so opaque terrain occludes anything below a
			// ridge (correct horizon hiding for free). The Sun is always drawn; the other objects are
			// gated inside the renderer by the sky-view toggle.
			skyRenderer.render();
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
		boolean pinned = com.peaknav.gesture.PhotoPin.isActive() && backgroundPicManager.getBackgroundPixmap() != null;
		if (pinned) {
			labelRenderer.renderPhotoPin();
		}
		if (tableTool != null) {
			tableTool.setPinned(pinned);
		}
		if (pendingFrameCapture != null) {
			FrameCapture capture = pendingFrameCapture;
			pendingFrameCapture = null;
			capture.onFrame(getSnapshotForSharing());
		}

		// The stage viewport is inset to the safe area (see
		// updateStageViewportInsideSafeArea), and Stage.draw does not apply its
		// viewport itself - without this the widgets would still be drawn across
		// the full glViewport while touch handling used the inset bounds.
		stageViewport.apply();
		stage.act();
		try {
			stage.draw();
		} catch (IllegalStateException illegalStateException) {
			if (stage.getBatch().isDrawing())
				stage.getBatch().end();
		}
		// The 3D pass at the top of the next frame expects the full-window
		// viewport it was resized to; put it back after the inset UI pass.
		Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

	}

	/** Receives one rendered frame; the pixmap is the receiver's to dispose. */
	public interface FrameCapture {
		void onFrame(Pixmap frame);
	}

	private volatile FrameCapture pendingFrameCapture;

	/**
	 * Hands the next rendered frame - cropped to the photo when one is shown, as the shared
	 * snapshot is - to {@code capture}, on the render thread. Any thread may ask.
	 */
	public void captureFrame(FrameCapture capture) {
		pendingFrameCapture = capture;
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

	/**
	 * Ends any 2D batch a mid-frame Throwable left open. {@link com.peaknav.viewer.MapApp#render}
	 * catches such errors, but a batch stuck in "drawing" state made every following frame's
	 * {@code begin()} throw as well — the loop then failed before drawing anything, and the app
	 * sat on a blank screen until force-closed. After this cleanup the next frame starts clean.
	 */
	public void recoverFromRenderError() {
		endBatchQuietly(spriteBatch);
		endBatchQuietly(spriteBatchOutlines);
		try {
			if (shapeRenderer != null && shapeRenderer.isDrawing()) {
				shapeRenderer.end();
			}
		} catch (Throwable ignored) {
		}
		if (stage != null)
			endBatchQuietly(stage.getBatch());
		if (stageCopyright != null)
			endBatchQuietly(stageCopyright.getBatch());
		if (stageNavigationOverview != null)
			endBatchQuietly(stageNavigationOverview.getBatch());
		// The sky pass disables depth writes; restore the default in case it blew up mid-way.
		Gdx.gl.glDepthMask(true);
	}

	static void endBatchQuietly(com.badlogic.gdx.graphics.g2d.Batch batch) {
		try {
			if (batch != null && batch.isDrawing()) {
				batch.end();
			}
		} catch (Throwable ignored) {
		}
	}

	@Override
	public void dispose() {
		// Everything the screen owns; previously only the label renderer (i.e. the compass
		// texture) was freed and all other GL/native resources leaked per screen lifecycle.
		if (labelRenderer != null)
			labelRenderer.dispose();
		if (skyRenderer != null)
			skyRenderer.dispose();
		if (tileBatchRenderer != null)
			tileBatchRenderer.dispose();
		if (impactPixmap != null)
			impactPixmap.dispose();
		if (stage != null)
			stage.dispose();
		if (stageCopyright != null)
			stageCopyright.dispose();
		if (stageNavigationOverview != null)
			stageNavigationOverview.dispose();
		if (spriteBatch != null)
			spriteBatch.dispose();
		if (spriteBatchOutlines != null) {
			// The outline shader was set with setShader(), so the batch does not own it.
			ShaderProgram outlineShader = spriteBatchOutlines.getShader();
			spriteBatchOutlines.dispose();
			if (outlineShader != null)
				outlineShader.dispose();
		}
		if (shapeRenderer != null)
			shapeRenderer.dispose();
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

	// Scratch for converting the projected impact into stage coordinates each frame.
	private final Vector2 pinStageCoords = new Vector2();
	// Scratch for cam.project, which mutates its argument; impact itself must stay in world space.
	private final Vector3 pinProjCoords = new Vector3();

	public boolean buttonPinLocUpdatePosition() {
		if (impact == null || buttonPinLoc == null)
			return false;
		Vector3 proj = cam.project(pinProjCoords.set(impact));
		if (proj.x < 0 || proj.x >= Gdx.graphics.getWidth() || proj.y < 0 || proj.y >= Gdx.graphics.getHeight()) {
			buttonPinLoc.setVisible(false);
			return false;
		}
		// cam.project yields WINDOW pixels (origin bottom-left), but the pin lives on the
		// scene2d stage, which runs in ExtendViewport world units - a different space
		// whenever the window has been resized away from its start-up size. Feeding pixels
		// straight into setPosition put the pin off its click point by the viewport scale,
		// growing toward the screen edges. Convert via the stage viewport: unproject wants
		// y measured downward, project's y is measured upward.
		pinStageCoords.set(proj.x, Gdx.graphics.getHeight() - proj.y);
		stage.getViewport().unproject(pinStageCoords);
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
				pinStageCoords.x - 0.5f*buttonPinLoc.getWidth(),
				pinStageCoords.y);
		return true;
	}

	// ---- Orbit ---------------------------------------------------------------------------
	//
	// Circles the camera around a clicked point, keeping it in view: the alternative to flying
	// to it. Everything here is in world coordinates and nothing touches the target, which is
	// what makes it safe - the world frame's east-west scale is fixed by
	// getC().L.getTargetLatitude() (see MapTile.buildVertices, LabelRenderer, PoiObject), so
	// leaving the target alone leaves the frame still and the geometry plain Euclidean.

	/** A full turn a minute: slow enough to watch, fast enough to see it move. */
	private static final double ORBIT_RADIANS_PER_SECOND = Math.toRadians(6);

	private boolean orbiting = false;
	private final Vector3 orbitCentre = new Vector3();
	private final Vector3 orbitEye = new Vector3();
	private final Vector3 orbitDirection = new Vector3();
	/** Horizontal distance from the centre, in world units, held constant. */
	private float orbitRadius;
	/** Camera height, held constant so the subject does not bob as the ground changes. */
	private float orbitHeight;
	/** Where the camera currently is around the circle, in radians. */
	private double orbitAngle;

	public boolean isOrbiting() {
		return orbiting;
	}

	/**
	 * Starts circling the given world point, from wherever the camera is now.
	 *
	 * <p>The current distance becomes the radius and the current height is held, so the orbit
	 * begins exactly where the view already is and simply starts turning.
	 */
	public void startOrbit(Vector3 centre) {
		if (centre == null) {
			return;
		}
		float dx = cam.position.x - centre.x;
		float dy = cam.position.y - centre.y;
		float radius = (float) Math.sqrt(dx * dx + dy * dy);
		if (radius < 1e-5f) {
			// Standing on it: there is no circle to walk.
			return;
		}
		orbitCentre.set(centre);
		orbitRadius = radius;
		orbitHeight = cam.position.z;
		orbitAngle = Math.atan2(dy, dx);
		orbiting = true;
	}

	public void stopOrbit() {
		orbiting = false;
	}

	/**
	 * Stops whatever scheduled path is currently driving the camera — a pinned-point orbit, a
	 * running GPX tour, or queued fly steps — so a newly chosen destination is not fought over by
	 * moves still aimed at the old one. Called from CurrentLocation when the target coordinates
	 * actually change. Deliberately leaves pendingGpxFrame alone: a GPX load files its framing
	 * request before re-targeting, and that fly belongs to the new destination, not the old.
	 */
	public void cancelScheduledCameraPath() {
		stopOrbit();
		if (gpxTourActive) {
			stopGpxFlythrough(); // also clears the queued steps and the framing hold
		} else {
			moveCameraAction.clearSteps();
		}
	}

	private void advanceOrbit(float deltaTime) {
		if (!orbiting) {
			return;
		}
		orbitAngle += ORBIT_RADIANS_PER_SECOND * deltaTime;
		orbitEye.set(
				orbitCentre.x + orbitRadius * (float) Math.cos(orbitAngle),
				orbitCentre.y + orbitRadius * (float) Math.sin(orbitAngle),
				orbitHeight);
		orbitDirection.set(orbitCentre).sub(orbitEye).nor();
		// immediate: this is a pose per frame, not a move to queue behind other moves.
		moveCameraAction.setCameraVectors(orbitEye, orbitDirection, Vector3.Z, true);
	}

	public void removeImpact() {
		buttonPinLoc.setVisible(false);
		tableLocation.tableCancelGoToDest.setVisible(false);
		impact = null;
		impactDistanceMeters = null;
	}
}
