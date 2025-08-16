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

    public MeshBuilder() {
        this(new Config());
    }

    public MeshBuilder(Config config) {
        this.cfg = config;
        if (cfg.sunDirection == null) cfg.sunDirection = norm3(
            new float[] { 0, 1, 0 }
        );
        norm3InPlace(cfg.sunDirection);
        if (cfg.colorLUT == null) cfg.colorLUT = defaultColorLUT();
        if (
            cfg.defaultColor == null || cfg.defaultColor.length < 4
        ) cfg.defaultColor = new float[] { 1, 1, 1, 1 };
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
                            u0,
                            v0,
                            u1,
                            v1
                        );
                    }
                    // South (+Z)
                    if (isAirOrOOB(acc, x, y, z + 1, minY, maxY)) {
                        set3(nrm, 0, 0, 1);
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
                            u0,
                            v0,
                            u1,
                            v1
                        );
                    }
                    // West (-X)
                    if (isAirOrOOB(acc, x - 1, y, z, minY, maxY)) {
                        set3(nrm, -1, 0, 0);
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
                            u0,
                            v0,
                            u1,
                            v1
                        );
                    }
                    // East (+X)
                    if (isAirOrOOB(acc, x + 1, y, z, minY, maxY)) {
                        set3(nrm, 1, 0, 0);
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
                            u0,
                            v0,
                            u1,
                            v1
                        );
                    }
                    // Top (+Y)
                    if (isAirOrOOB(acc, x, y + 1, z, minY, maxY)) {
                        set3(nrm, 0, 1, 0);
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
                            u0,
                            v0,
                            u1,
                            v1
                        );
                    }
                    // Bottom (-Y)
                    if (isAirOrOOB(acc, x, y - 1, z, minY, maxY)) {
                        set3(nrm, 0, -1, 0);
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
                            u0,
                            v0,
                            u1,
                            v1
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
            // color (4)
            vtx.add(color[0]);
            vtx.add(color[1]);
            vtx.add(color[2]);
            vtx.add(color[3]);

            // uv (2) — choose mapping based on dominant axis of normal
            float tu, tv;
            // Determine face by normal
            if (normal[2] == -1.0f) {
                // -Z (north)
                tu = cx;
                tv = 1.0f - cy;
            } else if (normal[2] == 1.0f) {
                // +Z (south)
                tu = 1.0f - cx;
                tv = 1.0f - cy;
            } else if (normal[0] == -1.0f) {
                // -X (west)
                tu = cz;
                tv = 1.0f - cy;
            } else if (normal[0] == 1.0f) {
                // +X (east)
                tu = 1.0f - cz;
                tv = 1.0f - cy;
            } else if (normal[1] == 1.0f) {
                // +Y (top)
                tu = cx;
                tv = cz;
            } else {
                // -Y (bottom)
                tu = cx;
                tv = 1.0f - cz;
            }

            // Remap into atlas region
            float uu = u0 + tu * du;
            float vv = v0 + tv * dv;
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
        float shade = cfg.ambientMin + cfg.sunIntensity * ndotl;
        shade = clamp01(shade);

        float lum = clamp01(shade * (0.5f + 0.5f * lmix)); // simple remap

        outColor[0] = powf(baseColor[0] * lum, cfg.gamma);
        outColor[1] = powf(baseColor[1] * lum, cfg.gamma);
        outColor[2] = powf(baseColor[2] * lum, cfg.gamma);
        outColor[3] = baseColor[3];
        return outColor;
    }

    private static Map<String, float[]> defaultColorLUT() {
        Map<String, float[]> m = new HashMap<>();
        // Simple distinctive colors (linear-ish, assuming further gamma at display)
        m.put("grass", rgba(0x4CAF50));
        m.put("stone", rgba(0x9E9E9E));
        m.put("dirt", rgba(0x795548));
        m.put("leaves", rgba(0x2E7D32));
        m.put("wood", rgba(0x8D6E63));
        m.put("sand", rgba(0xE0C085));
        m.put("water", rgba(0x1E88E5));
        m.put("glass", rgba(0x90CAF9));
        m.put("sandstone", rgba(0xD7CCC8));
        m.put("gravel", rgba(0xB0BEC5));
        m.put("clay", rgba(0x90A4AE));
        m.put("coal", rgba(0x424242));
        m.put("iron", rgba(0xB0BEC5));
        m.put("gold", rgba(0xFBC02D));
        m.put("diamond", rgba(0x26C6DA));
        m.put("redstone", rgba(0xEF5350));
        m.put("lapis", rgba(0x1A237E));
        m.put("obsidian", rgba(0x2C2D3A));
        // Fallback logic will use default color if key missing
        return m;
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
