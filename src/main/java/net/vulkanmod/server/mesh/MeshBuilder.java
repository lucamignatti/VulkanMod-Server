package net.vulkanmod.server.mesh;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.vulkanmod.server.ServerTextureAtlas;

/**
 * Server-only mesh builder scaffolding.
 *
 * Phase 2 scaffolding goals:
 * - No client-side classes/APIs.
 * - A simple, block-face mesher that emits only visible faces (neighbor is air).
 * - A tiny color LUT for common block keys (grass/stone/dirt/leaves/water/etc.).
 * - A basic, cheap lighting model using sky/block light and a sun direction.
 * - Outputs RegionMesh with an interleaved vertex buffer and index buffer.
 *
 * Notes:
 * - This is deliberately decoupled from Minecraft classes. Consumers should provide a BlockAccessor
 *   that maps world positions to "air or not", block keys (for LUT lookups), and sky/block light.
 * - The layout of vertices is compatible with RegionMesh (pos[3], normal[3], color[4]).
 * - This is a scaffolding that can be evolved to support textures/UVs in later phases.
 */
public final class MeshBuilder {

    public enum RenderLayer {
        SOLID,
        CUTOUT,
    }

    public static final class LayeredRegionMesh {

        public final RegionMesh solid;
        public final RegionMesh cutout;
        public final RegionMesh translucent;

        public LayeredRegionMesh(RegionMesh solid, RegionMesh cutout) {
            this(solid, cutout, null);
        }

        public LayeredRegionMesh(
            RegionMesh solid,
            RegionMesh cutout,
            RegionMesh translucent
        ) {
            this.solid = solid;
            this.cutout = cutout;
            this.translucent = translucent;
        }
    }

    public interface BlockAccessor {
        // True if the block at (x,y,z) is air (non-solid transparent)
        boolean isAir(int x, int y, int z);

        // A simple per-block key that can be mapped to a color LUT (e.g., "grass", "stone", etc.)
        String getBlockKey(int x, int y, int z);

        // 0..15 sky light level
        int getSkyLight(int x, int y, int z);

        // 0..15 block light level
        int getBlockLight(int x, int y, int z);

        // Optional world Y range (if not provided here, use the call-site minY/maxY)
        // Convenience offset helpers
        default boolean isAirOffset(
            int x,
            int y,
            int z,
            int ox,
            int oy,
            int oz
        ) {
            return isAir(x + ox, y + oy, z + oz);
        }

        default int getSkyLightOffset(
            int x,
            int y,
            int z,
            int ox,
            int oy,
            int oz
        ) {
            return getSkyLight(x + ox, y + oy, z + oz);
        }

        default int getBlockLightOffset(
            int x,
            int y,
            int z,
            int ox,
            int oy,
            int oz
        ) {
            return getBlockLight(x + ox, y + oy, z + oz);
        }

        // Optional world Y range (if not provided here, use the call-site minY/maxY)
        default int getMinY() {
            return 0;
        }

        default int getMaxY() {
            return 256;
        }
    }

    public static final class Config {

        public int regionSizeChunks = 8; // region is N x N chunks
        public int chunkSizeBlocks = 16; // standard chunk side length
        public int minY = 0; // world min Y (inclusive)
        public int maxY = 256; // world max Y (exclusive)
        public float[] sunDirection = norm3(new float[] { -0.4f, 1.0f, -0.2f });
        public float ambientMin = 0.15f; // minimum ambient
        public float sunIntensity = 0.6f; // contribution of sun based on N dot L
        public float skyLightWeight = 0.6f; // sky light influence in [0..1], remainder goes to block light
        public float gamma = 1.0f; // optional gamma
        public Map<String, float[]> colorLUT = defaultColorLUT();
        public float[] defaultColor = new float[] { 0.6f, 0.6f, 0.6f, 1.0f };
    }

    private final Config cfg;
    private BlockAccessor accRef; // transient accessor set during build for per-vertex lighting/AO

    public MeshBuilder() {
        this(new Config());
    }

    public MeshBuilder(Config config) {
        this.cfg = config;
        if (cfg.sunDirection == null) cfg.sunDirection = norm3(
            new float[] { 0, 1, 0 }
        );
        norm3InPlace(cfg.sunDirection);
        if (cfg.colorLUT == null) cfg.colorLUT = new java.util.HashMap<>();
        cfg.defaultColor = new float[] { 1, 1, 1, 1 };
        if (cfg.minY >= cfg.maxY) {
            // ensure sane range
            cfg.minY = 0;
            cfg.maxY = Math.max(cfg.maxY, 256);
        }
    }

    /**
     * Build a RegionMesh for a region identified by its chunk coordinates (regionChunkX, regionChunkZ),
     * using the provided BlockAccessor for data. The region spans regionSizeChunks * chunkSizeBlocks blocks per side.
     *
     * @param acc            BlockAccessor providing world data
     * @param regionChunkX   region origin chunk X
     * @param regionChunkZ   region origin chunk Z
     * @param version        a monotonic counter for cache invalidation (0 if unknown)
     * @return RegionMesh for the full region
     */
    public RegionMesh buildRegion(
        BlockAccessor acc,
        int regionChunkX,
        int regionChunkZ,
        long version
    ) {
        int cs = cfg.chunkSizeBlocks;
        int side = cfg.regionSizeChunks * cs;

        final int startX = regionChunkX * cs;
        final int startZ = regionChunkZ * cs;
        final int endX = startX + side; // exclusive
        final int endZ = startZ + side; // exclusive

        final int minY = clamp(cfg.minY, acc.getMinY(), acc.getMaxY());
        final int maxY = clamp(cfg.maxY, acc.getMinY(), acc.getMaxY());

        // AABB in world coordinates for this region
        float minXf = startX;
        float minYf = minY;
        float minZf = startZ;
        float maxXf = endX;
        float maxYf = maxY;
        float maxZf = endZ;

        GrowableFloatArray vtx = new GrowableFloatArray(1 << 18); // ~1MB float buffer start
        GrowableIntArray idx = new GrowableIntArray(1 << 18);

        // local caches
        final float[] baseColor = new float[4];
        final float[] faceColor = new float[4];
        final float[] nrm = new float[3];

        // Loop through blocks and emit faces when neighbor is air.
        for (int y = minY; y < maxY; y++) {
            for (int z = startZ; z < endZ; z++) {
                for (int x = startX; x < endX; x++) {
                    if (acc.isAir(x, y, z)) continue;

                    String key = safeKey(acc.getBlockKey(x, y, z));
                    float[] lutColor = cfg.colorLUT.getOrDefault(
                        key,
                        cfg.defaultColor
                    );
                    System.arraycopy(lutColor, 0, baseColor, 0, 4);

                    // Resolve UV region from the server-side texture atlas for this block key
                    ServerTextureAtlas.Region __uv =
                        ServerTextureAtlas.getInstance().getRegionForBlockKey(
                            key
                        );
                    float u0 = __uv.u0,
                        v0 = __uv.v0,
                        u1 = __uv.u1,
                        v1 = __uv.v1;

                    // For each face, check neighbor occupancy
                    // Face order: -Z, +Z, -X, +X, +Y (top), -Y (bottom)
                    // Normal and local quad emission for each.
                    // North (-Z)
                    if (isAirOrOOB(acc, x, y, z - 1, minY, maxY)) {
                        set3(nrm, 0, 0, -1);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_NZ,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // South (+Z)
                    if (isAirOrOOB(acc, x, y, z + 1, minY, maxY)) {
                        set3(nrm, 0, 0, 1);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_PZ,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // West (-X)
                    if (isAirOrOOB(acc, x - 1, y, z, minY, maxY)) {
                        set3(nrm, -1, 0, 0);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_NX,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // East (+X)
                    if (isAirOrOOB(acc, x + 1, y, z, minY, maxY)) {
                        set3(nrm, 1, 0, 0);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_PX,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // Top (+Y)
                    if (isAirOrOOB(acc, x, y + 1, z, minY, maxY)) {
                        set3(nrm, 0, 1, 0);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_t =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "top"
                            );
                        float tu0 = __uv_t.u0,
                            tv0 = __uv_t.v0,
                            tu1 = __uv_t.u1,
                            tv1 = __uv_t.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_PY,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            tu0,
                            tv0,
                            tu1,
                            tv1
                        );
                    }
                    // Bottom (-Y)
                    if (isAirOrOOB(acc, x, y - 1, z, minY, maxY)) {
                        set3(nrm, 0, -1, 0);
                        // Use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_b =
                            ServerTextureAtlas.getInstance().getRegionForBlockFace(
                                key,
                                "bottom"
                            );
                        float bu0 = __uv_b.u0,
                            bv0 = __uv_b.v0,
                            bu1 = __uv_b.u1,
                            bv1 = __uv_b.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_NY,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            bu0,
                            bv0,
                            bu1,
                            bv1
                        );
                    }
                }
            }
        }

        // Pack into arrays
        float[] vtxArr = Arrays.copyOf(vtx.data, vtx.size);
        int[] idxArr = Arrays.copyOf(idx.data, idx.size);

        return RegionMesh.fromArrays(
            regionChunkX,
            regionChunkZ,
            cfg.regionSizeChunks,
            vtxArr,
            idxArr,
            new float[] { minXf, minYf, minZf, maxXf, maxYf, maxZf },
            version
        );
    }

    // =============================================================================================
    // Face geometry definitions for unit cubes at integer block coordinates.
    // Each face is defined by 4 corners in local block space (x,y,z in {0,1}).
    // Indices are generated as two triangles with clockwise winding for Vulkan front-face CW.
    // =============================================================================================

    private static final float[][] FACE_NZ = new float[][] {
        { 0, 0, 0 },
        { 1, 0, 0 },
        { 1, 1, 0 },
        { 0, 1, 0 },
    };
    private static final float[][] FACE_PZ = new float[][] {
        { 1, 0, 1 },
        { 0, 0, 1 },
        { 0, 1, 1 },
        { 1, 1, 1 },
    };
    private static final float[][] FACE_NX = new float[][] {
        { 0, 0, 1 },
        { 0, 0, 0 },
        { 0, 1, 0 },
        { 0, 1, 1 },
    };
    private static final float[][] FACE_PX = new float[][] {
        { 1, 0, 0 },
        { 1, 0, 1 },
        { 1, 1, 1 },
        { 1, 1, 0 },
    };
    private static final float[][] FACE_PY = new float[][] {
        { 0, 1, 0 },
        { 1, 1, 0 },
        { 1, 1, 1 },
        { 0, 1, 1 },
    };
    private static final float[][] FACE_NY = new float[][] {
        { 0, 0, 1 },
        { 1, 0, 1 },
        { 1, 0, 0 },
        { 0, 0, 0 },
    };

    // Water-specific face variants that cap height at 0.875 to reduce edge grid lines
    private static final float[][] FACE_NZ_WATER = new float[][] {
        { 0, 0, 0 },
        { 1, 0, 0 },
        { 1, 0.875f, 0 },
        { 0, 0.875f, 0 },
    };
    private static final float[][] FACE_PZ_WATER = new float[][] {
        { 1, 0, 1 },
        { 0, 0, 1 },
        { 0, 0.875f, 1 },
        { 1, 0.875f, 1 },
    };
    private static final float[][] FACE_NX_WATER = new float[][] {
        { 0, 0, 1 },
        { 0, 0, 0 },
        { 0, 0.875f, 0 },
        { 0, 0.875f, 1 },
    };
    private static final float[][] FACE_PX_WATER = new float[][] {
        { 1, 0, 0 },
        { 1, 0, 1 },
        { 1, 0.875f, 1 },
        { 1, 0.875f, 0 },
    };
    private static final float[][] FACE_PY_WATER = new float[][] {
        { 0, 0.875f, 0 },
        { 1, 0.875f, 0 },
        { 1, 0.875f, 1 },
        { 0, 0.875f, 1 },
    };

    private void emitFaceQuad(
        GrowableFloatArray vtx,
        GrowableIntArray idx,
        int bx,
        int by,
        int bz,
        float[][] corners,
        float[] normal,
        float[] color,
        float u0,
        float v0,
        float u1,
        float v1
    ) {
        final int baseIndex = vtx.countVertices();

        // Precompute scale for atlas region
        final float du = (u1 - u0);
        final float dv = (v1 - v0);

        // Precompute directional shade from sun for this face
        float ndotl = clamp01(
            normal[0] * cfg.sunDirection[0] +
            normal[1] * cfg.sunDirection[1] +
            normal[2] * cfg.sunDirection[2]
        );
        float shade = clamp01(cfg.ambientMin + cfg.sunIntensity * ndotl);

        // 4 vertices
        for (int i = 0; i < 4; i++) {
            float cx = corners[i][0];
            float cy = corners[i][1];
            float cz = corners[i][2];

            float x = bx + cx;
            float y = by + cy;
            float z = bz + cz;

            // position (3)
            vtx.add(x);
            vtx.add(y);
            vtx.add(z);
            // normal (3)
            vtx.add(normal[0]);
            vtx.add(normal[1]);
            vtx.add(normal[2]);

            // Per-vertex lighting + AO (if accessor available), else use incoming color
            float colR = color[0],
                colG = color[1],
                colB = color[2],
                colA = color[3];
            if (this.accRef != null) {
                // Corner-based light sampling near this vertex
                int sx =
                    bx +
                    ((normal[0] < 0)
                            ? -1
                            : (normal[0] > 0 ? 1 : (cx >= 0.5f ? 1 : 0)));
                int sy =
                    by +
                    ((normal[1] < 0)
                            ? -1
                            : (normal[1] > 0 ? 1 : (cy >= 0.5f ? 1 : 0)));
                int sz =
                    bz +
                    ((normal[2] < 0)
                            ? -1
                            : (normal[2] > 0 ? 1 : (cz >= 0.5f ? 1 : 0)));

                float sky = clamp01(
                    this.accRef.getSkyLight(sx, sy, sz) / 15.0f
                );
                float blk = clamp01(
                    this.accRef.getBlockLight(sx, sy, sz) / 15.0f
                );
                float lmix =
                    cfg.skyLightWeight * sky +
                    (1.0f - cfg.skyLightWeight) * blk;

                // Face-local ambient occlusion from neighbor occupancy at this corner
                float ao = 1.0f;
                if (normal[2] != 0.0f) {
                    int nz = normal[2] < 0.0f ? -1 : 1;
                    int dui = (cx >= 0.5f) ? 1 : 0; // along +X
                    int dvi = (cy >= 0.5f) ? 1 : 0; // along +Y
                    boolean occU = !this.accRef.isAir(bx + dui, by, bz + nz);
                    boolean occV = !this.accRef.isAir(bx, by + dvi, bz + nz);
                    boolean occC = !this.accRef.isAir(
                        bx + dui,
                        by + dvi,
                        bz + nz
                    );
                    int occCount =
                        (occU ? 1 : 0) + (occV ? 1 : 0) + (occC ? 1 : 0);
                    ao = clamp01(1.0f - 0.25f * occCount);
                } else if (normal[0] != 0.0f) {
                    int nx = normal[0] < 0.0f ? -1 : 1;
                    int dui = (cz >= 0.5f) ? 1 : 0; // along +Z
                    int dvi = (cy >= 0.5f) ? 1 : 0; // along +Y
                    boolean occU = !this.accRef.isAir(bx + nx, by, bz + dui);
                    boolean occV = !this.accRef.isAir(bx + nx, by + dvi, bz);
                    boolean occC = !this.accRef.isAir(
                        bx + nx,
                        by + dvi,
                        bz + dui
                    );
                    int occCount =
                        (occU ? 1 : 0) + (occV ? 1 : 0) + (occC ? 1 : 0);
                    ao = clamp01(1.0f - 0.25f * occCount);
                } else {
                    int ny = normal[1] < 0.0f ? -1 : 1;
                    int dui = (cx >= 0.5f) ? 1 : 0; // along +X
                    int dvi = (cz >= 0.5f) ? 1 : 0; // along +Z
                    boolean occU = !this.accRef.isAir(bx + dui, by + ny, bz);
                    boolean occV = !this.accRef.isAir(bx, by + ny, bz + dvi);
                    boolean occC = !this.accRef.isAir(
                        bx + dui,
                        by + ny,
                        bz + dvi
                    );
                    int occCount =
                        (occU ? 1 : 0) + (occV ? 1 : 0) + (occC ? 1 : 0);
                    ao = clamp01(1.0f - 0.25f * occCount);
                }

                float lum = clamp01(shade * (0.5f + 0.5f * lmix) * ao);
                colR = powf(color[0] * lum, cfg.gamma);
                colG = powf(color[1] * lum, cfg.gamma);
                colB = powf(color[2] * lum, cfg.gamma);
                colA = color[3];
            }

            // color (4)
            vtx.add(colR);
            vtx.add(colG);
            vtx.add(colB);
            vtx.add(colA);

            // UVs: map the unit square directly into the atlas region in vertex order
            float uu, vv;
            if (i == 0) {
                uu = u0;
                vv = v1;
            } else if (i == 1) {
                uu = u1;
                vv = v1;
            } else if (i == 2) {
                uu = u1;
                vv = v0;
            } else {
                uu = u0;
                vv = v0;
            }
            vtx.add(uu);
            vtx.add(vv);
        }

        // Two triangles, CW winding:
        // (0,1,2) and (0,2,3)
        idx.add(baseIndex + 0);
        idx.add(baseIndex + 1);
        idx.add(baseIndex + 2);
        idx.add(baseIndex + 0);
        idx.add(baseIndex + 2);
        idx.add(baseIndex + 3);
    }

    // Helper: emit axis-aligned cuboid within a block, with per-face UVs and lighting
    // min/max are relative to the block cell [0..1] range in each axis
    private void emitCuboid(
        GrowableFloatArray vtx,
        GrowableIntArray idx,
        int bx,
        int by,
        int bz,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        ServerTextureAtlas.Region top,
        ServerTextureAtlas.Region bottom,
        ServerTextureAtlas.Region north,
        ServerTextureAtlas.Region south,
        ServerTextureAtlas.Region west,
        ServerTextureAtlas.Region east,
        BlockAccessor acc,
        float[] baseColor
    ) {
        final float[] nrm = new float[3];
        final float[] col = new float[4];

        // North (-Z)
        if (north != null) {
            set3(nrm, 0, 0, -1);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(north.u0);
            vtx.add(north.v1);
            // v1
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(north.u1);
            vtx.add(north.v1);
            // v2
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(north.u1);
            vtx.add(north.v0);
            // v3
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(north.u0);
            vtx.add(north.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }

        // South (+Z)
        if (south != null) {
            set3(nrm, 0, 0, 1);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(south.u0);
            vtx.add(south.v1);
            // v1
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(south.u1);
            vtx.add(south.v1);
            // v2
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(south.u1);
            vtx.add(south.v0);
            // v3
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(south.u0);
            vtx.add(south.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }

        // West (-X)
        if (west != null) {
            set3(nrm, -1, 0, 0);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(west.u0);
            vtx.add(west.v1);
            // v1
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(west.u1);
            vtx.add(west.v1);
            // v2
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(west.u1);
            vtx.add(west.v0);
            // v3
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(west.u0);
            vtx.add(west.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }

        // East (+X)
        if (east != null) {
            set3(nrm, 1, 0, 0);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(east.u0);
            vtx.add(east.v1);
            // v1
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(east.u1);
            vtx.add(east.v1);
            // v2
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(east.u1);
            vtx.add(east.v0);
            // v3
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(east.u0);
            vtx.add(east.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }

        // Top (+Y)
        if (top != null) {
            set3(nrm, 0, 1, 0);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(top.u0);
            vtx.add(top.v1);
            // v1
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(top.u1);
            vtx.add(top.v1);
            // v2
            vtx.add(bx + maxX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(top.u1);
            vtx.add(top.v0);
            // v3
            vtx.add(bx + minX);
            vtx.add(by + maxY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(top.u0);
            vtx.add(top.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }

        // Bottom (-Y)
        if (bottom != null) {
            set3(nrm, 0, -1, 0);
            float[] c = applyLighting(acc, bx, by, bz, nrm, baseColor, col);
            int baseIndex = vtx.countVertices();
            // v0
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(bottom.u0);
            vtx.add(bottom.v1);
            // v1
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + maxZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(bottom.u1);
            vtx.add(bottom.v1);
            // v2
            vtx.add(bx + maxX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(bottom.u1);
            vtx.add(bottom.v0);
            // v3
            vtx.add(bx + minX);
            vtx.add(by + minY);
            vtx.add(bz + minZ);
            vtx.add(nrm[0]);
            vtx.add(nrm[1]);
            vtx.add(nrm[2]);
            vtx.add(c[0]);
            vtx.add(c[1]);
            vtx.add(c[2]);
            vtx.add(c[3]);
            vtx.add(bottom.u0);
            vtx.add(bottom.v0);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 1);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 0);
            idx.add(baseIndex + 2);
            idx.add(baseIndex + 3);
        }
    }

    // =============================================================================================
    // Lighting and Color
    // =============================================================================================

    private float[] applyLighting(
        BlockAccessor acc,
        int x,
        int y,
        int z,
        float[] nrm,
        float[] baseColor,
        float[] outColor
    ) {
        // Defer lighting to per-vertex computation in emitFaceQuad; also bake biome tint for select keys.
        // Capture accessor so emitFaceQuad can sample local lights and occupancy.
        this.accRef = acc;

        // Start from base color then apply biome tint for grass/leaves/water
        float r = baseColor[0],
            g = baseColor[1],
            b = baseColor[2],
            a = baseColor[3];
        String __k = safeKey(acc.getBlockKey(x, y, z));
        if (acc instanceof WorldSnapshotAccessor ws) {
            int rgb = 0xFFFFFF;
            if ("grass".equals(__k)) {
                rgb = ws.getGrassTintRGB(x, y, z);
            } else if ("leaves".equals(__k)) {
                rgb = ws.getFoliageTintRGB(x, y, z);
            } else if ("water".equals(__k)) {
                rgb = ws.getWaterTintRGB(x, y, z);
            }
            float tr = ((rgb >> 16) & 0xFF) / 255.0f;
            float tg = ((rgb >> 8) & 0xFF) / 255.0f;
            float tb = (rgb & 0xFF) / 255.0f;
            r *= tr;
            g *= tg;
            b *= tb;
        }

        // Translucent materials: disable per-vertex AO. Water gets uniform, sky-only lighting sampled above surface; others use flat lighting.
        if ("water".equals(__k)) {
            this.accRef = null; // ensure emitFaceQuad doesn't apply per-vertex lighting
            // Use averaged sky light from a 3x3 cross above the water surface; ignore block light and remove directional shading.
            float sky0 = clamp01(acc.getSkyLight(x, y + 1, z) / 15.0f);
            float skyN = clamp01(acc.getSkyLight(x, y + 1, z - 1) / 15.0f);
            float skyS = clamp01(acc.getSkyLight(x, y + 1, z + 1) / 15.0f);
            float skyW = clamp01(acc.getSkyLight(x - 1, y + 1, z) / 15.0f);
            float skyE = clamp01(acc.getSkyLight(x + 1, y + 1, z) / 15.0f);
            float skyAvg = clamp01((sky0 + skyN + skyS + skyW + skyE) / 5.0f);
            // Uniform luminance without directional sun shading to eliminate grid lines
            float lum = clamp01(0.5f + 0.5f * skyAvg);
            outColor[0] = powf(r * lum, cfg.gamma);
            outColor[1] = powf(g * lum, cfg.gamma);
            outColor[2] = powf(b * lum, cfg.gamma);
            // Slight alpha clamp on water TOP faces to reduce harsh edges without hiding surfaces
            outColor[3] = (nrm[1] == 1.0f) ? Math.min(a, 0.92f) : a;
            return outColor;
        } else if (
            "ice".equals(__k) || "honey".equals(__k) || "glass".equals(__k)
        ) {
            this.accRef = null; // ensure emitFaceQuad doesn't apply per-vertex lighting
            float[] flatBase = new float[] { r, g, b, a };
            return applyFlatLighting(acc, x, y, z, nrm, flatBase, outColor);
        }

        outColor[0] = r;
        outColor[1] = g;
        outColor[2] = b;
        outColor[3] = a;
        return outColor;
    }

    // Flat (face-level) lighting utility for billboards and non-smooth cases
    private float[] applyFlatLighting(
        BlockAccessor acc,
        int x,
        int y,
        int z,
        float[] nrm,
        float[] baseColor,
        float[] outColor
    ) {
        // Disable per-vertex AO/lighting for flat-lit faces
        this.accRef = null;
        // Sky/block light in [0..1]
        float sky = clamp01(acc.getSkyLight(x, y, z) / 15.0f);
        float blk = clamp01(acc.getBlockLight(x, y, z) / 15.0f);
        float lmix =
            cfg.skyLightWeight * sky + (1.0f - cfg.skyLightWeight) * blk;

        // Sun shade from normal dot sunDir
        float ndotl = clamp01(
            nrm[0] * cfg.sunDirection[0] +
            nrm[1] * cfg.sunDirection[1] +
            nrm[2] * cfg.sunDirection[2]
        );
        float shade = clamp01(cfg.ambientMin + cfg.sunIntensity * ndotl);

        float lum = clamp01(shade * (0.5f + 0.5f * lmix)); // simple remap

        outColor[0] = powf(baseColor[0] * lum, cfg.gamma);
        outColor[1] = powf(baseColor[1] * lum, cfg.gamma);
        outColor[2] = powf(baseColor[2] * lum, cfg.gamma);
        outColor[3] = baseColor[3];
        return outColor;
    }

    // Billboard lighting: no directional shading and no AO.
    // Uses only sky/block light mix to avoid over-darkening on flora billboards.
    private float[] applyBillboardLighting(
        BlockAccessor acc,
        int x,
        int y,
        int z,
        float[] baseColor,
        float[] outColor
    ) {
        float sky = clamp01(acc.getSkyLight(x, y, z) / 15.0f);
        float blk = clamp01(acc.getBlockLight(x, y, z) / 15.0f);
        float lmix =
            cfg.skyLightWeight * sky + (1.0f - cfg.skyLightWeight) * blk;

        // Neutral luminance (no directional term)
        float lum = clamp01(0.5f + 0.5f * lmix);

        outColor[0] = powf(baseColor[0] * lum, cfg.gamma);
        outColor[1] = powf(baseColor[1] * lum, cfg.gamma);
        outColor[2] = powf(baseColor[2] * lum, cfg.gamma);
        outColor[3] = baseColor[3];
        return outColor;
    }

    private static Map<String, float[]> defaultColorLUT() {
        return new HashMap<>();
    }

    private static float[] rgba(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        return new float[] { r, g, b, 1.0f };
    }

    private static String safeKey(String k) {
        if (k == null || k.isEmpty()) return "default";
        return k.toLowerCase();
    }

    private static boolean isCutoutKey(String k) {
        if (k == null) return false;
        String s = k.toLowerCase();
        return (
            "leaves".equals(s) ||
            "short_grass".equals(s) ||
            "tall_grass".equals(s) ||
            "fern".equals(s) ||
            "dead_bush".equals(s) ||
            "kelp".equals(s) ||
            "cactus".equals(s) ||
            "torch".equals(s)
        );
    }

    private static boolean isTranslucentKey(String k) {
        if (k == null) return false;
        String s = k.toLowerCase();
        return (
            "water".equals(s) ||
            "ice".equals(s) ||
            "honey".equals(s) ||
            "glass".equals(s)
        );
    }

    public LayeredRegionMesh buildRegionLayered(
        BlockAccessor acc,
        int regionChunkX,
        int regionChunkZ,
        long version
    ) {
        int cs = cfg.chunkSizeBlocks;
        int side = cfg.regionSizeChunks * cs;

        final int startX = regionChunkX * cs;
        final int startZ = regionChunkZ * cs;
        final int endX = startX + side; // exclusive
        final int endZ = startZ + side; // exclusive

        final int minY = clamp(cfg.minY, acc.getMinY(), acc.getMaxY());
        final int maxY = clamp(cfg.maxY, acc.getMinY(), acc.getMaxY());

        // AABB in world coordinates for this region
        float minXf = startX;
        float minYf = minY;
        float minZf = startZ;
        float maxXf = endX;
        float maxYf = maxY;
        float maxZf = endZ;

        GrowableFloatArray vtxSolid = new GrowableFloatArray(1 << 20);
        GrowableIntArray idxSolid = new GrowableIntArray(1 << 21);
        GrowableFloatArray vtxCutout = new GrowableFloatArray(1 << 18);
        GrowableIntArray idxCutout = new GrowableIntArray(1 << 19);
        GrowableFloatArray vtxTranslucent = new GrowableFloatArray(1 << 18);
        GrowableIntArray idxTranslucent = new GrowableIntArray(1 << 19);

        // local caches
        final float[] baseColor = new float[4];
        final float[] faceColor = new float[4];
        final float[] nrm = new float[3];

        // Loop through blocks and emit faces when neighbor is air.
        for (int y = minY; y < maxY; y++) {
            for (int z = startZ; z < endZ; z++) {
                for (int x = startX; x < endX; x++) {
                    String key = safeKey(acc.getBlockKey(x, y, z));
                    String __blockId = (acc instanceof WorldSnapshotAccessor ws)
                        ? ws.getBlockId(x, y, z)
                        : null;
                    net.vulkanmod.server.pack.RenderType __rtype =
                        net.vulkanmod.server.ServerTextureAtlas.getInstance().getRenderTypeForBlockId(
                            __blockId
                        );
                    float[] lutColor = cfg.colorLUT.getOrDefault(
                        key,
                        cfg.defaultColor
                    );
                    System.arraycopy(lutColor, 0, baseColor, 0, 4);

                    // Billboard-style cutouts (resource-pack driven): emit crossed quads into CUTOUT and continue
                    if (
                        __rtype ==
                            net.vulkanmod.server.pack.RenderType.BILLBOARD_CROSS ||
                        __rtype ==
                        net.vulkanmod.server.pack.RenderType.BILLBOARD_CROSS_TINTED
                    ) {
                        ServerTextureAtlas.Region __uvbb =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                null
                            );
                        float bu0 = __uvbb.u0,
                            bv0 = __uvbb.v0,
                            bu1 = __uvbb.u1,
                            bv1 = __uvbb.v1;

                        // Billboard lighting: neutral (no directional shading and no AO) to avoid over-darkening
                        set3(nrm, 0, 1, 0);
                        // Apply biome tint only for tinted_cross models
                        float[] baseTintBB = baseColor;
                        if (
                            __rtype ==
                                net.vulkanmod.server.pack.RenderType.BILLBOARD_CROSS_TINTED &&
                            acc instanceof WorldSnapshotAccessor wsBB
                        ) {
                            int rgbBB = wsBB.getGrassTintRGB(x, y, z);
                            float trBB = ((rgbBB >> 16) & 0xFF) / 255.0f;
                            float tgBB = ((rgbBB >> 8) & 0xFF) / 255.0f;
                            float tbBB = (rgbBB & 0xFF) / 255.0f;
                            baseTintBB = new float[] {
                                baseColor[0] * trBB,
                                baseColor[1] * tgBB,
                                baseColor[2] * tbBB,
                                baseColor[3],
                            };
                        }
                        float[] colBB = applyBillboardLighting(
                            acc,
                            x,
                            y,
                            z,
                            baseTintBB,
                            faceColor
                        );

                        // Emit first diagonal quad (0,0,0) -> (1,1,1)
                        {
                            final int baseIndex = vtxCutout.countVertices();
                            // v0
                            vtxCutout.add(x + 0.0f);
                            vtxCutout.add(y + 0.0f);
                            vtxCutout.add(z + 0.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu0);
                            vtxCutout.add(bv1);
                            // v1
                            vtxCutout.add(x + 1.0f);
                            vtxCutout.add(y + 0.0f);
                            vtxCutout.add(z + 1.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu1);
                            vtxCutout.add(bv1);
                            // v2
                            vtxCutout.add(x + 1.0f);
                            vtxCutout.add(y + 1.0f);
                            vtxCutout.add(z + 1.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu1);
                            vtxCutout.add(bv0);
                            // v3
                            vtxCutout.add(x + 0.0f);
                            vtxCutout.add(y + 1.0f);
                            vtxCutout.add(z + 0.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu0);
                            vtxCutout.add(bv0);
                            // indices
                            idxCutout.add(baseIndex + 0);
                            idxCutout.add(baseIndex + 1);
                            idxCutout.add(baseIndex + 2);
                            idxCutout.add(baseIndex + 0);
                            idxCutout.add(baseIndex + 2);
                            idxCutout.add(baseIndex + 3);
                        }

                        // Emit second diagonal quad (1,0,0) -> (0,1,1)
                        {
                            final int baseIndex = vtxCutout.countVertices();
                            // v0
                            vtxCutout.add(x + 1.0f);
                            vtxCutout.add(y + 0.0f);
                            vtxCutout.add(z + 0.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu0);
                            vtxCutout.add(bv1);
                            // v1
                            vtxCutout.add(x + 0.0f);
                            vtxCutout.add(y + 0.0f);
                            vtxCutout.add(z + 1.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu1);
                            vtxCutout.add(bv1);
                            // v2
                            vtxCutout.add(x + 0.0f);
                            vtxCutout.add(y + 1.0f);
                            vtxCutout.add(z + 1.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu1);
                            vtxCutout.add(bv0);
                            // v3
                            vtxCutout.add(x + 1.0f);
                            vtxCutout.add(y + 1.0f);
                            vtxCutout.add(z + 0.0f);
                            vtxCutout.add(nrm[0]);
                            vtxCutout.add(nrm[1]);
                            vtxCutout.add(nrm[2]);
                            vtxCutout.add(colBB[0]);
                            vtxCutout.add(colBB[1]);
                            vtxCutout.add(colBB[2]);
                            vtxCutout.add(colBB[3]);
                            vtxCutout.add(bu0);
                            vtxCutout.add(bv0);
                            // indices
                            idxCutout.add(baseIndex + 0);
                            idxCutout.add(baseIndex + 1);
                            idxCutout.add(baseIndex + 2);
                            idxCutout.add(baseIndex + 0);
                            idxCutout.add(baseIndex + 2);
                            idxCutout.add(baseIndex + 3);
                        }

                        // Done with billboard
                        continue;
                    }

                    boolean translucent =
                        (__rtype ==
                                net.vulkanmod.server.pack.RenderType.WATER ||
                            __rtype ==
                            net.vulkanmod.server.pack.RenderType.TRANSLUCENT) ||
                        (__rtype ==
                                net.vulkanmod.server.pack.RenderType.SOLID &&
                            isTranslucentKey(key));
                    if (!translucent && acc.isAir(x, y, z)) continue;

                    // Step B geometry: slabs
                    if (acc instanceof WorldSnapshotAccessor wsB) {
                        byte slabT = wsB.getSlabType(x, y, z);
                        if (slabT == 1 || slabT == 2) {
                            boolean cutoutL = (__rtype ==
                                net.vulkanmod.server.pack.RenderType.CUTOUT);
                            GrowableFloatArray vtx = cutoutL
                                ? vtxCutout
                                : vtxSolid;
                            GrowableIntArray idx = cutoutL
                                ? idxCutout
                                : idxSolid;

                            float sMinY = (slabT == 1) ? 0.0f : 0.5f;
                            float sMaxY = (slabT == 1) ? 0.5f : 1.0f;

                            ServerTextureAtlas atlas =
                                ServerTextureAtlas.getInstance();
                            ServerTextureAtlas.Region rTop =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "top"
                                );
                            ServerTextureAtlas.Region rBottom =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "bottom"
                                );
                            ServerTextureAtlas.Region rSide =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "side"
                                );

                            emitCuboid(
                                vtx,
                                idx,
                                x,
                                y,
                                z,
                                0.0f,
                                sMinY,
                                0.0f,
                                1.0f,
                                sMaxY,
                                1.0f,
                                rTop,
                                rBottom,
                                rSide,
                                rSide,
                                rSide,
                                rSide,
                                acc,
                                baseColor
                            );
                            continue;
                        }
                    }

                    // Step B geometry: straight stairs (approximate L-shape)
                    if (acc instanceof WorldSnapshotAccessor wsS) {
                        byte shape = wsS.getStairShape(x, y, z);
                        if (shape != 0) {
                            String k = "vulkanmod.loggedStairsNonStraight";
                            if (!"1".equals(System.getProperty(k))) {
                                System.out.println(
                                    "[MeshBuilder] Stairs shape INNER/OUTER not implemented; falling back to STRAIGHT."
                                );
                                System.setProperty(k, "1");
                            }
                        }
                        // Heuristic: detect stairs by presence of facing/half meta when no slab meta
                        byte slabT = wsS.getSlabType(x, y, z);
                        if (
                            (__blockId != null &&
                                __blockId.endsWith("_stairs")) &&
                            slabT == 0
                        ) {
                            boolean cutoutL = (__rtype ==
                                net.vulkanmod.server.pack.RenderType.CUTOUT);
                            GrowableFloatArray vtx = cutoutL
                                ? vtxCutout
                                : vtxSolid;
                            GrowableIntArray idx = cutoutL
                                ? idxCutout
                                : idxSolid;

                            byte facing = wsS.getStairFacing(x, y, z); // 0=N,1=E,2=S,3=W
                            byte half = wsS.getStairHalf(x, y, z); // 0=bottom,1=top

                            float y0 = (half == 0) ? 0.0f : 0.5f;
                            float y1 = (half == 0) ? 0.5f : 1.0f;

                            ServerTextureAtlas atlas =
                                ServerTextureAtlas.getInstance();
                            ServerTextureAtlas.Region rTop =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "top"
                                );
                            ServerTextureAtlas.Region rBottom =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "bottom"
                                );
                            ServerTextureAtlas.Region rSide =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "side"
                                );

                            // Lower/upper slab across full footprint
                            emitCuboid(
                                vtx,
                                idx,
                                x,
                                y,
                                z,
                                0.0f,
                                y0,
                                0.0f,
                                1.0f,
                                y1,
                                1.0f,
                                rTop,
                                rBottom,
                                rSide,
                                rSide,
                                rSide,
                                rSide,
                                acc,
                                baseColor
                            );

                            // Back half riser: depends on facing, occupies other half height
                            float minX2 = 0f,
                                maxX2 = 1f,
                                minZ2 = 0f,
                                maxZ2 = 1f;
                            if (facing == 0) {
                                // NORTH: back is south half (z 0.5..1)
                                minZ2 = 0.5f;
                                maxZ2 = 1.0f;
                            } else if (facing == 2) {
                                // SOUTH: back is north half (z 0..0.5)
                                minZ2 = 0.0f;
                                maxZ2 = 0.5f;
                            } else if (facing == 1) {
                                // EAST: back is west half (x 0..0.5)
                                minX2 = 0.0f;
                                maxX2 = 0.5f;
                            } else {
                                // WEST: back is east half (x 0.5..1)
                                minX2 = 0.5f;
                                maxX2 = 1.0f;
                            }
                            float ry0 = (half == 0) ? 0.5f : 0.0f;
                            float ry1 = (half == 0) ? 1.0f : 0.5f;

                            emitCuboid(
                                vtx,
                                idx,
                                x,
                                y,
                                z,
                                minX2,
                                ry0,
                                minZ2,
                                maxX2,
                                ry1,
                                maxZ2,
                                rTop,
                                rBottom,
                                rSide,
                                rSide,
                                rSide,
                                rSide,
                                acc,
                                baseColor
                            );
                            continue;
                        }
                    }

                    // Step B geometry: panes (glass panes/iron bars)
                    if (acc instanceof WorldSnapshotAccessor wsP) {
                        boolean isPane =
                            wsP.isPaneConnectedN(x, y, z) ||
                            wsP.isPaneConnectedE(x, y, z) ||
                            wsP.isPaneConnectedS(x, y, z) ||
                            wsP.isPaneConnectedW(x, y, z);
                        // Also render isolated post (no connections)
                        if (
                            __blockId != null &&
                            (__blockId.endsWith("_glass_pane") ||
                                __blockId.endsWith("iron_bars"))
                        ) {
                            GrowableFloatArray vtx = vtxCutout;
                            GrowableIntArray idx = idxCutout;
                            ServerTextureAtlas atlas =
                                ServerTextureAtlas.getInstance();
                            ServerTextureAtlas.Region rTop =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "top"
                                );
                            ServerTextureAtlas.Region rBottom =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "bottom"
                                );
                            ServerTextureAtlas.Region rSide =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "side"
                                );

                            float t = 0.125f;
                            float c0 = 0.5f - t * 0.5f;
                            float c1 = 0.5f + t * 0.5f;

                            // Center post
                            emitCuboid(
                                vtx,
                                idx,
                                x,
                                y,
                                z,
                                c0,
                                0.0f,
                                c0,
                                c1,
                                1.0f,
                                c1,
                                rTop,
                                rBottom,
                                rSide,
                                rSide,
                                rSide,
                                rSide,
                                acc,
                                baseColor
                            );

                            // Arms based on connectivity
                            if (wsP.isPaneConnectedN(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    c0,
                                    0.0f,
                                    0.0f,
                                    c1,
                                    1.0f,
                                    c0,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsP.isPaneConnectedS(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    c0,
                                    0.0f,
                                    c1,
                                    c1,
                                    1.0f,
                                    1.0f,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsP.isPaneConnectedW(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    0.0f,
                                    0.0f,
                                    c0,
                                    c0,
                                    1.0f,
                                    c1,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsP.isPaneConnectedE(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    c1,
                                    0.0f,
                                    c0,
                                    1.0f,
                                    1.0f,
                                    c1,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            // Do not continue; panes should not fallback to cube
                            continue;
                        }
                    }

                    // Step B geometry: fences
                    if (acc instanceof WorldSnapshotAccessor wsF) {
                        boolean connected =
                            wsF.isFenceConnectedN(x, y, z) ||
                            wsF.isFenceConnectedE(x, y, z) ||
                            wsF.isFenceConnectedS(x, y, z) ||
                            wsF.isFenceConnectedW(x, y, z);
                        if (connected) {
                            GrowableFloatArray vtx = vtxCutout;
                            GrowableIntArray idx = idxCutout;
                            ServerTextureAtlas atlas =
                                ServerTextureAtlas.getInstance();
                            ServerTextureAtlas.Region rTop =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "top"
                                );
                            ServerTextureAtlas.Region rBottom =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "bottom"
                                );
                            ServerTextureAtlas.Region rSide =
                                atlas.getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "side"
                                );

                            float post = 0.25f;
                            float p0 = 0.5f - post * 0.5f;
                            float p1 = 0.5f + post * 0.5f;

                            // Center post
                            emitCuboid(
                                vtx,
                                idx,
                                x,
                                y,
                                z,
                                p0,
                                0.0f,
                                p0,
                                p1,
                                1.0f,
                                p1,
                                rTop,
                                rBottom,
                                rSide,
                                rSide,
                                rSide,
                                rSide,
                                acc,
                                baseColor
                            );

                            float railT = 0.1875f;
                            float ry0 = 0.375f;
                            float ry1 = 0.625f;

                            if (wsF.isFenceConnectedN(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    p0,
                                    ry0,
                                    0.0f,
                                    p1,
                                    ry1,
                                    p0,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsF.isFenceConnectedS(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    p0,
                                    ry0,
                                    p1,
                                    p1,
                                    ry1,
                                    1.0f,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsF.isFenceConnectedW(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    0.0f,
                                    ry0,
                                    p0,
                                    p0,
                                    ry1,
                                    p1,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            if (wsF.isFenceConnectedE(x, y, z)) {
                                emitCuboid(
                                    vtx,
                                    idx,
                                    x,
                                    y,
                                    z,
                                    p1,
                                    ry0,
                                    p0,
                                    1.0f,
                                    ry1,
                                    p1,
                                    rTop,
                                    rBottom,
                                    rSide,
                                    rSide,
                                    rSide,
                                    rSide,
                                    acc,
                                    baseColor
                                );
                            }
                            continue;
                        }
                    }

                    // Step B geometry: doors (closed)
                    if (
                        acc instanceof WorldSnapshotAccessor wsD &&
                        __blockId != null &&
                        __blockId.endsWith("_door")
                    ) {
                        boolean isUpper = wsD.isDoorUpperHalf(x, y, z);
                        byte facing = wsD.getDoorFacing(x, y, z);
                        // Treat as closed; thin panel
                        float thickness = 0.125f;
                        float dMinX = 0,
                            dMaxX = 1,
                            dMinZ = 0,
                            dMaxZ = 1;
                        if (facing == 0 || facing == 2) {
                            // NORTH/SOUTH -> thickness along Z
                            dMinZ = 0.5f - thickness * 0.5f;
                            dMaxZ = 0.5f + thickness * 0.5f;
                        } else {
                            // EAST/WEST -> thickness along X
                            dMinX = 0.5f - thickness * 0.5f;
                            dMaxX = 0.5f + thickness * 0.5f;
                        }
                        GrowableFloatArray vtx = (__rtype ==
                                net.vulkanmod.server.pack.RenderType.CUTOUT)
                            ? vtxCutout
                            : vtxSolid;
                        GrowableIntArray idx = (__rtype ==
                                net.vulkanmod.server.pack.RenderType.CUTOUT)
                            ? idxCutout
                            : idxSolid;

                        ServerTextureAtlas atlas =
                            ServerTextureAtlas.getInstance();
                        ServerTextureAtlas.Region rTop =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "top"
                            );
                        ServerTextureAtlas.Region rBottom =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "bottom"
                            );
                        ServerTextureAtlas.Region rNorth =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "north"
                            );
                        ServerTextureAtlas.Region rSouth =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "south"
                            );
                        ServerTextureAtlas.Region rWest =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "west"
                            );
                        ServerTextureAtlas.Region rEast =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "east"
                            );

                        emitCuboid(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            dMinX,
                            0.0f,
                            dMinZ,
                            dMaxX,
                            1.0f,
                            dMaxZ,
                            rTop,
                            rBottom,
                            rNorth,
                            rSouth,
                            rWest,
                            rEast,
                            acc,
                            baseColor
                        );
                        continue;
                    }

                    // Step B geometry: trapdoors (closed)
                    if (
                        acc instanceof WorldSnapshotAccessor wsT &&
                        __blockId != null &&
                        __blockId.endsWith("_trapdoor")
                    ) {
                        boolean topHalf = wsT.isTrapdoorTopHalf(x, y, z);
                        float thickness = 0.125f;
                        float y0t = topHalf ? (1.0f - thickness) : 0.0f;
                        float y1t = topHalf ? 1.0f : thickness;

                        GrowableFloatArray vtx = vtxCutout;
                        GrowableIntArray idx = idxCutout;

                        ServerTextureAtlas atlas =
                            ServerTextureAtlas.getInstance();
                        ServerTextureAtlas.Region rTop =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "top"
                            );
                        ServerTextureAtlas.Region rBottom =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "bottom"
                            );
                        ServerTextureAtlas.Region rSide =
                            atlas.getRegionForBlockFaceByBlockId(
                                __blockId,
                                "side"
                            );

                        emitCuboid(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            0.0f,
                            y0t,
                            0.0f,
                            1.0f,
                            y1t,
                            1.0f,
                            rTop,
                            rBottom,
                            rSide,
                            rSide,
                            rSide,
                            rSide,
                            acc,
                            baseColor
                        );
                        continue;
                    }

                    // Basic non-cube geometry stubs (rails)
                    // Emit a flat quad slightly above the block to represent straight rails (N/S or E/W)
                    if (acc instanceof WorldSnapshotAccessor wsRail) {
                        byte railShape = wsRail.getRailShape(x, y, z); // 0=other,1=NORTH_SOUTH,2=EAST_WEST
                        if (railShape != 0) {
                            // Use CUTOUT layer for rails
                            GrowableFloatArray vtx = vtxCutout;
                            GrowableIntArray idx = idxCutout;

                            // Sample top-face texture via blockId
                            ServerTextureAtlas.Region uvR =
                                ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                    __blockId,
                                    "top"
                                );

                            // Normal straight up; use flat lighting (no directional shading)
                            set3(nrm, 0, 1, 0);
                            float[] colRail = applyFlatLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            );

                            // Slightly above Y to avoid z-fighting with neighbors
                            float yy = y + 0.0625f;

                            // Emit a single horizontal quad over the full cell (stub)
                            final int baseIndex = vtx.countVertices();
                            // v0
                            vtx.add(x + 0.0f);
                            vtx.add(yy);
                            vtx.add(z + 0.0f);
                            vtx.add(nrm[0]);
                            vtx.add(nrm[1]);
                            vtx.add(nrm[2]);
                            vtx.add(colRail[0]);
                            vtx.add(colRail[1]);
                            vtx.add(colRail[2]);
                            vtx.add(colRail[3]);
                            vtx.add(uvR.u0);
                            vtx.add(uvR.v1);
                            // v1
                            vtx.add(x + 1.0f);
                            vtx.add(yy);
                            vtx.add(z + 0.0f);
                            vtx.add(nrm[0]);
                            vtx.add(nrm[1]);
                            vtx.add(nrm[2]);
                            vtx.add(colRail[0]);
                            vtx.add(colRail[1]);
                            vtx.add(colRail[2]);
                            vtx.add(colRail[3]);
                            vtx.add(uvR.u1);
                            vtx.add(uvR.v1);
                            // v2
                            vtx.add(x + 1.0f);
                            vtx.add(yy);
                            vtx.add(z + 1.0f);
                            vtx.add(nrm[0]);
                            vtx.add(nrm[1]);
                            vtx.add(nrm[2]);
                            vtx.add(colRail[0]);
                            vtx.add(colRail[1]);
                            vtx.add(colRail[2]);
                            vtx.add(colRail[3]);
                            vtx.add(uvR.u1);
                            vtx.add(uvR.v0);
                            // v3
                            vtx.add(x + 0.0f);
                            vtx.add(yy);
                            vtx.add(z + 1.0f);
                            vtx.add(nrm[0]);
                            vtx.add(nrm[1]);
                            vtx.add(nrm[2]);
                            vtx.add(colRail[0]);
                            vtx.add(colRail[1]);
                            vtx.add(colRail[2]);
                            vtx.add(colRail[3]);
                            vtx.add(uvR.u0);
                            vtx.add(uvR.v0);

                            // indices
                            idx.add(baseIndex + 0);
                            idx.add(baseIndex + 1);
                            idx.add(baseIndex + 2);
                            idx.add(baseIndex + 0);
                            idx.add(baseIndex + 2);
                            idx.add(baseIndex + 3);

                            // Done with rail stub for this block
                            continue;
                        } else {
                            if (
                                __blockId != null && __blockId.contains("rail")
                            ) {
                                String k = "vulkanmod.loggedRailAscending";
                                if (!"1".equals(System.getProperty(k))) {
                                    System.out.println(
                                        "[MeshBuilder] Rails with non-straight/ascending/switch shape detected; falling back to flat rail quad or cube for now."
                                    );
                                    System.setProperty(k, "1");
                                }
                            }
                        }
                    }

                    // Resolve UV region from the server-side texture atlas for this block key
                    ServerTextureAtlas.Region __uv =
                        ServerTextureAtlas.getInstance().getRegionForBlockKey(
                            key
                        );
                    float u0 = __uv.u0,
                        v0 = __uv.v0,
                        u1 = __uv.u1,
                        v1 = __uv.v1;

                    boolean cutout =
                        (__rtype ==
                            net.vulkanmod.server.pack.RenderType.CUTOUT) ||
                        (__rtype ==
                                net.vulkanmod.server.pack.RenderType.SOLID &&
                            isCutoutKey(key));
                    GrowableFloatArray vtx = translucent
                        ? vtxTranslucent
                        : (cutout ? vtxCutout : vtxSolid);
                    GrowableIntArray idx = translucent
                        ? idxTranslucent
                        : (cutout ? idxCutout : idxSolid);

                    // North (-Z)
                    if (
                        isAirOrOOB(acc, x, y, z - 1, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x, y, z - 1)).equals(key))
                    ) {
                        set3(nrm, 0, 0, -1);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            (__rtype ==
                                    net.vulkanmod.server.pack.RenderType.WATER
                                    ? FACE_NZ_WATER
                                    : FACE_NZ),
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // South (+Z)
                    if (
                        isAirOrOOB(acc, x, y, z + 1, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x, y, z + 1)).equals(key))
                    ) {
                        set3(nrm, 0, 0, 1);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            (__rtype ==
                                    net.vulkanmod.server.pack.RenderType.WATER
                                    ? FACE_PZ_WATER
                                    : FACE_PZ),
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // West (-X)
                    if (
                        isAirOrOOB(acc, x - 1, y, z, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x - 1, y, z)).equals(key))
                    ) {
                        set3(nrm, -1, 0, 0);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            (__rtype ==
                                    net.vulkanmod.server.pack.RenderType.WATER
                                    ? FACE_NX_WATER
                                    : FACE_NX),
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // East (+X)
                    if (
                        isAirOrOOB(acc, x + 1, y, z, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x + 1, y, z)).equals(key))
                    ) {
                        set3(nrm, 1, 0, 0);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_side =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "side"
                            );
                        float su0 = __uv_side.u0,
                            sv0 = __uv_side.v0,
                            su1 = __uv_side.u1,
                            sv1 = __uv_side.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            (__rtype ==
                                    net.vulkanmod.server.pack.RenderType.WATER
                                    ? FACE_PX_WATER
                                    : FACE_PX),
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            su0,
                            sv0,
                            su1,
                            sv1
                        );
                    }
                    // Top (+Y)
                    if (
                        isAirOrOOB(acc, x, y + 1, z, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x, y + 1, z)).equals(key))
                    ) {
                        set3(nrm, 0, 1, 0);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_t =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "top"
                            );
                        float tu0 = __uv_t.u0,
                            tv0 = __uv_t.v0,
                            tu1 = __uv_t.u1,
                            tv1 = __uv_t.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            (__rtype ==
                                    net.vulkanmod.server.pack.RenderType.WATER
                                    ? FACE_PY_WATER
                                    : FACE_PY),
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            tu0,
                            tv0,
                            tu1,
                            tv1
                        );
                    }
                    // Bottom (-Y)
                    if (
                        isAirOrOOB(acc, x, y - 1, z, minY, maxY) &&
                        !(translucent &&
                            safeKey(acc.getBlockKey(x, y - 1, z)).equals(key))
                    ) {
                        set3(nrm, 0, -1, 0);
                        // Always use per-face atlas region; falls back internally if not defined
                        ServerTextureAtlas.Region __uv_b =
                            ServerTextureAtlas.getInstance().getRegionForBlockFaceByBlockId(
                                __blockId,
                                "bottom"
                            );
                        float bu0 = __uv_b.u0,
                            bv0 = __uv_b.v0,
                            bu1 = __uv_b.u1,
                            bv1 = __uv_b.v1;
                        emitFaceQuad(
                            vtx,
                            idx,
                            x,
                            y,
                            z,
                            FACE_NY,
                            nrm,
                            applyLighting(
                                acc,
                                x,
                                y,
                                z,
                                nrm,
                                baseColor,
                                faceColor
                            ),
                            bu0,
                            bv0,
                            bu1,
                            bv1
                        );
                    }
                }
            }
        }

        // Pack into arrays
        float[] vtxSolidArr = Arrays.copyOf(vtxSolid.data, vtxSolid.size);
        int[] idxSolidArr = Arrays.copyOf(idxSolid.data, idxSolid.size);
        float[] vtxCutoutArr = Arrays.copyOf(vtxCutout.data, vtxCutout.size);
        int[] idxCutoutArr = Arrays.copyOf(idxCutout.data, idxCutout.size);
        float[] vtxTranslucentArr = Arrays.copyOf(
            vtxTranslucent.data,
            vtxTranslucent.size
        );
        int[] idxTranslucentArr = Arrays.copyOf(
            idxTranslucent.data,
            idxTranslucent.size
        );
        // Instrumentation: print sizes prior to RegionMesh.fromArrays to help diagnose overflows
        System.out.println(
            "[MeshBuilder] buildRegionLayered: sizes floats={solid=" +
            vtxSolid.size +
            ", cutout=" +
            vtxCutout.size +
            ", translucent=" +
            vtxTranslucent.size +
            "} indices={solid=" +
            idxSolid.size +
            ", cutout=" +
            idxCutout.size +
            ", translucent=" +
            idxTranslucent.size +
            "} regionChunks=" +
            cfg.regionSizeChunks +
            " y=[" +
            minY +
            "," +
            maxY +
            ")"
        );

        RegionMesh solidMesh = RegionMesh.fromArrays(
            regionChunkX,
            regionChunkZ,
            cfg.regionSizeChunks,
            vtxSolidArr,
            idxSolidArr,
            new float[] { minXf, minYf, minZf, maxXf, maxYf, maxZf },
            version
        );
        RegionMesh cutoutMesh = RegionMesh.fromArrays(
            regionChunkX,
            regionChunkZ,
            cfg.regionSizeChunks,
            vtxCutoutArr,
            idxCutoutArr,
            new float[] { minXf, minYf, minZf, maxXf, maxYf, maxZf },
            version
        );
        RegionMesh translucentMesh = RegionMesh.fromArrays(
            regionChunkX,
            regionChunkZ,
            cfg.regionSizeChunks,
            vtxTranslucentArr,
            idxTranslucentArr,
            new float[] { minXf, minYf, minZf, maxXf, maxYf, maxZf },
            version
        );
        return new LayeredRegionMesh(solidMesh, cutoutMesh, translucentMesh);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static float powf(float v, float g) {
        if (g == 1.0f) return v;
        return (float) Math.pow(Math.max(v, 0.0f), g);
    }

    private static float[] norm3(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l <= 0.0f) return new float[] { 0, 1, 0 };
        return new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    private static void norm3InPlace(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l <= 0.0f) {
            v[0] = 0;
            v[1] = 1;
            v[2] = 0;
        } else {
            v[0] /= l;
            v[1] /= l;
            v[2] /= l;
        }
    }

    private static void set3(float[] v, float x, float y, float z) {
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    private static boolean isAirOrOOB(
        BlockAccessor acc,
        int x,
        int y,
        int z,
        int minY,
        int maxY
    ) {
        if (y < minY || y >= maxY) return true;
        return acc.isAir(x, y, z);
    }

    // =============================================================================================
    // Simple growable arrays for vertex and index data.
    // Vertex stride = 10 floats (pos3 + normal3 + color4)
    // =============================================================================================

    private static final class GrowableFloatArray {

        float[] data;
        int size; // floats
        int vertices; // number of vertices written

        GrowableFloatArray(int cap) {
            data = new float[Math.max(cap, 1024)];
            size = 0;
            vertices = 0;
        }

        void add(float f) {
            ensure(1);
            data[size++] = f;
        }

        void ensure(int extra) {
            int need = size + extra;
            if (need > data.length) {
                int newCap = Math.max(need, data.length + (data.length >>> 1));
                data = Arrays.copyOf(data, newCap);
            }
        }

        int countVertices() {
            // Vertex stride is 12 floats (pos3 + normal3 + color4 + uv2)
            // Compute based on floats written to avoid separate tracking bugs
            return size / 12;
        }

        // called only by emitFaceQuad (4 vertices at once)
        void incrementVertices(int add) {
            vertices += add;
        }
    }

    private static final class GrowableIntArray {

        int[] data;
        int size;

        GrowableIntArray(int cap) {
            data = new int[Math.max(cap, 1024)];
            size = 0;
        }

        void add(int v) {
            ensure(1);
            data[size++] = v;
        }

        void ensure(int extra) {
            int need = size + extra;
            if (need > data.length) {
                int newCap = Math.max(need, data.length + (data.length >>> 1));
                data = Arrays.copyOf(data, newCap);
            }
        }
    }

    // Modify emitFaceQuad to correctly increment vertex count (4 verts per face)
    private void emitFaceQuad(
        GrowableFloatArray vtx,
        GrowableIntArray idx,
        int bx,
        int by,
        int bz,
        float[][] corners,
        float[] normal,
        float[] color,
        boolean incrementVertexCountMarker
    ) {
        // NOT USED - kept for reference (see main emitFaceQuad above)
    }

    // Overload with vertex count tracking inside
}
