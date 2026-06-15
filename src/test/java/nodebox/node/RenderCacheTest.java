package nodebox.node;

import com.google.common.collect.ImmutableMap;
import nodebox.function.*;
import nodebox.util.SideEffects;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static junit.framework.TestCase.*;
import static nodebox.util.Assertions.assertResultsEqual;

/**
 * Tests for cross-render result caching (see {@link RenderCache}).
 *
 * <p>These are computation-count tests: they assert how many times a node's underlying function runs,
 * using the {@code side-effects/increaseAndCount} probe. That makes them deterministic regression
 * guards — a future change that breaks caching (recomputing unchanged nodes) or breaks invalidation
 * (reusing a stale result after an edit) changes a count or a value and fails the test.
 */
public class RenderCacheTest {

    private final FunctionRepository functions = FunctionRepository.of(
            CoreFunctions.LIBRARY, MathFunctions.LIBRARY, ListFunctions.LIBRARY, SideEffects.LIBRARY);

    @Before
    public void setUp() {
        SideEffects.reset();
    }

    private final Node makeNumbersNode = Node.ROOT
            .withName("makeNumbers")
            .withFunction("math/makeNumbers")
            .withOutputRange(Port.Range.LIST)
            .withInputAdded(Port.stringPort("string", ""))
            .withInputAdded(Port.stringPort("separator", " "));

    private final Node incNode = Node.ROOT
            .withName("inc")
            .withFunction("side-effects/increaseAndCount")
            .withInputAdded(Port.floatPort("number", 0));

    private final Node addNode = Node.ROOT
            .withName("add")
            .withFunction("math/add")
            .withInputAdded(Port.floatPort("v1", 0.0))
            .withInputAdded(Port.floatPort("v2", 0.0));

    private List<?> render(Node root, String childName, RenderCache cache) {
        NodeLibrary library = NodeLibrary.create("test", root, functions);
        Node child = root.getChild(childName);
        return new NodeContext(library, functions, ImmutableMap.<String, Object>of(), ImmutableMap.<String, Object>of(), cache)
                .renderChild("/", child);
    }

    // ------------------------------------------------------------------
    // Reuse across renders.
    // ------------------------------------------------------------------

    @Test
    public void resultReusedAcrossRendersWithSharedCache() {
        Node makeNumbers = makeNumbersNode.withName("makeNumbers").withInputValue("string", "1 2 3");
        Node net = Node.NETWORK
                .withChildAdded(makeNumbers)
                .withChildAdded(incNode)
                .connect("makeNumbers", "inc", "number");

        RenderCache cache = new RenderCache();
        assertResultsEqual(render(net, "inc", cache), 2.0, 3.0, 4.0);
        assertEquals(3, SideEffects.theCounter);

        // A second render with the same cache reuses the result: the function does not run again.
        assertResultsEqual(render(net, "inc", cache), 2.0, 3.0, 4.0);
        assertEquals("Result should be reused across renders, not recomputed", 3, SideEffects.theCounter);
    }

    @Test
    public void withoutSharedCacheResultIsRecomputed() {
        Node makeNumbers = makeNumbersNode.withName("makeNumbers").withInputValue("string", "1 2 3");
        Node net = Node.NETWORK
                .withChildAdded(makeNumbers)
                .withChildAdded(incNode)
                .connect("makeNumbers", "inc", "number");

        // No cache (null): each render recomputes from scratch.
        render(net, "inc", null);
        render(net, "inc", null);
        assertEquals(6, SideEffects.theCounter);
    }

    // ------------------------------------------------------------------
    // Incremental invalidation: only the changed subgraph recomputes.
    // ------------------------------------------------------------------

    @Test
    public void editingDownstreamReusesUpstream() {
        // inc (counted, shared) feeds two add nodes. Editing one add must NOT recompute inc.
        Node makeNumbers = makeNumbersNode.withName("makeNumbers").withInputValue("string", "1 2 3");
        Node addA = addNode.withName("addA").withInputValue("v2", 10.0);
        Node addB = addNode.withName("addB").withInputValue("v2", 100.0);
        Node net = Node.NETWORK
                .withChildAdded(makeNumbers)
                .withChildAdded(incNode)
                .withChildAdded(addA)
                .withChildAdded(addB)
                .connect("makeNumbers", "inc", "number")
                .connect("inc", "addA", "v1")
                .connect("inc", "addB", "v1");

        RenderCache cache = new RenderCache();
        assertResultsEqual(render(net, "addA", cache), 12.0, 13.0, 14.0);
        assertResultsEqual(render(net, "addB", cache), 102.0, 103.0, 104.0);
        assertEquals("inc runs once for its 3 elements", 3, SideEffects.theCounter);

        // Edit only addB. inc (shared upstream) must be reused, so the counter stays at 3.
        Node net2 = net.withChildReplaced("addB", addB.withInputValue("v2", 200.0));
        assertResultsEqual(render(net2, "addB", cache), 202.0, 203.0, 204.0);
        assertEquals("Editing addB must not recompute the shared upstream inc node", 3, SideEffects.theCounter);

        // addA is untouched and fully reused.
        assertResultsEqual(render(net2, "addA", cache), 12.0, 13.0, 14.0);
        assertEquals(3, SideEffects.theCounter);
    }

    @Test
    public void editingUpstreamRecomputesDownstream() {
        Node makeNumbers = makeNumbersNode.withName("makeNumbers").withInputValue("string", "1 2 3");
        Node net = Node.NETWORK
                .withChildAdded(makeNumbers)
                .withChildAdded(incNode)
                .connect("makeNumbers", "inc", "number");

        RenderCache cache = new RenderCache();
        assertResultsEqual(render(net, "inc", cache), 2.0, 3.0, 4.0);
        assertEquals(3, SideEffects.theCounter);

        // Change the upstream source: inc must recompute and reflect the new input.
        Node net2 = net.withChildReplaced("makeNumbers", makeNumbers.withInputValue("string", "10 20 30 40"));
        assertResultsEqual(render(net2, "inc", cache), 11.0, 21.0, 31.0, 41.0);
        assertEquals("inc should recompute for the 4 new values", 7, SideEffects.theCounter);
    }

    // ------------------------------------------------------------------
    // Purity: context-dependent nodes are never cached (no stale results).
    // ------------------------------------------------------------------

    @Test
    public void contextNodeIsNotCachedAcrossFrames() {
        Node frameNode = Node.ROOT
                .withName("frame")
                .withFunction("core/frame")
                .withInputAdded(Port.customPort("context", "context"));
        Node net = Node.NETWORK.withChildAdded(frameNode);
        NodeLibrary library = NodeLibrary.create("test", net, functions);
        RenderCache cache = new RenderCache();

        List<?> atFrame1 = new NodeContext(library, functions, ImmutableMap.<String, Object>of("frame", 1.0),
                ImmutableMap.<String, Object>of(), cache).renderChild("/", frameNode);
        assertResultsEqual(atFrame1, 1.0);

        // Same cache, different frame: the frame node must reflect the new frame, not a stale cached value.
        List<?> atFrame2 = new NodeContext(library, functions, ImmutableMap.<String, Object>of("frame", 2.0),
                ImmutableMap.<String, Object>of(), cache).renderChild("/", frameNode);
        assertResultsEqual(atFrame2, 2.0);
    }

    @Test
    public void networkContainingContextNodeIsNotCacheable() {
        Node frameNode = Node.ROOT
                .withName("frame")
                .withFunction("core/frame")
                .withInputAdded(Port.customPort("context", "context"));
        Node pure = makeNumbersNode.withName("pure").withInputValue("string", "1 2 3");
        Node subnet = Node.NETWORK.withName("subnet")
                .withChildAdded(frameNode)
                .withChildAdded(pure)
                .withRenderedChildName("frame");

        RenderCache cache = new RenderCache();
        assertFalse("A network containing a context node must be uncacheable", cache.isCacheable(subnet));
        assertTrue("A pure leaf node must be cacheable", cache.isCacheable(pure));
    }
}
