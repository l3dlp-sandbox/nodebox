package nodebox.node;

import com.google.common.collect.ImmutableSet;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cache of node render results that persists <i>across</i> renders.
 *
 * <p>NodeBox builds a fresh {@link NodeContext} for every render (frame change, parameter tweak, ...).
 * Historically that meant re-evaluating the entire rendered network from scratch every time. This
 * cache lets unchanged parts of the network be reused between renders.
 *
 * <h2>Why this is correct</h2>
 *
 * <p>{@link Node} and {@link NodeLibrary} are immutable, and editing a network reuses the unchanged
 * child nodes by reference (structural sharing, see {@code Node.withChildReplaced}). So a node's
 * output is a pure function of:
 * <ul>
 *   <li>the {@link Node} itself (which captures its function, its literal port values and — for a
 *       subnetwork — its entire subtree), and</li>
 *   <li>the outputs of the nodes connected to its inputs.</li>
 * </ul>
 * The cache key (see {@link Key}) is therefore the identity of the child node plus the identities of
 * the result lists feeding its connected input ports. When the user edits one node, that node and its
 * ancestors get fresh identities while its siblings keep theirs, so only the changed subgraph misses
 * the cache. No explicit dirty-tracking is needed.
 *
 * <h2>Purity</h2>
 *
 * <p>The reasoning above only holds for <i>pure</i> nodes. Impurity in NodeBox flows through
 * <i>context</i> ports (frame, mouse position, device input): a node reading the context can return a
 * different value on every render even though its node identity and connected inputs are unchanged.
 * Such nodes — and, transitively, any subnetwork that contains one — are excluded from the cache via
 * {@link #isCacheable(Node)}. A small denylist additionally excludes functions that are impure without
 * declaring a context port (network fetches, OSC sends).
 *
 * <p>Feedback ("state") ports, which made a node depend on its <i>own previous output</i>, cannot be
 * expressed as a pure function of inputs and were removed from the engine; that removal is what makes
 * this cache sound.
 *
 * <h2>Threading</h2>
 *
 * <p>A document renders on a single background worker at a time, so a cache instance is only touched by
 * one thread during a render. It is not safe for concurrent use; give separate render pipelines (e.g.
 * an export job) their own cache.
 */
public final class RenderCache {

    /**
     * Functions that are impure but do not declare a context input port, so they cannot be detected by
     * the context-port rule. They must re-run on every render.
     */
    private static final ImmutableSet<String> IMPURE_FUNCTIONS = ImmutableSet.of(
            "network/httpGet",  // performs network I/O and refreshes on a time interval
            "device/sendOSC"    // sends data as a side effect
    );

    /** Upper bound on cached results, to keep memory bounded over a long session. Evicted least-recently-used. */
    private static final int MAX_ENTRIES = 10000;

    /** Upper bound on the purity memo; cleared wholesale if exceeded (it is cheap to recompute). */
    private static final int MAX_CACHEABLE_ENTRIES = 100000;

    private final LinkedHashMap<Key, List<?>> results = new LinkedHashMap<Key, List<?>>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, List<?>> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    // Memoizes transitive purity per node instance. Keyed by identity: purity is a pure function of the
    // (immutable) node, and edited nodes are new instances that get recomputed on demand.
    private final IdentityHashMap<Node, Boolean> cacheable = new IdentityHashMap<Node, Boolean>();

    public List<?> get(Key key) {
        return results.get(key);
    }

    public void put(Key key, List<?> value) {
        results.put(key, value);
    }

    /**
     * Whether the given node's result may be cached across renders: true unless the node, or anything in
     * its subtree, reads the execution context or is a known-impure function.
     */
    public boolean isCacheable(Node node) {
        Boolean known = cacheable.get(node);
        if (known != null) return known;
        if (cacheable.size() > MAX_CACHEABLE_ENTRIES) cacheable.clear();
        boolean result = computeCacheable(node);
        cacheable.put(node, result);
        return result;
    }

    private boolean computeCacheable(Node node) {
        for (Port port : node.getInputs()) {
            String type = port.getType();
            if (type.equals(Port.TYPE_CONTEXT) || type.equals(Port.TYPE_STATE)) return false;
        }
        if (IMPURE_FUNCTIONS.contains(node.getFunction())) return false;
        if (node.isNetwork()) {
            for (Node child : node.getChildren()) {
                if (!isCacheable(child)) return false;
            }
        }
        return true;
    }

    /**
     * Build a cache key for a child node being rendered. {@code connectedInputs} holds, in input-port
     * order, the result list feeding each <i>connected</i> input port (literal port values are already
     * captured by the node's identity and are not included here).
     */
    public static Key key(Node node, List<List<?>> connectedInputs) {
        return new Key(node, connectedInputs);
    }

    /**
     * An identity-based cache key. The node and the connected-input result lists are compared by
     * reference (==): the engine reuses the same node and result-list instances across renders for
     * unchanged subgraphs, so reference equality is both correct and cheap (no deep hashing of large
     * geometry lists).
     */
    public static final class Key {
        private final Node node;
        private final List<?>[] inputs;
        private final int hash;

        private Key(Node node, List<List<?>> connectedInputs) {
            this.node = node;
            this.inputs = connectedInputs.toArray(new List<?>[0]);
            int h = System.identityHashCode(node);
            for (List<?> input : inputs) {
                h = h * 31 + System.identityHashCode(input);
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key other = (Key) o;
            if (node != other.node || inputs.length != other.inputs.length) return false;
            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != other.inputs[i]) return false;
            }
            return true;
        }
    }
}
