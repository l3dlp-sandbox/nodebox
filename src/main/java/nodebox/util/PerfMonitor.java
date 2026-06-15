package nodebox.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A lightweight, opt-in profiling facility for the interactive render loop.
 * <p/>
 * It is disabled by default; when disabled every record* method is a single volatile
 * read and immediate return, so it adds no measurable overhead to production runs.
 * Enable it from a test harness (or with {@code -Dnodebox.perf=true}) to characterise
 * the drag/render/repaint pipeline.
 * <p/>
 * All record* methods that increment counters are invoked from the Event Dispatch
 * Thread (render bookkeeping and painting), so plain fields are sufficient; the
 * sample lists are synchronized because the reporting thread reads them concurrently.
 */
public final class PerfMonitor {

    private static volatile boolean enabled = "true".equalsIgnoreCase(System.getProperty("nodebox.perf"));

    private static final List<Long> renderDurations = Collections.synchronizedList(new ArrayList<Long>());
    private static final List<Long> paintDurations = Collections.synchronizedList(new ArrayList<Long>());

    private static volatile long renderRequests = 0;
    private static volatile long rendersCompleted = 0;
    private static volatile long rendersCoalesced = 0;

    private PerfMonitor() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static synchronized void reset() {
        renderDurations.clear();
        paintDurations.clear();
        renderRequests = 0;
        rendersCompleted = 0;
        rendersCoalesced = 0;
    }

    /** A render was requested (one per value change / drag tick that asks for a redraw). */
    public static void recordRenderRequest() {
        if (enabled) renderRequests++;
    }

    /** A requested render was dropped because another render was already in flight. */
    public static void recordRenderCoalesced() {
        if (enabled) rendersCoalesced++;
    }

    /** A render finished computing; {@code nanos} is the background compute time. */
    public static void recordRender(long nanos) {
        if (enabled) {
            rendersCompleted++;
            renderDurations.add(nanos);
        }
    }

    /** A viewer repaint finished; {@code nanos} is the time spent in paintComponent. */
    public static void recordPaint(long nanos) {
        if (enabled) paintDurations.add(nanos);
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(renderRequests, rendersCompleted, rendersCoalesced,
                new ArrayList<Long>(renderDurations), new ArrayList<Long>(paintDurations));
    }

    /** An immutable view of the collected metrics with a few derived statistics. */
    public static final class Snapshot {
        public final long renderRequests;
        public final long rendersCompleted;
        public final long rendersCoalesced;
        public final List<Long> renderDurations;
        public final List<Long> paintDurations;

        Snapshot(long renderRequests, long rendersCompleted, long rendersCoalesced,
                 List<Long> renderDurations, List<Long> paintDurations) {
            this.renderRequests = renderRequests;
            this.rendersCompleted = rendersCompleted;
            this.rendersCoalesced = rendersCoalesced;
            this.renderDurations = renderDurations;
            this.paintDurations = paintDurations;
        }

        private static double ms(double nanos) {
            return nanos / 1_000_000.0;
        }

        public double meanRenderMs() {
            return ms(mean(renderDurations));
        }

        public double p95RenderMs() {
            return ms(percentile(renderDurations, 95));
        }

        public double meanPaintMs() {
            return ms(mean(paintDurations));
        }

        public double p95PaintMs() {
            return ms(percentile(paintDurations, 95));
        }

        public int paintCount() {
            return paintDurations.size();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("render requests : %d%n", renderRequests));
            sb.append(String.format("renders done    : %d%n", rendersCompleted));
            sb.append(String.format("renders coalesced: %d%n", rendersCoalesced));
            sb.append(String.format("render compute  : mean %.2f ms, p95 %.2f ms%n", meanRenderMs(), p95RenderMs()));
            sb.append(String.format("paints          : %d (mean %.2f ms, p95 %.2f ms)%n", paintCount(), meanPaintMs(), p95PaintMs()));
            return sb.toString();
        }

        private static double mean(List<Long> values) {
            if (values.isEmpty()) return 0;
            long total = 0;
            for (long v : values) total += v;
            return (double) total / values.size();
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
    }
}
