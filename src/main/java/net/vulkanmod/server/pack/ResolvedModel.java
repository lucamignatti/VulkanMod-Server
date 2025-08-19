package net.vulkanmod.server.pack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved, server-side representation of a block model after applying the resource pack's
 * model parent-chain and texture indirections. This structure is intentionally small:
 * - RenderType: how the geometry should be rendered (solid/cutout/translucent/billboard/water).
 * - Per-face texture mapping for cube-like templates (face logical names like "minecraft:block/stone").
 * - Single sprite name for billboard-cross templates.
 *
 * Notes:
 * - Texture logical names are stored as-is (no canonicalization here). Atlas lookups are responsible
 *   for accepting short or namespaced forms and resolving to UV regions.
 * - WATER is treated as a special RenderType; per-face selection (still vs flow) is handled at lookup time.
 */
public final class ResolvedModel {

    public enum Face { TOP, BOTTOM, NORTH, SOUTH, WEST, EAST }

    private final RenderType renderType;
    private final EnumMap<Face, String> faceTextures; // logical texture names, e.g. "minecraft:block/stone"
    private final String crossSprite;                 // for cross/tinted_cross models

    public ResolvedModel(RenderType type, Map<Face, String> faces, String crossSprite) {
        this.renderType = Objects.requireNonNull(type, "renderType");
        this.faceTextures = (faces != null) ? new EnumMap<>(faces) : new EnumMap<>(Face.class);
        this.crossSprite = crossSprite;
    }

    public RenderType getRenderType() {
        return renderType;
    }

    /**
     * Returns an unmodifiable view of the face texture map.
     */
    public Map<Face, String> getFaceTextures() {
        return Collections.unmodifiableMap(faceTextures);
    }

    /**
     * Returns the logical texture name for a specific face, or null if not present.
     */
    public String getFaceTexture(Face f) {
        return faceTextures.get(f);
    }

    /**
     * Returns the sprite used for BILLBOARD_CROSS or BILLBOARD_CROSS_TINTED models. Null otherwise.
     */
    public String getCrossSprite() {
        return crossSprite;
    }

    // --------------------------------------------------------------------------------------------
    // Convenience factory methods for common vanilla model templates
    // --------------------------------------------------------------------------------------------

    /**
     * cube_all: same texture on every face.
     */
    public static ResolvedModel cubeAll(String tex) {
        EnumMap<Face, String> m = new EnumMap<>(Face.class);
        for (Face f : Face.values()) {
            m.put(f, tex);
        }
        return new ResolvedModel(RenderType.SOLID, m, null);
    }

    /**
     * cube_bottom_top: side/top/bottom mapping.
     */
    public static ResolvedModel cubeBottomTop(String top, String bottom, String side) {
        EnumMap<Face, String> m = new EnumMap<>(Face.class);
        m.put(Face.TOP, top);
        m.put(Face.BOTTOM, bottom);
        m.put(Face.NORTH, side);
        m.put(Face.SOUTH, side);
        m.put(Face.WEST, side);
        m.put(Face.EAST, side);
        return new ResolvedModel(RenderType.SOLID, m, null);
    }

    /**
     * cube_column: end for top/bottom, side for the ring faces (logs/hay/basalt).
     */
    public static ResolvedModel cubeColumn(String end, String side) {
        EnumMap<Face, String> m = new EnumMap<>(Face.class);
        m.put(Face.TOP, end);
        m.put(Face.BOTTOM, end);
        m.put(Face.NORTH, side);
        m.put(Face.SOUTH, side);
        m.put(Face.WEST, side);
        m.put(Face.EAST, side);
        return new ResolvedModel(RenderType.SOLID, m, null);
    }

    /**
     * cactus: explicit top/bottom/side mapping.
     */
    public static ResolvedModel cactus(String top, String bottom, String side) {
        EnumMap<Face, String> m = new EnumMap<>(Face.class);
        m.put(Face.TOP, top);
        m.put(Face.BOTTOM, bottom);
        m.put(Face.NORTH, side);
        m.put(Face.SOUTH, side);
        m.put(Face.WEST, side);
        m.put(Face.EAST, side);
        // Cactus is treated as SOLID geometry (no alpha fringe issues when sampled with padding).
        return new ResolvedModel(RenderType.SOLID, m, null);
    }

    /**
     * cross: crossed-quad billboard without biome tint (e.g., torch, dead_bush).
     */
    public static ResolvedModel cross(String sprite) {
        return new ResolvedModel(RenderType.BILLBOARD_CROSS, null, sprite);
    }

    /**
     * tinted_cross: crossed-quad billboard with biome tint (e.g., short_grass, tall_grass, fern).
     */
    public static ResolvedModel tintedCross(String sprite) {
        return new ResolvedModel(RenderType.BILLBOARD_CROSS_TINTED, null, sprite);
    }

    /**
     * water: special-case; selection of still vs flow is done at texture lookup time.
     */
    public static ResolvedModel water() {
        return new ResolvedModel(RenderType.WATER, null, null);
    }

    @Override
    public String toString() {
        return "ResolvedModel{" +
                "renderType=" + renderType +
                ", faceTextures=" + faceTextures +
                ", crossSprite=" + crossSprite +
                '}';
    }

    @Override
    public int hashCode() {
        int h = renderType.hashCode();
        h = 31 * h + faceTextures.hashCode();
        h = 31 * h + (crossSprite != null ? crossSprite.hashCode() : 0);
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedModel other)) return false;
        if (this.renderType != other.renderType) return false;
        if (!this.faceTextures.equals(other.faceTextures)) return false;
        return Objects.equals(this.crossSprite, other.crossSprite);
    }
}
