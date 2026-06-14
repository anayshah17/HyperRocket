package info.openrocket.swing.gui.simulation;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.startup.Application;
import info.openrocket.core.util.TerrainFetcher;
import info.openrocket.swing.gui.figure3d.FigureRenderer;
import info.openrocket.swing.gui.figure3d.RealisticRenderer;
import info.openrocket.swing.gui.figure3d.RocketRenderer;
import info.openrocket.swing.gui.figure3d.TerrainRenderer;

/**
 * A panel that replays a completed simulation flight in 3D.
 * <p>
 * Each flight-data branch is rendered with its actual stage geometry: the sustainer
 * sheds boosters as they separate and each separated stage is drawn as an independent
 * body flying its own trajectory, so you can watch the stages come apart.  Deployed
 * recovery devices are drawn as an animated parachute canopy, key flight events are
 * logged in a selectable sidebar list, the ground is a locally-generated high-resolution
 * desert (no network required), and the camera orbits with a mouse drag and zooms with
 * the wheel — all the way down to the rocket itself.
 * <p>
 * Coordinate conventions used internally:
 * <ul>
 *   <li>GL X = east (OpenRocket world X)</li>
 *   <li>GL Y = altitude / up (OpenRocket world Z)</li>
 *   <li>GL Z = south = –north (negated OpenRocket world Y)</li>
 * </ul>
 */
public class SimulationReplayPanel extends JPanel implements GLEventListener {

    private static final Logger log = LoggerFactory.getLogger(SimulationReplayPanel.class);

    private static final double[] SPEEDS = { 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0 };
    private static final String[] SPEED_LABELS = { "0.1×", "0.25×", "0.5×", "1×", "2×", "5×", "10×" };
    private static final int DEFAULT_SPEED_IDX = 3; // 1×

    // Sky / atmosphere colours (warm desert sky).
    private static final float[] SKY_ZENITH = { 0.30f, 0.52f, 0.85f };   // blue overhead
    private static final float[] SKY_HORIZON = { 0.82f, 0.79f, 0.70f };  // warm sandy haze

    private final OpenRocketDocument document;
    private final Simulation simulation;
    private final FlightConfiguration configuration;

    private Component canvas; // GLJPanel (lightweight, safe for embedding in dialogs)
    private RocketRenderer renderer;
    private boolean rendererReady = false;

    /** Per-stage flight tracks (index 0 = sustainer, others = separated boosters). */
    private final List<BranchTrack> tracks = new ArrayList<>();

    /** Scratch configuration whose stage activeness is toggled per branch to render the right geometry. */
    private FlightConfiguration renderConfig;
    /** True only when renderConfig is our own clone (safe to toggle stages without corrupting the live config). */
    private boolean canToggleStages = false;
    /** All stage numbers in the rocket. */
    private int[] allStageNumbers = new int[0];
    /** Time (s) at which each stage number separates from the sustainer; absent = never. */
    private final Map<Integer, Double> separationTimeByStage = new HashMap<>();

    /** All flight events across branches, sorted by time, for the sidebar event log. */
    private final List<EventMark> allEvents = new ArrayList<>();
    /** Event types present in this flight (insertion order). */
    private final Set<FlightEvent.Type> presentEventTypes = new LinkedHashSet<>();
    /** Event types currently shown in the sidebar (user-selectable, like the plot menu). */
    private final Set<FlightEvent.Type> enabledEventTypes = new HashSet<>();

    // Branch-0 arrays mirrored for the stats side-panel / mini view.
    private double[] times = new double[0];
    private double[] altitudes = new double[0];
    private double[] thetas = new double[0];
    private double[] phis = new double[0];
    private double[] verticalSpeeds = new double[0];
    private double[] horizSpeeds = new double[0];
    private double[] dragForces = new double[0];

    private int frameCount = 0;
    private double maxAltitude = 10.0;
    private double rocketLength = 1.0;
    private double rocketRadius = 0.05;

    // Locally-generated desert ground (never null after loadFlightData).
    private TerrainFetcher.TerrainData terrain;
    private double terrainHalfExtent = 2000.0;

    // Stats side-panel references (updated each frame)
    private MiniRocketView miniRocketView;
    private JLabel statAltitude;
    private JLabel statVVert;
    private JLabel statVHoriz;
    private JLabel statDrag;
    private JLabel statTheta;
    private JLabel statHdg;
    private JLabel eventLogLabel;

    // Playback state
    private int currentFrame = 0;
    private boolean playing = false;
    private double playbackSpeed = 1.0;
    private long lastTickMs = 0;
    private double simulationTime = 0.0;

    // Repaint guard — prevents the 16ms Timer from flooding the EDT with pending GL repaints
    private volatile boolean repaintPending = false;

    // Camera orbit state (camDist is an absolute distance in metres → real zoom)
    private double cameraYaw = -Math.PI / 5.0;
    private double cameraPitch = 0.22;
    private double camDist = 30.0;
    private double defaultCamDist = 30.0;
    private double minCamDist = 0.5;
    private double maxCamDist = 5000.0;
    private int lastMouseX, lastMouseY;

    // Controls
    private JSlider timeSlider;
    private JButton playPauseButton;
    private JLabel timeLabel;
    private JComboBox<String> speedCombo;
    private Timer playbackTimer;

    public SimulationReplayPanel(OpenRocketDocument document, Simulation simulation) {
        super(new BorderLayout());
        this.document = document;
        this.simulation = simulation;
        this.configuration = simulation.getActiveConfiguration();

        loadFlightData();
        initGLCanvas();
        initControls();
        add(buildStatsPanel(), BorderLayout.EAST);
    }

    // ---- Data model --------------------------------------------------------

    /** One stage's flight history, plus its recovery-deployment time if any. */
    private static final class BranchTrack {
        final String name;
        final double[] t;
        final double[] x;     // GL east  (OR position X)
        final double[] z;     // GL south (−OR position Y)
        final double[] alt;   // GL up
        final double[] qw, qx, qy, qz;
        final double[] theta, phi;
        final double[] vz, vxy, drag;
        double deployTime = Double.NaN;  // recovery device deployment (s), NaN = none
        final boolean sustainer;
        final List<FlightEvent> events;
        /** Stage number this branch's body represents (boosters); -1 for the sustainer. */
        int stageNumber = -1;

        BranchTrack(FlightDataBranch b, boolean sustainer) {
            this.sustainer = sustainer;
            this.name = b.getName();
            this.events = new ArrayList<>(b.getEvents());
            this.t   = arr(b.get(FlightDataType.TYPE_TIME));
            this.x   = arr(b.get(FlightDataType.TYPE_POSITION_X));
            double[] posY = arr(b.get(FlightDataType.TYPE_POSITION_Y));
            this.z = new double[posY.length];
            for (int i = 0; i < posY.length; i++) this.z[i] = -posY[i];
            this.alt = arr(b.get(FlightDataType.TYPE_ALTITUDE));
            this.qw = arr(b.get(FlightDataType.TYPE_ORIENTATION_QW));
            this.qx = arr(b.get(FlightDataType.TYPE_ORIENTATION_QX));
            this.qy = arr(b.get(FlightDataType.TYPE_ORIENTATION_QY));
            this.qz = arr(b.get(FlightDataType.TYPE_ORIENTATION_QZ));
            this.theta = arr(b.get(FlightDataType.TYPE_ORIENTATION_THETA));
            this.phi   = arr(b.get(FlightDataType.TYPE_ORIENTATION_PHI));
            this.vz  = arr(b.get(FlightDataType.TYPE_VELOCITY_Z));
            this.vxy = arr(b.get(FlightDataType.TYPE_VELOCITY_XY));
            this.drag = arr(b.get(FlightDataType.TYPE_DRAG_FORCE));

            for (FlightEvent e : b.getEvents()) {
                if (e.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT) {
                    if (Double.isNaN(deployTime) || e.getTime() < deployTime) {
                        deployTime = e.getTime();
                    }
                }
            }
        }

        boolean hasData() { return t.length > 0; }
        double startTime() { return t.length > 0 ? t[0] : 0; }
        double endTime()   { return t.length > 0 ? t[t.length - 1] : 0; }

        /** Index of the latest sample at or before the given absolute sim time. */
        int frameAt(double time) {
            if (t.length == 0) return -1;
            if (time <= t[0]) return 0;
            if (time >= t[t.length - 1]) return t.length - 1;
            int i = Arrays.binarySearch(t, time);
            if (i >= 0) return i;
            return Math.max(0, -i - 2);
        }

        boolean isActiveAt(double time) {
            return hasData() && time >= startTime() - 1e-6;
        }

        boolean isDeployedAt(double time) {
            return !Double.isNaN(deployTime) && time >= deployTime;
        }

        private static double[] arr(List<Double> list) {
            if (list == null) return new double[0];
            return list.stream().mapToDouble(d -> d == null || d.isNaN() ? 0.0 : d).toArray();
        }
    }

    /** A flight event at a point in time, for the sidebar event log. */
    private static final class EventMark {
        final double time;
        final FlightEvent.Type type;
        EventMark(double time, FlightEvent.Type type) {
            this.time = time;
            this.type = type;
        }
    }

    private void loadFlightData() {
        FlightData data = simulation.getSimulatedData();
        if (data == null || data.getBranchCount() == 0) {
            return;
        }

        for (int i = 0; i < data.getBranchCount(); i++) {
            FlightDataBranch b = data.getBranch(i);
            if (b != null) {
                tracks.add(new BranchTrack(b, i == 0));
            }
        }
        if (tracks.isEmpty()) {
            return;
        }

        BranchTrack t0 = tracks.get(0);
        times = t0.t;
        altitudes = t0.alt;
        thetas = t0.theta;
        phis = t0.phi;
        verticalSpeeds = t0.vz;
        horizSpeeds = t0.vxy;
        dragForces = t0.drag;
        frameCount = times.length;

        // Flight extent across all stages, for camera framing and terrain size.
        double maxHoriz = 0;
        for (BranchTrack tr : tracks) {
            for (int i = 0; i < tr.alt.length; i++) {
                maxAltitude = Math.max(maxAltitude, tr.alt[i]);
                maxHoriz = Math.max(maxHoriz, Math.hypot(tr.x[i], tr.z[i]));
            }
        }

        rocketLength = simulation.getRocket().getLength();
        if (rocketLength <= 0) rocketLength = 0.5;
        rocketRadius = Math.max(rocketLength * 0.015,
                Math.min(rocketLength * 0.25, simulation.getRocket().getBoundingRadius()));
        if (rocketRadius <= 0) rocketRadius = rocketLength * 0.05;

        // Camera distance range: from right on the rocket out to the whole flight.
        minCamDist = Math.max(0.3, rocketLength * 0.6);
        maxCamDist = maxAltitude * 12.0 + 1000.0;
        // Default framing keeps the rocket clearly visible (the previous default sat
        // so far back the rocket was an invisible speck); users zoom out for the
        // whole trajectory with the wheel.
        defaultCamDist = Math.max(minCamDist, Math.min(maxCamDist, rocketLength * 7.0));
        camDist = defaultCamDist;

        // Generate the local high-res desert ground sized to the flight.
        terrainHalfExtent = Math.max(800.0, Math.max(maxAltitude * 1.3, maxHoriz * 1.6));
        terrain = TerrainFetcher.generateDesert(terrainHalfExtent, 160, 1337L);

        prepareStageRendering();
        collectEvents();
    }

    /**
     * Builds the scratch render configuration and works out which stages belong to
     * each branch, plus when each stage separates from the sustainer, so the rocket
     * model can shed boosters and the boosters can be drawn as independent bodies.
     */
    private void prepareStageRendering() {
        try {
            renderConfig = configuration.clone();
            canToggleStages = (renderConfig != null && renderConfig != configuration);
        } catch (Exception e) {
            log.warn("Could not clone flight configuration for staged rendering", e);
            // Fall back to the live configuration but NEVER toggle its stages — that
            // would deactivate stages in the user's actual rocket design.
            renderConfig = configuration;
            canToggleStages = false;
        }

        Collection<AxialStage> stageList = configuration.getRocket().getStageList();
        allStageNumbers = stageList.stream().mapToInt(AxialStage::getStageNumber).toArray();

        // Separation times (and the order stages leave) come from the sustainer branch.
        List<FlightEvent> seps = new ArrayList<>();
        for (FlightEvent e : tracks.get(0).events) {
            if (e.getType() == FlightEvent.Type.STAGE_SEPARATION && e.getSource() != null) {
                separationTimeByStage.put(e.getSource().getStageNumber(), e.getTime());
                seps.add(e);
            }
        }
        seps.sort((a, b) -> Double.compare(a.getTime(), b.getTime()));

        // Assign each booster branch (in creation order) to the stage it carries.
        int si = 0;
        for (int k = 1; k < tracks.size(); k++) {
            BranchTrack tr = tracks.get(k);
            if (si < seps.size()) {
                tr.stageNumber = seps.get(si++).getSource().getStageNumber();
            } else {
                // Fallback: match the branch name to a stage name.
                for (AxialStage s : stageList) {
                    if (s.getName().equals(tr.name)) {
                        tr.stageNumber = s.getStageNumber();
                        break;
                    }
                }
            }
        }
    }

    /** Collects all flight events across branches for the sidebar event log. */
    private void collectEvents() {
        Set<String> seen = new HashSet<>();
        for (BranchTrack tr : tracks) {
            for (FlightEvent e : tr.events) {
                FlightEvent.Type type = e.getType();
                if (type == null) continue;
                // De-duplicate events copied across branches (same type at the same instant).
                String key = type.name() + "@" + Math.round(e.getTime() * 100);
                if (!seen.add(key)) continue;
                allEvents.add(new EventMark(e.getTime(), type));
                presentEventTypes.add(type);
            }
        }
        allEvents.sort((a, b) -> Double.compare(a.time, b.time));
        // Default selection: everything present except low-level/noise events.
        for (FlightEvent.Type type : presentEventTypes) {
            if (type != FlightEvent.Type.ALTITUDE && type != FlightEvent.Type.SIM_WARN) {
                enabledEventTypes.add(type);
            }
        }
    }

    // ---- GL canvas setup ---------------------------------------------------

    private void initGLCanvas() {
        try {
            final GLProfile glp = GLProfile.get(GLProfile.GL2);
            final GLCapabilities caps = new GLCapabilities(glp);
            caps.setBackgroundOpaque(true);

            if (Application.getPreferences().getBoolean(ApplicationPreferences.OPENGL_ENABLE_AA, true)) {
                caps.setSampleBuffers(true);
                caps.setNumSamples(4);
            }

            GLJPanel glPanel = new GLJPanel(caps);
            glPanel.setOpaque(true);
            canvas = glPanel;

            ((GLAutoDrawable) canvas).addGLEventListener(this);

            MouseAdapter orbit = buildOrbitAdapter();
            canvas.addMouseListener(orbit);
            canvas.addMouseMotionListener(orbit);
            canvas.addMouseWheelListener(buildZoomListener());

            add(canvas, BorderLayout.CENTER);
        } catch (Throwable t) {
            log.error("Failed to initialize 3D replay canvas", t);
            canvas = null;
            JLabel error = new JLabel("Unable to load 3D libraries: " + t.getMessage());
            error.setForeground(Color.RED);
            add(error, BorderLayout.CENTER);
        }
    }

    // ---- Controls ----------------------------------------------------------

    private void initControls() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(Color.BLACK);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        playPauseButton = new JButton("▶");
        styleButton(playPauseButton);
        playPauseButton.addActionListener(e -> togglePlayback());

        speedCombo = new JComboBox<>(SPEED_LABELS);
        speedCombo.setSelectedIndex(DEFAULT_SPEED_IDX);
        speedCombo.setMaximumSize(new Dimension(70, 24));
        speedCombo.addActionListener(e -> playbackSpeed = SPEEDS[speedCombo.getSelectedIndex()]);

        timeLabel = new JLabel("t = 0.00 s");
        timeLabel.setForeground(Color.WHITE);
        timeLabel.setPreferredSize(new Dimension(110, 20));

        timeSlider = new JSlider(0, Math.max(1, frameCount - 1), 0);
        timeSlider.setBackground(Color.BLACK);
        timeSlider.addChangeListener(e -> {
            if (!playing) {
                currentFrame = timeSlider.getValue();
                if (currentFrame < times.length) {
                    simulationTime = times[currentFrame];
                }
                refreshFrame();
            }
        });

        JLabel speedLabel = new JLabel("Speed:");
        speedLabel.setForeground(Color.WHITE);

        JButton resetViewButton = new JButton("Reset view");
        styleButton(resetViewButton);
        resetViewButton.addActionListener(e -> {
            cameraYaw = -Math.PI / 5.0;
            cameraPitch = 0.22;
            camDist = defaultCamDist;
            requestRepaint();
        });

        JLabel hint = new JLabel("drag: orbit   wheel: zoom");
        hint.setForeground(new Color(150, 150, 150));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 10f));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setBackground(Color.BLACK);
        left.add(playPauseButton);
        left.add(speedLabel);
        left.add(speedCombo);
        left.add(timeLabel);
        left.add(resetViewButton);
        left.add(hint);

        bar.add(left, BorderLayout.WEST);
        bar.add(timeSlider, BorderLayout.CENTER);

        add(bar, BorderLayout.SOUTH);

        playbackTimer = new Timer(16, e -> tickPlayback());
    }

    private static void styleButton(JButton btn) {
        btn.setForeground(Color.WHITE);
        btn.setBackground(Color.DARK_GRAY);
        btn.setFocusPainted(false);
    }

    // ---- Playback ----------------------------------------------------------

    private void togglePlayback() {
        if (frameCount == 0) return;
        playing = !playing;
        playPauseButton.setText(playing ? "⏸" : "▶");
        if (playing) {
            if (currentFrame >= frameCount - 1) {
                currentFrame = 0;
                simulationTime = 0.0;
            } else {
                simulationTime = times[currentFrame];
            }
            lastTickMs = System.currentTimeMillis();
            playbackTimer.start();
        } else {
            playbackTimer.stop();
        }
    }

    private void tickPlayback() {
        if (!playing || frameCount == 0) return;

        long now = System.currentTimeMillis();
        double elapsed = (now - lastTickMs) / 1000.0 * playbackSpeed;
        lastTickMs = now;
        simulationTime += elapsed;

        while (currentFrame < frameCount - 1 && times[currentFrame + 1] <= simulationTime) {
            currentFrame++;
        }

        if (simulationTime >= times[frameCount - 1]) {
            currentFrame = frameCount - 1;
            playing = false;
            playPauseButton.setText("▶");
            playbackTimer.stop();
            SwingUtilities.invokeLater(() -> {
                timeSlider.setValue(currentFrame);
                refreshFrame();
            });
            return;
        }

        SwingUtilities.invokeLater(() -> {
            timeSlider.setValue(currentFrame);
            refreshFrame();
        });
    }

    private void refreshFrame() {
        if (frameCount > 0 && currentFrame < times.length) {
            timeLabel.setText(String.format("t = %.2f s", times[currentFrame]));
        }
        updateStatsDisplay(currentFrame);
        if (miniRocketView != null) {
            miniRocketView.repaint();
        }
        requestRepaint();
    }

    private void requestRepaint() {
        if (canvas != null && !repaintPending) {
            repaintPending = true;
            canvas.repaint();
        }
    }

    private void updateStatsDisplay(int f) {
        if (statAltitude == null) return;
        double alt   = safeGet(altitudes, f);
        double vz    = safeGet(verticalSpeeds, f);
        double vxy   = safeGet(horizSpeeds, f);
        double drag  = safeGet(dragForces, f);
        double theta = safeGet(thetas, f);
        double phi   = safeGet(phis, f);

        statAltitude.setText(String.format("%.0f m", alt));
        statVVert.setText(String.format("%+.1f m/s", vz));
        statVHoriz.setText(String.format("%.1f m/s", vxy));
        statDrag.setText(String.format("%.1f N", drag));
        statTheta.setText(String.format("%.1f°", Math.toDegrees(theta)));
        statHdg.setText(String.format("HDG %.0f°", (Math.toDegrees(phi) % 360 + 360) % 360));
        updateEventLog();
        if (miniRocketView != null) {
            miniRocketView.setAngles(theta, phi);
        }
    }

    /**
     * Updates the sidebar event log with the selected events that have occurred up to
     * the current playback time; the most recent is highlighted.
     */
    private void updateEventLog() {
        if (eventLogLabel == null) return;
        double now = currentFrame < times.length ? times[currentFrame] : 0;

        List<EventMark> fired = new ArrayList<>();
        for (EventMark m : allEvents) {
            if (m.time <= now + 1e-6 && enabledEventTypes.contains(m.type)) {
                fired.add(m);
            }
        }

        StringBuilder sb = new StringBuilder("<html>");
        if (fired.isEmpty()) {
            sb.append("<span style='color:#808080'>(no events yet)</span>");
        } else {
            int from = Math.max(0, fired.size() - 7); // show the most recent few
            for (int i = from; i < fired.size(); i++) {
                EventMark m = fired.get(i);
                boolean latest = (i == fired.size() - 1);
                String color = latest ? "#FFD25A" : "#C8C8C8";
                String weight = latest ? "bold" : "normal";
                sb.append(String.format(
                        "<div style='color:%s;font-weight:%s'>%s&nbsp;&nbsp;%.2fs</div>",
                        color, weight, escapeHtml(eventLabel(m.type)), m.time));
            }
        }
        sb.append("</html>");
        eventLogLabel.setText(sb.toString());
    }

    /** Human-readable label for an event type. */
    private static String eventLabel(FlightEvent.Type type) {
        String s = type.toString();
        return (s == null || s.isEmpty()) ? type.name() : s;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Opens the popup that selects which event types appear in the sidebar (like the plot menu). */
    private void showEventFilterMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        if (presentEventTypes.isEmpty()) {
            JCheckBoxMenuItem none = new JCheckBoxMenuItem("(no events in this flight)");
            none.setEnabled(false);
            menu.add(none);
        }
        for (FlightEvent.Type type : presentEventTypes) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(eventLabel(type), enabledEventTypes.contains(type));
            item.addActionListener(e -> {
                if (item.isSelected()) {
                    enabledEventTypes.add(type);
                } else {
                    enabledEventTypes.remove(type);
                }
                updateEventLog();
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private static double safeGet(double[] arr, int idx) {
        return (arr != null && idx >= 0 && idx < arr.length) ? arr[idx] : 0.0;
    }

    // ---- GLEventListener ---------------------------------------------------

    @Override
    public void init(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_CULL_FACE);
        gl.glShadeModel(GL2.GL_SMOOTH);
        gl.glClearColor(SKY_HORIZON[0], SKY_HORIZON[1], SKY_HORIZON[2], 1.0f);

        gl.glFogi(GL2.GL_FOG_MODE, GL2.GL_LINEAR);
        gl.glFogfv(GL2.GL_FOG_COLOR, new float[]{ SKY_HORIZON[0], SKY_HORIZON[1], SKY_HORIZON[2], 1f }, 0);

        gl.glEnable(GL2.GL_LIGHTING);
        gl.glEnable(GL2.GL_LIGHT0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, new float[]{ 0.6f, 2.5f, 1f, 0f }, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE,  new float[]{ 0.9f, 0.87f, 0.78f, 1f }, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT,  new float[]{ 0.38f, 0.37f, 0.36f, 1f }, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_SPECULAR, new float[]{ 0.3f, 0.3f, 0.3f, 1f }, 0);

        try {
            renderer = new RealisticRenderer(document);
            renderer.init(drawable);
            renderer.updateFigure(drawable);
            rendererReady = true;
        } catch (Exception e) {
            log.warn("RealisticRenderer failed, using FigureRenderer", e);
            try {
                renderer = new FigureRenderer();
                renderer.init(drawable);
                renderer.updateFigure(drawable);
                rendererReady = true;
            } catch (Exception ex) {
                log.error("FigureRenderer also failed", ex);
            }
        }
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        repaintPending = false;
        if (frameCount == 0) return;

        GL2 gl = drawable.getGL().getGL2();
        GLU glu = new GLU();

        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        if (h == 0) h = 1;

        gl.glViewport(0, 0, w, h);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        drawSky(gl);

        double simTime = currentFrame < times.length ? times[currentFrame] : 0;

        // Camera follows the sustainer (branch 0) so the main rocket is always framed.
        // (Averaging in a separated booster would aim the camera at the empty space
        // between the two stages and lose both.)
        BranchTrack main = tracks.get(0);
        int mf = main.frameAt(simTime);
        double tx = main.x[mf];
        double ty = main.alt[mf];
        double tz = main.z[mf];

        // Projection — near/far tracked to the scene size for good depth precision
        // (a fixed 0.02–400000 range causes severe z-fighting when zoomed in).
        double near = Math.max(0.05, camDist * 0.02);
        double far = Math.max(terrainHalfExtent * 4.0, ty + camDist + 2000.0);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        glu.gluPerspective(55.0, (double) w / h, near, far);

        // Modelview / orbit camera
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
        double cy = Math.cos(cameraYaw), sy = Math.sin(cameraYaw);
        double cp = Math.cos(cameraPitch), sp = Math.sin(cameraPitch);
        double eyeX = tx + camDist * sy * cp;
        double eyeY = ty + camDist * sp;
        double eyeZ = tz + camDist * cy * cp;
        glu.gluLookAt(eyeX, eyeY, eyeZ, tx, ty, tz, 0, 1, 0);

        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, new float[]{ 0.6f, 2.5f, 1f, 0f }, 0);

        // Distance fog blends the desert edge into the horizon haze.
        gl.glFogf(GL2.GL_FOG_START, (float) (terrainHalfExtent * 0.45));
        gl.glFogf(GL2.GL_FOG_END, (float) (terrainHalfExtent * 1.05));
        gl.glEnable(GL2.GL_FOG);

        drawGround(gl);

        // Flight-path traces for every stage
        for (int i = 0; i < tracks.size(); i++) {
            drawFlightPath(gl, tracks.get(i), simTime, i);
        }

        // Rocket(s) stay crisp
        gl.glDisable(GL2.GL_FOG);

        for (BranchTrack tr : tracks) {
            if (!tr.isActiveAt(simTime)) continue;
            renderStage(drawable, gl, tr, simTime);
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        // Projection rebuilt each frame in display()
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        if (renderer != null) {
            renderer.dispose(drawable);
        }
    }

    // ---- Stage / rocket rendering -----------------------------------------

    private void renderStage(GLAutoDrawable drawable, GL2 gl, BranchTrack tr, double simTime) {
        int f = tr.frameAt(simTime);
        double rX = tr.x[f];
        double rAlt = tr.alt[f];
        double rZ = tr.z[f];

        // Which stages this branch is carrying right now (the sustainer sheds boosters
        // as they separate; each booster branch carries only its own stage).
        Set<Integer> activeStages = activeStagesFor(tr, simTime);
        if (activeStages.isEmpty()) {
            return;
        }

        gl.glPushMatrix();
        gl.glTranslated(rX, rAlt, rZ);
        applyOrientation(gl, tr, f);

        gl.glScaled(1.0, 1.0, -1.0);
        gl.glFrontFace(GL.GL_CW);
        gl.glTranslated(-rocketLength / 2.0, 0, 0);

        boolean drawn = false;
        if (rendererReady && renderConfig != null && canToggleStages) {
            try {
                applyActiveStages(activeStages);
                renderer.render(drawable, renderConfig, Collections.emptySet());
                drawn = true;
            } catch (Exception e) {
                log.warn("Rocket render failed", e);
            }
        } else if (rendererReady && renderConfig != null && tr.sustainer) {
            // Cannot safely toggle stages — draw the full rocket for the sustainer only.
            try {
                renderer.render(drawable, renderConfig, Collections.emptySet());
                drawn = true;
            } catch (Exception e) {
                log.warn("Rocket render failed", e);
            }
        }
        if (!drawn) {
            drawSimpleRocket(gl);
        }

        gl.glFrontFace(GL.GL_CCW);
        gl.glPopMatrix();

        // Parachute canopy once the recovery device has deployed.
        if (tr.isDeployedAt(simTime)) {
            drawParachute(gl, rX, rAlt, rZ);
        }
    }

    /** Returns the set of stage numbers this branch should render at the given time. */
    private Set<Integer> activeStagesFor(BranchTrack tr, double simTime) {
        Set<Integer> set = new HashSet<>();
        if (tr.sustainer) {
            // All stages that have not yet separated from the sustainer.
            for (int num : allStageNumbers) {
                Double sep = separationTimeByStage.get(num);
                if (sep == null || simTime < sep) {
                    set.add(num);
                }
            }
        } else if (tr.stageNumber >= 0) {
            set.add(tr.stageNumber);
        }
        return set;
    }

    /** Toggles the scratch render configuration so only the given stages are active. */
    private void applyActiveStages(Set<Integer> active) {
        for (int num : allStageNumbers) {
            renderConfig._setStageActive(num, active.contains(num), false);
        }
    }

    /** Applies the stage's orientation (quaternion if available, else theta/phi). */
    private void applyOrientation(GL2 gl, BranchTrack tr, int f) {
        double qw = safeGet(tr.qw, f);
        double qx = safeGet(tr.qx, f);
        double qy = safeGet(tr.qy, f);
        double qz = safeGet(tr.qz, f);
        double qLen = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);

        if (tr.qw.length > f && qLen > 1e-6) {
            qw /= qLen; qx /= qLen; qy /= qLen; qz /= qLen;
            double cx = 2.0 * (qx * qz + qy * qw);
            double cyy = 2.0 * (qy * qz - qx * qw);
            double cz = 1.0 - 2.0 * (qx * qx + qy * qy);
            double ngx = cx, ngy = cz, ngz = -cyy;
            double cosA = Math.max(-1.0, Math.min(1.0, -ngx));
            double angleDeg = Math.toDegrees(Math.acos(cosA));
            double ay = ngz, az = -ngy;
            double axisLen = Math.sqrt(ay * ay + az * az);
            if (axisLen > 1e-10) {
                gl.glRotated(angleDeg, 0.0, ay / axisLen, az / axisLen);
            } else if (cosA < 0) {
                gl.glRotated(180.0, 0, 1, 0);
            }
        } else {
            double theta = f < tr.theta.length ? tr.theta[f] : Math.PI / 2;
            double phi = f < tr.phi.length ? tr.phi[f] : 0.0;
            gl.glRotated(-90.0 - Math.toDegrees(phi), 0, 1, 0);
            gl.glRotated(-Math.toDegrees(theta), 0, 0, 1);
        }
    }

    /** Draws a simple cylinder + nose body (nose along +X) for a separated stage. */
    private void drawSimpleRocket(GL2 gl) {
        GLU glu = new GLU();
        com.jogamp.opengl.glu.GLUquadric q = glu.gluNewQuadric();
        double r = rocketRadius;
        double len = rocketLength * 0.5;

        gl.glEnable(GL2.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
        gl.glColor3f(0.80f, 0.30f, 0.12f);

        gl.glPushMatrix();
        // GLU draws cylinders along +Z; rotate so the body lies along +X (rocket axis).
        gl.glRotated(90, 0, 1, 0);
        glu.gluCylinder(q, r, r, len, 16, 1);
        gl.glTranslated(0, 0, len);
        glu.gluCylinder(q, r, 0.0, r * 3.0, 16, 1); // nose
        gl.glPopMatrix();

        gl.glDisable(GL2.GL_COLOR_MATERIAL);
        glu.gluDeleteQuadric(q);
    }

    /** Draws a classic gored parachute canopy above a deployed stage with shroud lines. */
    private void drawParachute(GL2 gl, double rX, double rAlt, double rZ) {
        double canopyR = Math.max(rocketLength * 1.2, rocketRadius * 12.0);
        double hang = canopyR * 1.3;                 // canopy height above the rocket
        double cx = rX, cy = rAlt + hang, cz = rZ;

        gl.glEnable(GL2.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
        gl.glDisable(GL.GL_CULL_FACE);

        final int slices = 16;
        final int stacks = 6;
        // Hemisphere dome opening downward, alternating coloured gores.
        for (int s = 0; s < slices; s++) {
            double a0 = 2 * Math.PI * s / slices;
            double a1 = 2 * Math.PI * (s + 1) / slices;
            boolean red = (s % 2) == 0;
            if (red) gl.glColor3f(0.85f, 0.18f, 0.18f);
            else     gl.glColor3f(0.95f, 0.95f, 0.95f);

            gl.glBegin(GL2.GL_QUAD_STRIP);
            for (int k = 0; k <= stacks; k++) {
                double ph = (Math.PI / 2.0) * k / stacks; // 0 at top → PI/2 at rim
                double y = Math.cos(ph) * canopyR;
                double rr = Math.sin(ph) * canopyR;
                double nx0 = Math.sin(ph) * Math.cos(a0);
                double nz0 = Math.sin(ph) * Math.sin(a0);
                gl.glNormal3d(nx0, Math.cos(ph), nz0);
                gl.glVertex3d(cx + rr * Math.cos(a0), cy - canopyR + y, cz + rr * Math.sin(a0));
                double nx1 = Math.sin(ph) * Math.cos(a1);
                double nz1 = Math.sin(ph) * Math.sin(a1);
                gl.glNormal3d(nx1, Math.cos(ph), nz1);
                gl.glVertex3d(cx + rr * Math.cos(a1), cy - canopyR + y, cz + rr * Math.sin(a1));
            }
            gl.glEnd();
        }

        // Shroud lines from the canopy rim down to the rocket.
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glColor3f(0.9f, 0.9f, 0.9f);
        gl.glLineWidth(1.2f);
        gl.glBegin(GL2.GL_LINES);
        for (int s = 0; s < slices; s += 2) {
            double a = 2 * Math.PI * s / slices;
            gl.glVertex3d(cx + canopyR * Math.cos(a), cy - canopyR, cz + canopyR * Math.sin(a));
            gl.glVertex3d(rX, rAlt + rocketLength * 0.4, rZ);
        }
        gl.glEnd();
        gl.glLineWidth(1.0f);
        gl.glEnable(GL2.GL_LIGHTING);

        gl.glEnable(GL.GL_CULL_FACE);
        gl.glDisable(GL2.GL_COLOR_MATERIAL);
    }

    // ---- Scene helpers -----------------------------------------------------

    private void drawSky(GL2 gl) {
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrtho(0, 1, 0, 1, -1, 1);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        gl.glDisable(GL2.GL_LIGHTING);
        gl.glDisable(GL.GL_DEPTH_TEST);

        gl.glBegin(GL2.GL_QUADS);
        gl.glColor3f(SKY_HORIZON[0], SKY_HORIZON[1], SKY_HORIZON[2]);
        gl.glVertex2f(0f, 0f);
        gl.glVertex2f(1f, 0f);
        gl.glColor3f(SKY_ZENITH[0], SKY_ZENITH[1], SKY_ZENITH[2]);
        gl.glVertex2f(1f, 1f);
        gl.glVertex2f(0f, 1f);
        gl.glEnd();

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL2.GL_LIGHTING);

        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPopMatrix();
    }

    private void drawGround(GL2 gl) {
        gl.glDisable(GL.GL_CULL_FACE);

        // A large flat sand plane underneath, so the ground reaches the fogged horizon.
        double baseline = (terrain != null) ? (terrain.minElevation - terrain.centerElevation) - 0.5 : 0.0;
        double half = terrainHalfExtent * 3.0;
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glColor3f(0.80f, 0.67f, 0.44f);
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex3d(-half, baseline, -half);
        gl.glVertex3d( half, baseline, -half);
        gl.glVertex3d( half, baseline,  half);
        gl.glVertex3d(-half, baseline,  half);
        gl.glEnd();
        gl.glEnable(GL2.GL_LIGHTING);

        // The detailed dune mesh on top.
        if (terrain != null) {
            TerrainRenderer.render(gl, terrain, true);
        }

        // Launch-pad marker
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glColor3f(0.15f, 0.15f, 0.15f);
        gl.glLineWidth(2.0f);
        gl.glBegin(GL2.GL_LINE_LOOP);
        double pad = Math.max(2.0, rocketLength);
        for (int i = 0; i < 24; i++) {
            double a = 2 * Math.PI * i / 24;
            gl.glVertex3d(pad * Math.cos(a), 0.1, pad * Math.sin(a));
        }
        gl.glEnd();
        gl.glLineWidth(1.0f);
        gl.glEnable(GL2.GL_LIGHTING);

        gl.glEnable(GL.GL_CULL_FACE);
    }

    private void drawFlightPath(GL2 gl, BranchTrack tr, double simTime, int idx) {
        if (tr.x.length == 0) return;
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glLineWidth(idx == 0 ? 2.5f : 1.8f);
        if (idx == 0) gl.glColor3f(0.95f, 0.55f, 0.10f);
        else          gl.glColor3f(0.95f, 0.25f, 0.20f);
        gl.glBegin(GL2.GL_LINE_STRIP);
        int end = tr.frameAt(simTime);
        for (int i = 0; i <= end && i < tr.x.length; i++) {
            gl.glVertex3d(tr.x[i], tr.alt[i], tr.z[i]);
        }
        gl.glEnd();
        gl.glLineWidth(1.0f);
        gl.glEnable(GL2.GL_LIGHTING);
    }

    // ---- Camera mouse interaction -----------------------------------------

    private MouseAdapter buildOrbitAdapter() {
        return new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastMouseX = e.getX();
                lastMouseY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                cameraYaw += dx * 0.008;
                cameraPitch = Math.max(-Math.PI / 2.1, Math.min(Math.PI / 2.1, cameraPitch - dy * 0.008));
                if (canvas instanceof GLAutoDrawable) {
                    ((GLAutoDrawable) canvas).display();
                }
            }
        };
    }

    private MouseWheelListener buildZoomListener() {
        return (MouseWheelEvent e) -> {
            camDist *= Math.pow(1.12, e.getWheelRotation());
            camDist = Math.max(minCamDist, Math.min(maxCamDist, camDist));
            if (canvas instanceof GLAutoDrawable) {
                ((GLAutoDrawable) canvas).display();
            }
        };
    }

    // ---- Stats side panel --------------------------------------------------

    private JPanel buildStatsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new java.awt.GridBagLayout());
        panel.setBackground(Color.BLACK);
        panel.setPreferredSize(new Dimension(185, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.insets = new java.awt.Insets(0, 0, 0, 0);
        gbc.weightx = 1.0;

        miniRocketView = new MiniRocketView();
        gbc.gridy = 0;
        panel.add(miniRocketView, gbc);

        statHdg = makeValueLabel("HDG 0°");
        gbc.gridy = 1;
        gbc.insets = new java.awt.Insets(0, 0, 6, 0);
        panel.add(statHdg, gbc);
        gbc.insets = new java.awt.Insets(0, 0, 0, 0);

        gbc.gridy = 2;
        panel.add(makeDivider(), gbc);

        gbc.gridy = 3;
        panel.add(makeTitleLabel("Altitude"), gbc);
        statAltitude = makeValueLabel("— m");
        gbc.gridy = 4;
        panel.add(statAltitude, gbc);

        gbc.gridy = 5;
        panel.add(makeTitleLabel("Vertical spd"), gbc);
        statVVert = makeValueLabel("— m/s");
        gbc.gridy = 6;
        panel.add(statVVert, gbc);

        gbc.gridy = 7;
        panel.add(makeTitleLabel("Horiz speed"), gbc);
        statVHoriz = makeValueLabel("— m/s");
        gbc.gridy = 8;
        panel.add(statVHoriz, gbc);

        gbc.gridy = 9;
        panel.add(makeTitleLabel("Drag"), gbc);
        statDrag = makeValueLabel("— N");
        gbc.gridy = 10;
        panel.add(statDrag, gbc);

        gbc.gridy = 11;
        panel.add(makeTitleLabel("Elevation"), gbc);
        statTheta = makeValueLabel("—°");
        gbc.gridy = 12;
        panel.add(statTheta, gbc);

        gbc.gridy = 13;
        gbc.insets = new java.awt.Insets(8, 0, 0, 0);
        panel.add(makeDivider(), gbc);
        gbc.insets = new java.awt.Insets(0, 0, 0, 0);

        // Events header with a selector button (choose which events appear, like the plot menu).
        JPanel eventsHeader = new JPanel(new BorderLayout());
        eventsHeader.setOpaque(false);
        eventsHeader.add(makeTitleLabel("Events"), BorderLayout.WEST);
        JButton eventFilterBtn = new JButton("⚙");
        eventFilterBtn.setMargin(new java.awt.Insets(0, 4, 0, 4));
        eventFilterBtn.setForeground(Color.WHITE);
        eventFilterBtn.setBackground(Color.DARK_GRAY);
        eventFilterBtn.setFocusPainted(false);
        eventFilterBtn.setToolTipText("Choose which events to show");
        eventFilterBtn.addActionListener(e -> showEventFilterMenu(eventFilterBtn));
        eventsHeader.add(eventFilterBtn, BorderLayout.EAST);
        gbc.gridy = 14;
        panel.add(eventsHeader, gbc);

        eventLogLabel = new JLabel(" ");
        eventLogLabel.setVerticalAlignment(JLabel.TOP);
        eventLogLabel.setFont(eventLogLabel.getFont().deriveFont(Font.PLAIN, 11f));
        eventLogLabel.setForeground(new Color(200, 200, 200));
        eventLogLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        gbc.gridy = 15;
        gbc.weighty = 1.0;
        gbc.fill = java.awt.GridBagConstraints.BOTH;
        gbc.anchor = java.awt.GridBagConstraints.NORTH;
        panel.add(eventLogLabel, gbc);

        return panel;
    }

    private static JLabel makeTitleLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(160, 160, 160));
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 10f));
        l.setBorder(BorderFactory.createEmptyBorder(6, 2, 1, 2));
        return l;
    }

    private static JLabel makeValueLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.WHITE);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        return l;
    }

    private static JPanel makeDivider() {
        JPanel d = new JPanel();
        d.setBackground(new Color(50, 50, 50));
        d.setPreferredSize(new Dimension(0, 1));
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return d;
    }

    /**
     * A small Swing panel that draws a 2D rocket silhouette rotated to the
     * current elevation angle (theta).
     */
    private static final class MiniRocketView extends JPanel {

        private double theta = Math.PI / 2.0;
        private double phi = 0.0;

        MiniRocketView() {
            setBackground(new Color(15, 15, 25));
            setPreferredSize(new Dimension(138, 120));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        }

        void setAngles(double thetaRad, double phiRad) {
            this.theta = thetaRad;
            this.phi = phiRad;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int cx = w / 2;
                int cy = h / 2;

                g2.setColor(new Color(40, 40, 60));
                int r = Math.min(cx, cy) - 4;
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);

                double rotAngle = Math.PI / 2.0 - theta;
                g2.rotate(rotAngle, cx, cy);

                int bodyW = 8;
                int bodyH = 38;
                int noseH = 18;
                int finW = 12;
                int finH = 14;

                g2.setColor(new Color(220, 220, 230));
                g2.fillRect(cx - bodyW / 2, cy - bodyH / 2, bodyW, bodyH);

                int[] noseXs = { cx - bodyW / 2, cx + bodyW / 2, cx };
                int[] noseYs = { cy - bodyH / 2, cy - bodyH / 2, cy - bodyH / 2 - noseH };
                g2.fillPolygon(noseXs, noseYs, 3);

                int[] lfx = { cx - bodyW / 2, cx - bodyW / 2 - finW, cx - bodyW / 2 };
                int[] lfy = { cy + bodyH / 2 - finH, cy + bodyH / 2, cy + bodyH / 2 };
                g2.setColor(new Color(180, 180, 200));
                g2.fillPolygon(lfx, lfy, 3);

                int[] rfx = { cx + bodyW / 2, cx + bodyW / 2 + finW, cx + bodyW / 2 };
                int[] rfy = { cy + bodyH / 2 - finH, cy + bodyH / 2, cy + bodyH / 2 };
                g2.fillPolygon(rfx, rfy, 3);

                g2.setColor(new Color(100, 120, 160));
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRect(cx - bodyW / 2, cy - bodyH / 2, bodyW, bodyH);
                g2.drawPolygon(noseXs, noseYs, 3);
                g2.drawPolygon(lfx, lfy, 3);
                g2.drawPolygon(rfx, rfy, 3);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Called by SimulationReplayDialog when the dialog is closed. */
    public void cleanup() {
        if (playbackTimer != null) {
            playbackTimer.stop();
        }
    }
}
