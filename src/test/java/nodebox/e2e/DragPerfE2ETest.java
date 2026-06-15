package nodebox.e2e;

import nodebox.client.Application;
import nodebox.client.NodeBoxDocument;
import nodebox.client.PortView;
import nodebox.client.port.PortControl;
import nodebox.util.PerfMonitor;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Measures the end-to-end responsiveness of dragging a numeric parameter, the way it actually feels.
 * <p/>
 * It synthesizes a real drag by dispatching AWT {@link MouseEvent}s (PRESSED, a stream of DRAGGED,
 * RELEASED) to the actual {@link nodebox.ui.DraggableNumber}, driving the identical
 * mouseDragged -> fireStateChanged -> setValue -> requestRender path a user's mouse would. This is
 * deterministic and independent of screen geometry/focus, unlike a physical {@code Robot} screen
 * drag. Meanwhile a background probe samples Event Dispatch Thread scheduling latency (the proxy for
 * perceived jank) and {@link PerfMonitor} captures render/paint throughput.
 * <p/>
 * Gated behind {@code NODEBOX_E2E=1} like the other e2e tests. It is a measurement harness, not a
 * hard gate: it asserts only that the drag drove the pipeline, and writes a full report to the
 * artifacts directory (and stdout) for before/after comparison.
 */
public class DragPerfE2ETest {

    private static final String E2E_ENV = "NODEBOX_E2E";
    private static final long DEFAULT_TIMEOUT_MS = 20000;

    // The drag: how many mouse-move events, and the delay between them (~100 Hz, like a real mouse).
    private static final int DRAG_STEPS = 200;
    private static final int DRAG_STEP_DELAY_MS = 8;
    // EDT latency probe sampling interval.
    private static final int PROBE_INTERVAL_MS = 4;

    @BeforeClass
    public static void requireE2E() throws Exception {
        Assume.assumeTrue("E2E tests require NODEBOX_E2E=1", "1".equals(System.getenv(E2E_ENV)));
        Assume.assumeFalse("E2E tests require a graphics environment", GraphicsEnvironment.isHeadless());
        if (Application.getInstance() == null) {
            Application.main(new String[]{});
            waitFor("Application instance", DEFAULT_TIMEOUT_MS, new Supplier<Boolean>() {
                public Boolean get() {
                    return Application.getInstance() != null;
                }
            });
        }
        waitFor("Initial document", DEFAULT_TIMEOUT_MS, new Supplier<Boolean>() {
            public Boolean get() {
                Application app = Application.getInstance();
                return app != null && app.getDocumentCount() > 0 && app.getCurrentDocument() != null;
            }
        });
    }

    @Test
    public void dragFloatParameter() throws Exception {
        // Defaults to a reasonably heavy example (dragging copy1.rotate rotates a full spirograph,
        // forcing an ancestor re-render plus a full antialiased redraw every frame). Override with
        // -Dperf.example=... -Dperf.node=... -Dperf.port=... to profile other scenarios.
        final File example = new File(System.getProperty("perf.example",
                "examples/01 Basics/01 Shape/11 Spirograph/11 Spirograph.ndbx"));
        final String nodeName = System.getProperty("perf.node", "copy1");
        final String portName = System.getProperty("perf.port", "rotate");

        report(measureDrag(example, nodeName, portName), example, nodeName, portName);
    }

    private MeasurementResult measureDrag(final File example, final String nodeName, final String portName) throws Exception {
        final NodeBoxDocument doc = openExample(example);
        assertNotNull("Could not open example " + example, doc);

        // Activate the node so its parameter controls are built, then grab its DraggableNumber.
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                doc.toFront();
                doc.requestFocus();
                doc.setActiveNode(nodeName);
            }
        });
        waitFor("Parameter control for " + portName, DEFAULT_TIMEOUT_MS, new Supplier<Boolean>() {
            public Boolean get() {
                return draggableComponent(doc, portName) != null;
            }
        });

        final Component draggable = draggableComponent(doc, portName);
        assertNotNull("No DraggableNumber for " + portName, draggable);
        System.out.println("[perf] dragging " + nodeName + "." + portName + " via " + draggable.getClass().getSimpleName());

        final int cx = draggable.getWidth() / 2;   // centre, clear of the +/- buttons at the edges
        final int cy = draggable.getHeight() / 2;

        // Warm up: a short drag + settle so the initial cold full render and JIT compilation do not
        // pollute the steady-state numbers we care about for "feelable" dragging.
        dispatchMouse(draggable, MouseEvent.MOUSE_PRESSED, cx, cy);
        for (int i = 0; i < 30; i++) {
            dispatchMouse(draggable, MouseEvent.MOUSE_DRAGGED, cx + triangle(i, 10), cy);
            Thread.sleep(DRAG_STEP_DELAY_MS);
        }
        dispatchMouse(draggable, MouseEvent.MOUSE_RELEASED, cx, cy);
        Thread.sleep(1500); // let all queued renders/paints drain

        final List<Long> edtLatencies = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicBoolean probing = new AtomicBoolean(true);
        Thread probe = startEdtLatencyProbe(edtLatencies, probing);

        PerfMonitor.setEnabled(true);
        PerfMonitor.reset();

        // Synthesize a real drag: PRESSED, a stream of DRAGGED at ~100 Hz, then RELEASED, all
        // dispatched to the actual DraggableNumber on the EDT. This drives the identical
        // mouseDragged -> fireStateChanged -> setValue -> requestRender path a user's mouse would,
        // including the same EDT contention, but is deterministic and independent of screen geometry.
        dispatchMouse(draggable, MouseEvent.MOUSE_PRESSED, cx, cy);

        long dragStart = System.nanoTime();
        for (int i = 0; i < DRAG_STEPS; i++) {
            final int x = cx + triangle(i, 40); // oscillate +/- a few px each step
            dispatchMouse(draggable, MouseEvent.MOUSE_DRAGGED, x, cy);
            Thread.sleep(DRAG_STEP_DELAY_MS);
        }
        dispatchMouse(draggable, MouseEvent.MOUSE_RELEASED, cx, cy);
        double dragSeconds = (System.nanoTime() - dragStart) / 1_000_000_000.0;

        // Let the final coalesced render settle so it is counted.
        Thread.sleep(700);

        probing.set(false);
        probe.join(1000);

        PerfMonitor.Snapshot snapshot = PerfMonitor.snapshot();
        PerfMonitor.setEnabled(false);

        assertTrue("Drag should have requested renders", snapshot.renderRequests > 0);
        assertTrue("Drag should have produced paints", snapshot.paintCount() > 0);

        return new MeasurementResult(snapshot, new ArrayList<Long>(edtLatencies), dragSeconds);
    }

    private Thread startEdtLatencyProbe(final List<Long> samples, final AtomicBoolean running) {
        Thread t = new Thread("edt-latency-probe") {
            public void run() {
                while (running.get()) {
                    final long t0 = System.nanoTime();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            samples.add(System.nanoTime() - t0);
                        }
                    });
                    try {
                        Thread.sleep(PROBE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void report(MeasurementResult result, File example, String nodeName, String portName) throws Exception {
        PerfMonitor.Snapshot s = result.snapshot;
        List<Long> edt = result.edtLatencies;

        double effectiveFps = result.dragSeconds > 0 ? s.paintCount() / result.dragSeconds : 0;
        double coalesceRatio = s.renderRequests > 0 ? (double) s.rendersCoalesced / s.renderRequests : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("=== NodeBox drag responsiveness ===\n");
        sb.append(String.format("example         : %s%n", example.getPath()));
        sb.append(String.format("node.port       : %s.%s%n", nodeName, portName));
        sb.append(String.format("drag duration   : %.2f s (%d mouse moves)%n", result.dragSeconds, DRAG_STEPS));
        sb.append("\n--- pipeline ---\n");
        sb.append(s.toString());
        sb.append(String.format("coalesce ratio  : %.0f%% of requests dropped while busy%n", coalesceRatio * 100));
        sb.append(String.format("effective FPS   : %.1f (viewer paints / drag second)%n", effectiveFps));
        sb.append("\n--- EDT scheduling latency (perceived jank) ---\n");
        sb.append(String.format("samples         : %d%n", edt.size()));
        sb.append(String.format("mean            : %.2f ms%n", ms(mean(edt))));
        sb.append(String.format("p50             : %.2f ms%n", ms(percentile(edt, 50))));
        sb.append(String.format("p95             : %.2f ms%n", ms(percentile(edt, 95))));
        sb.append(String.format("p99             : %.2f ms%n", ms(percentile(edt, 99))));
        sb.append(String.format("max             : %.2f ms%n", ms(max(edt))));
        sb.append(String.format("frames > 16.7ms : %.0f%% (each is a likely dropped 60fps frame)%n",
                fractionAbove(edt, 16_700_000L) * 100));

        String text = sb.toString();
        System.out.println(text);

        File out = new File(artifactsDir(), "drag-perf.txt");
        try (PrintWriter w = new PrintWriter(out)) {
            w.print(text);
        }
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    //// Control discovery (via the document's private PortView -> FloatControl's DraggableNumber) ////

    private static PortControl portControl(NodeBoxDocument doc, String portName) {
        try {
            Field f = NodeBoxDocument.class.getDeclaredField("portView");
            f.setAccessible(true);
            PortView pv = (PortView) f.get(doc);
            return pv == null ? null : pv.getControlForPort(portName);
        } catch (Exception e) {
            return null;
        }
    }

    /** The DraggableNumber inside a numeric control, located reflectively, or null. */
    private static Component draggableComponent(NodeBoxDocument doc, String portName) {
        PortControl ctrl = portControl(doc, portName);
        if (ctrl == null) return null;
        try {
            Field f = ctrl.getClass().getDeclaredField("draggable");
            f.setAccessible(true);
            Object d = f.get(ctrl);
            return (d instanceof Component && ((Component) d).isShowing()) ? (Component) d : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void dispatchMouse(final Component target, final int id, final int x, final int y) throws Exception {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                int mods = MouseEvent.BUTTON1_DOWN_MASK;
                int button = MouseEvent.BUTTON1;
                MouseEvent e = new MouseEvent(target, id, System.currentTimeMillis(), mods, x, y, 1, false, button);
                target.dispatchEvent(e);
            }
        });
    }

    //// Bootstrapping helpers ////

    private static NodeBoxDocument openExample(final File example) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                Application.getInstance().openExample(example);
            }
        });
        waitFor("Example open", DEFAULT_TIMEOUT_MS, new Supplier<Boolean>() {
            public Boolean get() {
                NodeBoxDocument doc = Application.getInstance().getCurrentDocument();
                return doc != null && doc.getActiveNetwork() != null;
            }
        });
        return Application.getInstance().getCurrentDocument();
    }

    private static void waitFor(String label, long timeoutMs, Supplier<Boolean> condition) throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            if (condition.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + label);
    }

    private static File artifactsDir() {
        File dir = new File("build/e2e-artifacts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    //// Small numeric helpers ////

    /** Triangle wave: rises 0..period then falls back, repeating. */
    private static int triangle(int i, int period) {
        int phase = i % (2 * period);
        return phase <= period ? phase : 2 * period - phase;
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static double mean(List<Long> values) {
        if (values.isEmpty()) return 0;
        long total = 0;
        for (long v : values) total += v;
        return (double) total / values.size();
    }

    private static double max(List<Long> values) {
        long m = 0;
        for (long v : values) m = Math.max(m, v);
        return m;
    }

    private static double percentile(List<Long> values, double pct) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index);
    }

    private static double fractionAbove(List<Long> values, long thresholdNanos) {
        if (values.isEmpty()) return 0;
        int count = 0;
        for (long v : values) if (v > thresholdNanos) count++;
        return (double) count / values.size();
    }

    private static final class MeasurementResult {
        final PerfMonitor.Snapshot snapshot;
        final List<Long> edtLatencies;
        final double dragSeconds;

        MeasurementResult(PerfMonitor.Snapshot snapshot, List<Long> edtLatencies, double dragSeconds) {
            this.snapshot = snapshot;
            this.edtLatencies = edtLatencies;
            this.dragSeconds = dragSeconds;
        }
    }
}
