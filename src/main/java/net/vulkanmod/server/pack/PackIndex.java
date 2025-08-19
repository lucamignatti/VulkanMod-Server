package net.vulkanmod.server.pack;

import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PackIndex links blockstates to resolved models for a given resource pack and exposes
 * simple lookups used by the server-side renderer.
 *
 * Responsibilities:
 * - Load BlockstateIndex and ModelIndex from either a ZIP file or a directory pack.
 * - For each blockstate, pick a primary model (Step A scope) and resolve it into a ResolvedModel.
 * - Cache blockId -> ResolvedModel mappings for fast runtime access.
 * - Provide helpers to inspect resolved entries and referenced textures (for atlas inclusion).
 *
 * Notes:
 * - This class is intentionally free of client dependencies and only understands a subset of
 *   vanilla model templates (see ModelIndex).
 * - Multipart blockstates and complex variant selection are out of scope for Step A.
 */
public final class PackIndex {

    private final BlockstateIndex states = new BlockstateIndex();
    private final ModelIndex models = new ModelIndex();
    private final Map<String, ResolvedModel> byBlockId = new HashMap<>();

    // One-time logging guards
    private final Set<String> warnedMissingModel = new HashSet<>();
    private final Set<String> warnedUnresolved = new HashSet<>();

    /**
     * Load indexes from a resource pack ZIP, then link blockIds to resolved models.
     */
    public void loadFromZip(Path zip) {
        Objects.requireNonNull(zip, "zip");
        if (!Files.isRegularFile(zip)) {
            System.err.println("[PackIndex] ZIP not found: " + zip);
            return;
        }
        states.indexFromZip(zip);
        models.indexFromZip(zip);
        linkAll();
    }

    /**
     * Load indexes from a resource pack directory, then link blockIds to resolved models.
     */
    public void loadFromDir(Path dir) {
        Objects.requireNonNull(dir, "dir");
        if (!Files.isDirectory(dir)) {
            System.err.println("[PackIndex] Directory not found: " + dir);
            return;
        }
        states.indexFromDir(dir);
        models.indexFromDir(dir);
        linkAll();
    }

    /**
     * Returns an Optional ResolvedModel for a specific blockId, if present.
     * If not linked yet (e.g., called before load), attempts a one-off resolve and caches it.
     */
    public Optional<ResolvedModel> get(String blockId) {
        if (blockId == null || blockId.isEmpty()) return Optional.empty();
        String id = canonicalizeBlockId(blockId);
        ResolvedModel rm = byBlockId.get(id);
        if (rm != null) return Optional.of(rm);

        // Attempt on-demand resolution and cache for future calls
        var modelOpt = states.pickPrimaryModel(id);
        if (modelOpt.isEmpty()) {
            if (warnedMissingModel.add(id)) {
                System.out.println("[PackIndex] No primary model for blockId='" + id + "'");
            }
            return Optional.empty();
        }
        var resolvedOpt = models.resolve(modelOpt.get());
        if (resolvedOpt.isEmpty()) {
            if (warnedUnresolved.add(id)) {
                System.out.println("[PackIndex] Failed to resolve model '" + modelOpt.get() + "' for blockId='" + id + "'");
            }
            return Optional.empty();
        }
        rm = resolvedOpt.get();
        byBlockId.put(id, rm);
        return Optional.of(rm);
    }

    /**
     * Returns an immutable snapshot of all blockId -> ResolvedModel mappings currently linked.
     */
    public Map<String, ResolvedModel> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byBlockId));
    }

    /**
     * Returns all blockIds known to this pack (based on blockstate files). This is useful
     * for preloading atlas textures for every block that will be resolvable.
     */
    public Set<String> listAllBlockIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(introspectBlockstateKeys(states)));
    }

    /**
     * Returns a set of logical texture names referenced by all currently resolved models.
     * Includes per-face mappings for cube-like models, the cross sprite for billboard models,
     * and both still/flow sprites for WATER render type.
     *
     * Note: Call linkAll() first (via loadFromZip/Dir) to populate byBlockId, otherwise this
     * will only reflect entries resolved so far.
     */
    public Set<String> collectAllReferencedTextures() {
        Set<String> out = new LinkedHashSet<>();
        for (ResolvedModel rm : byBlockId.values()) {
            switch (rm.getRenderType()) {
                case BILLBOARD_CROSS, BILLBOARD_CROSS_TINTED -> {
                    String s = rm.getCrossSprite();
                    if (s != null && !s.isEmpty()) out.add(s);
                }
                case WATER -> {
                    // Special-case water: include both still and flow
                    out.add("minecraft:block/water_still");
                    out.add("minecraft:block/water_flow");
                }
                default -> {
                    for (String name : rm.getFaceTextures().values()) {
                        if (name != null && !name.isEmpty()) out.add(name);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Convenience: return a RenderType for a blockId (defaults to SOLID if unknown).
     */
    public RenderType getRenderTypeForBlockId(String blockId) {
        return get(blockId).map(ResolvedModel::getRenderType).orElse(RenderType.SOLID);
    }

    // ------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------

    /**
     * Link all blockIds from the blockstate index to their resolved models and populate the cache.
     * This uses reflection to read BlockstateIndex's internal key set to avoid broad public APIs.
     */
    private void linkAll() {
        byBlockId.clear();
        Set<String> blockIds = introspectBlockstateKeys(states);
        if (blockIds.isEmpty()) {
            System.out.println("[PackIndex] No blockstates discovered. Nothing to link.");
            return;
        }
        int linked = 0;
        for (String id : blockIds) {
            var optModel = states.pickPrimaryModel(id);
            if (optModel.isEmpty()) {
                // Known cases: multipart or ambiguous variants are skipped in Step A
                continue;
            }
            var optResolved = models.resolve(optModel.get());
            if (optResolved.isEmpty()) {
                // Unknown or unhandled template; already logged by ModelIndex
                continue;
            }
            byBlockId.put(canonicalizeBlockId(id), optResolved.get());
            linked++;
        }
        System.out.println("[PackIndex] Linked " + linked + " of " + blockIds.size() + " blockstates to models.");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> introspectBlockstateKeys(BlockstateIndex idx) {
        // We keep BlockstateIndex's raw map private for encapsulation; use reflection locally
        // to enumerate its keys without expanding the public API surface.
        try {
            Field f = BlockstateIndex.class.getDeclaredField("rawStates");
            f.setAccessible(true);
            Object v = f.get(idx);
            if (v instanceof Map<?, ?> m) {
                Set<String> out = new LinkedHashSet<>();
                for (Object k : m.keySet()) {
                    if (k instanceof String s) out.add(canonicalizeBlockId(s));
                }
                return out;
            }
        } catch (Throwable t) {
            System.err.println("[PackIndex] Reflection failed to enumerate blockstate keys: " + t);
        }
        return Collections.emptySet();
    }

    private static String canonicalizeBlockId(String blockId) {
        if (blockId == null) return "minecraft:air";
        String s = blockId.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
    }
}
