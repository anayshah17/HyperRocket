package info.openrocket.swing.gui.simulation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.Range;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.unit.Unit;

/**
 * Mission-Control telemetry side panel for the 3D flight replay.
 * <p>
 * It reuses OpenRocket's JFreeChart plotting stack and pulls every value straight
 * from the simulation's {@link FlightDataBranch} (no values are recomputed) to draw
 * a stack of compact, time-synchronised mini plots — Altitude, Velocity and
 * Acceleration by default.  The plots share a single time (domain) axis, so zooming
 * and panning the timeline affects all of them at once.
 * <p>
 * The panel stays locked to the replay clock: {@link #setTime(double)} moves a cursor
 * line across every plot and refreshes the live numeric read-outs.  The interaction is
 * bidirectional — dragging on a plot (or clicking it) scrubs the replay through the
 * {@link ReplayController} callback.  Every {@link FlightEvent} (including the
 * HyperRocket failure events) is drawn as a labelled vertical marker with a hover
 * tooltip.
 * <p>
 * New telemetry channels can be added by passing more {@link FlightDataType}s to the
 * constructor — no other change is required.
 */
public class MissionControlTelemetryPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(MissionControlTelemetryPanel.class);

    /** Telemetry channels shown by default, in stacking order. */
    public static final List<FlightDataType> DEFAULT_CHANNELS = Arrays.asList(
            FlightDataType.TYPE_ALTITUDE,
            FlightDataType.TYPE_VELOCITY_TOTAL,
            FlightDataType.TYPE_ACCELERATION_TOTAL);

    private static final Color BG = new Color(12, 12, 18);
    private static final Color PLOT_BG = new Color(20, 20, 28);
    private static final Color GRID = new Color(55, 55, 70);
    private static final Color CURSOR = new Color(255, 80, 80);
    private static final Color TEXT = new Color(225, 225, 235);
    private static final Color SUBTLE = new Color(150, 150, 165);

    /** A line-series colour per channel, cycled in order. */
    private static final Color[] CHANNEL_COLORS = {
            new Color(96, 200, 255),   // altitude  – cyan
            new Color(120, 235, 140),  // velocity  – green
            new Color(255, 196, 96),   // accel     – amber
            new Color(206, 150, 255),  // extra     – violet
            new Color(255, 130, 180),  // extra     – pink
    };

    /** Callback used to drive the 3D replay from telemetry interaction. */
    public interface ReplayController {
        /** Seek the replay (and everything synced to it) to the given absolute sim time (s). */
        void seekToTime(double time);
    }

    /** One telemetry channel: a data type, its plot, cursor marker and read-out. */
    private static final class Channel {
        final FlightDataType type;
        final Unit unit;
        final double[] valuesSI;   // raw SI values from the branch (not recomputed)
        XYPlot subplot;
        ValueMarker cursor;
        JLabel readout;
        String lastReadoutValue;   // skip rebuilding the HTML label when unchanged

        Channel(FlightDataType type, double[] valuesSI) {
            this.type = type;
            this.unit = type.getUnitGroup().getDefaultUnit();
            this.valuesSI = valuesSI;
        }
    }

    /** A flight event positioned on the timeline, with a precomputed tooltip. */
    private static final class EventInfo {
        final double time;
        final FlightEvent.Type type;
        final String source;
        final Color color;

        EventInfo(double time, FlightEvent.Type type, String source, Color color) {
            this.time = time;
            this.type = type;
            this.source = source;
            this.color = color;
        }
    }

    private final Simulation simulation;
    private ReplayController controller;

    private double[] times = new double[0];
    private double tMin = 0, tMax = 1;
    private final List<Channel> channels = new ArrayList<>();
    private final List<EventInfo> events = new ArrayList<>();

    private CombinedDomainXYPlot combinedPlot;
    private TelemetryChartPanel chartPanel;
    private JLabel timeReadout;
    private double currentTime = 0;

    public MissionControlTelemetryPanel(Simulation simulation) {
        this(simulation, DEFAULT_CHANNELS);
    }

    public MissionControlTelemetryPanel(Simulation simulation, List<FlightDataType> channelTypes) {
        super(new java.awt.BorderLayout());
        this.simulation = simulation;
        setBackground(BG);
        setPreferredSize(new Dimension(360, 0));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        loadData(channelTypes);
        add(buildHeader(), java.awt.BorderLayout.NORTH);
        if (!channels.isEmpty() && times.length > 0) {
            add(buildChart(), java.awt.BorderLayout.CENTER);
        } else {
            JLabel empty = new JLabel("No telemetry data available", JLabel.CENTER);
            empty.setForeground(SUBTLE);
            add(empty, java.awt.BorderLayout.CENTER);
        }
        setTime(tMin);
    }

    public void setReplayController(ReplayController controller) {
        this.controller = controller;
    }

    // ---- Data loading (straight from FlightDataBranch) ---------------------

    private void loadData(List<FlightDataType> channelTypes) {
        FlightData data = simulation.getSimulatedData();
        if (data == null || data.getBranchCount() == 0) {
            return;
        }
        FlightDataBranch main = data.getBranch(0);
        if (main == null) {
            return;
        }

        times = toArray(main.get(FlightDataType.TYPE_TIME));
        if (times.length > 0) {
            tMin = times[0];
            tMax = times[times.length - 1];
            if (tMax <= tMin) {
                tMax = tMin + 1;
            }
        }

        for (FlightDataType type : channelTypes) {
            double[] vals = toArray(main.get(type));
            if (vals.length == 0) {
                log.debug("Telemetry channel {} has no data; skipping", type.getName());
                continue;
            }
            channels.add(new Channel(type, vals));
        }

        collectEvents(data);
    }

    /** Gathers every event across all branches, de-duplicated, sorted by time. */
    private void collectEvents(FlightData data) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < data.getBranchCount(); i++) {
            FlightDataBranch b = data.getBranch(i);
            if (b == null) {
                continue;
            }
            for (FlightEvent e : b.getEvents()) {
                FlightEvent.Type type = e.getType();
                if (type == null || type == FlightEvent.Type.ALTITUDE) {
                    continue;
                }
                String key = type.name() + "@" + Math.round(e.getTime() * 100);
                if (!seen.add(key)) {
                    continue;
                }
                String src = (e.getSource() != null) ? e.getSource().getName() : null;
                events.add(new EventInfo(e.getTime(), type, src, eventColor(type)));
            }
        }
        events.sort((a, b) -> Double.compare(a.time, b.time));
    }

    // ---- UI ----------------------------------------------------------------

    private JPanel buildHeader() {
        JPanel header = new JPanel(new java.awt.BorderLayout());
        header.setOpaque(false);

        JLabel title = new JLabel("MISSION CONTROL");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 2));
        header.add(title, java.awt.BorderLayout.NORTH);

        // One read-out row per channel, plus the replay time.
        JPanel readouts = new JPanel(new GridLayout(0, 1, 0, 2));
        readouts.setOpaque(false);
        for (Channel ch : channels) {
            ch.readout = makeReadout(ch.type.getName() + ":", "—");
            readouts.add(ch.readout);
        }
        timeReadout = makeReadout("Replay Time:", "0.00 s");
        readouts.add(timeReadout);
        header.add(readouts, java.awt.BorderLayout.CENTER);

        JLabel hint = new JLabel("drag: scrub   wheel: zoom   shift+drag: pan");
        hint.setForeground(SUBTLE);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 9.5f));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
        header.add(hint, java.awt.BorderLayout.SOUTH);

        return header;
    }

    private static JLabel makeReadout(String label, String value) {
        JLabel l = new JLabel("<html><span style='color:#9696a5'>" + escape(label)
                + "</span> <b style='color:#e1e1eb'>" + escape(value) + "</b></html>");
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
        return l;
    }

    private ChartPanel buildChart() {
        NumberAxis domainAxis = new NumberAxis("Time (s)");
        styleAxis(domainAxis);
        domainAxis.setRange(tMin, tMax);
        domainAxis.setAutoRangeIncludesZero(false);

        combinedPlot = new CombinedDomainXYPlot(domainAxis);
        combinedPlot.setGap(10.0);
        combinedPlot.setOrientation(PlotOrientation.VERTICAL);
        combinedPlot.setBackgroundPaint(PLOT_BG);

        int colorIdx = 0;
        for (Channel ch : channels) {
            XYSeries series = new XYSeries(ch.type.getName(), false, true);
            int n = Math.min(times.length, ch.valuesSI.length);
            for (int i = 0; i < n; i++) {
                series.add(times[i], ch.unit.toUnit(ch.valuesSI[i]));
            }
            XYSeriesCollection dataset = new XYSeriesCollection(series);

            NumberAxis rangeAxis = new NumberAxis(ch.unit.getUnit().isEmpty()
                    ? ch.type.getName() : ch.unit.getUnit());
            styleAxis(rangeAxis);
            rangeAxis.setAutoRangeIncludesZero(false);

            XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
            Color c = CHANNEL_COLORS[colorIdx++ % CHANNEL_COLORS.length];
            renderer.setSeriesPaint(0, c);
            renderer.setSeriesStroke(0, new BasicStroke(1.8f));

            XYPlot subplot = new XYPlot(dataset, null, rangeAxis, renderer);
            subplot.setBackgroundPaint(PLOT_BG);
            subplot.setDomainGridlinePaint(GRID);
            subplot.setRangeGridlinePaint(GRID);

            // Time cursor (one marker per subplot, all updated together in setTime).
            ch.cursor = new ValueMarker(currentTime);
            ch.cursor.setPaint(CURSOR);
            ch.cursor.setStroke(new BasicStroke(1.4f));
            subplot.addDomainMarker(ch.cursor);

            // Event markers, drawn on every subplot so they line up across the stack.
            for (EventInfo ev : events) {
                subplot.addDomainMarker(makeEventMarker(ev));
            }

            combinedPlot.add(subplot, 1);
            ch.subplot = subplot;
        }

        JFreeChart chart = new JFreeChart(null, JFreeChart.DEFAULT_TITLE_FONT, combinedPlot, false);
        chart.setBackgroundPaint(BG);

        chartPanel = new TelemetryChartPanel(chart);
        chartPanel.setBackground(BG);
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setDomainZoomable(true);
        chartPanel.setRangeZoomable(false);    // timeline zoom only
        chartPanel.setDisplayToolTips(true);
        chartPanel.setBorder(BorderFactory.createEmptyBorder());
        return chartPanel;
    }

    private ValueMarker makeEventMarker(EventInfo ev) {
        ValueMarker m = new ValueMarker(ev.time);
        m.setPaint(ev.color);
        m.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1.0f, new float[]{ 4f, 3f }, 0f));
        return m;
    }

    private static void styleAxis(ValueAxis axis) {
        axis.setLabelPaint(SUBTLE);
        axis.setTickLabelPaint(SUBTLE);
        axis.setAxisLinePaint(GRID);
        axis.setTickMarkPaint(GRID);
        axis.setLabelFont(axis.getLabelFont().deriveFont(10f));
        axis.setTickLabelFont(axis.getTickLabelFont().deriveFont(9f));
    }

    // ---- Synchronisation ---------------------------------------------------

    /**
     * Locks the telemetry display to the given replay time: moves the cursor on every
     * plot and refreshes the numeric read-outs.  Cheap enough to call every frame.
     */
    public void setTime(double time) {
        currentTime = Math.max(tMin, Math.min(tMax, time));
        for (Channel ch : channels) {
            if (ch.cursor != null) {
                ch.cursor.setValue(currentTime);
            }
            if (ch.readout != null) {
                double si = valueAtSI(ch.valuesSI, currentTime);
                String val = Double.isNaN(si) ? "—" : ch.unit.toStringUnit(si);
                if (!val.equals(ch.lastReadoutValue)) {
                    ch.lastReadoutValue = val;
                    setReadout(ch.readout, ch.type.getName() + ":", val);
                }
            }
        }
        if (timeReadout != null) {
            setReadout(timeReadout, "Replay Time:", String.format("%.2f s", currentTime));
        }
    }

    private static void setReadout(JLabel label, String name, String value) {
        label.setText("<html><span style='color:#9696a5'>" + escape(name)
                + "</span> <b style='color:#e1e1eb'>" + escape(value) + "</b></html>");
    }

    /** Linear interpolation of an SI channel at an absolute time. */
    private double valueAtSI(double[] vals, double t) {
        if (vals.length == 0 || times.length == 0) {
            return Double.NaN;
        }
        if (t <= times[0]) {
            return vals[0];
        }
        if (t >= times[times.length - 1]) {
            return vals[Math.min(vals.length - 1, times.length - 1)];
        }
        int i = Arrays.binarySearch(times, t);
        if (i >= 0) {
            return vals[Math.min(i, vals.length - 1)];
        }
        int hi = -i - 1;
        int lo = hi - 1;
        if (hi >= vals.length) {
            return vals[vals.length - 1];
        }
        double t0 = times[lo], t1 = times[hi];
        double f = (t1 > t0) ? (t - t0) / (t1 - t0) : 0;
        return vals[lo] + f * (vals[hi] - vals[lo]);
    }

    // ---- Interaction (telemetry -> replay) ---------------------------------

    /** Maps a screen X within the plot data area to an absolute sim time, then seeks. */
    private void scrubToScreenX(int screenX) {
        if (controller == null || combinedPlot == null || chartPanel == null) {
            return;
        }
        Rectangle2D dataArea = chartPanel.getScreenDataArea();
        if (dataArea == null || dataArea.getWidth() <= 0) {
            return;
        }
        double t = combinedPlot.getDomainAxis().java2DToValue(screenX, dataArea, RectangleEdge.BOTTOM);
        t = Math.max(tMin, Math.min(tMax, t));
        controller.seekToTime(t);
    }

    /** Pans the shared time axis by a pixel delta (shift-drag / right-drag). */
    private void panByPixels(int dxPixels) {
        if (combinedPlot == null || chartPanel == null) {
            return;
        }
        Rectangle2D dataArea = chartPanel.getScreenDataArea();
        if (dataArea == null || dataArea.getWidth() <= 0) {
            return;
        }
        ValueAxis axis = combinedPlot.getDomainAxis();
        double v0 = axis.java2DToValue(0, dataArea, RectangleEdge.BOTTOM);
        double v1 = axis.java2DToValue(dxPixels, dataArea, RectangleEdge.BOTTOM);
        double delta = v0 - v1;
        Range r = axis.getRange();
        double lower = r.getLowerBound() + delta;
        double upper = r.getUpperBound() + delta;
        // Keep the view within the flight, preserving the zoom span.
        double span = upper - lower;
        if (lower < tMin) {
            lower = tMin;
            upper = lower + span;
        }
        if (upper > tMax) {
            upper = tMax;
            lower = upper - span;
        }
        axis.setRange(Math.max(tMin, lower), Math.min(tMax, upper));
    }

    private void resetZoom() {
        if (combinedPlot != null) {
            combinedPlot.getDomainAxis().setRange(tMin, tMax);
        }
    }

    /** Builds the hover tooltip for an event marker near the given screen X. */
    private String eventTooltipAt(int screenX, int screenY) {
        if (combinedPlot == null || chartPanel == null || events.isEmpty()) {
            return null;
        }
        Rectangle2D dataArea = chartPanel.getScreenDataArea();
        if (dataArea == null || dataArea.getWidth() <= 0) {
            return null;
        }
        ValueAxis axis = combinedPlot.getDomainAxis();
        EventInfo best = null;
        double bestDist = 5.0; // px tolerance
        for (EventInfo ev : events) {
            if (ev.time < axis.getLowerBound() || ev.time > axis.getUpperBound()) {
                continue;
            }
            double ex = axis.valueToJava2D(ev.time, dataArea, RectangleEdge.BOTTOM);
            double d = Math.abs(ex - screenX);
            if (d <= bestDist) {
                bestDist = d;
                best = ev;
            }
        }
        if (best == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(escape(eventLabel(best.type))).append("</b>");
        if (best.source != null && !best.source.isEmpty()) {
            sb.append("<br>").append(escape(best.source));
        }
        sb.append(String.format("<br>Occurred at %.2f s", best.time));
        sb.append("</html>");
        return sb.toString();
    }

    // ---- Event styling -----------------------------------------------------

    private static Color eventColor(FlightEvent.Type type) {
        switch (type) {
            case STRUCTURAL_FAILURE:
            case THERMAL_FAILURE:
            case BOND_FAILURE:
            case PARACHUTE_FAILURE:
            case COMPONENT_SEPARATION:
            case SIM_ABORT:
            case EXCEPTION:
                return new Color(255, 90, 90);     // failures – red
            case APOGEE:
                return new Color(206, 150, 255);   // apogee – violet
            case RECOVERY_DEVICE_DEPLOYMENT:
                return new Color(120, 235, 140);   // deployment – green
            case BURNOUT:
            case EJECTION_CHARGE:
                return new Color(255, 196, 96);    // burnout/ejection – amber
            case GROUND_HIT:
                return new Color(180, 180, 190);   // landing – grey
            default:
                return new Color(110, 170, 230);   // ignition/liftoff/etc – blue
        }
    }

    private static String eventLabel(FlightEvent.Type type) {
        String s = type.toString();
        return (s == null || s.isEmpty()) ? type.name() : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double[] toArray(List<Double> list) {
        if (list == null) {
            return new double[0];
        }
        return list.stream().mapToDouble(d -> d == null || d.isNaN() ? 0.0 : d).toArray();
    }

    // ---- Chart panel with scrub/pan/zoom/tooltip handling ------------------

    /**
     * ChartPanel specialised for the telemetry timeline: left-drag scrubs the replay,
     * shift-drag pans, the wheel zooms the time axis, a double-click resets the zoom,
     * and hovering an event marker shows its details.
     */
    private final class TelemetryChartPanel extends ChartPanel {
        private Integer panLastX;

        TelemetryChartPanel(JFreeChart chart) {
            super(chart,
                    /* properties */ false,
                    /* save */ true,
                    /* print */ false,
                    /* zoom */ false,
                    /* tooltips */ true);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger()) {
                if (e.isShiftDown()) {
                    panLastX = e.getX();
                } else {
                    scrubToScreenX(e.getX());
                }
            } else {
                super.mousePressed(e);   // right-click popup (save / auto-range) etc.
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                if (e.isShiftDown() && panLastX != null) {
                    panByPixels(e.getX() - panLastX);
                    panLastX = e.getX();
                } else if (!e.isShiftDown()) {
                    scrubToScreenX(e.getX());
                }
            } else {
                super.mouseDragged(e);
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                panLastX = null;
            } else {
                super.mouseReleased(e);
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                resetZoom();
            } else {
                super.mouseClicked(e);
            }
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            String tip = eventTooltipAt(e.getX(), e.getY());
            return (tip != null) ? tip : super.getToolTipText(e);
        }
    }
}
