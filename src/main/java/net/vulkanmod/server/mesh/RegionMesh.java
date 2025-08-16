package net.vulkanmod.server.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * RegionMesh is a simple POJO that holds VBO-like buffers and metadata for a meshed region
 * of the server world. It is intentionally free of client-only dependencies and is safe to
 * use on a dedicated server.
 *
 * Typical usage:
 * - Build arrays of interleaved vertex attributes and indices for a region
 * - Create a RegionMesh via one of the factory methods (e.g., fromArrays or fromBuffers)
 * - Cache and reuse across frames until the underlying world data changes
 *
 * Buffer layout (interleaved):
 * - Each vertex is packed as one of:
 *   - Without UVs: [px, py, pz, nx, ny, nz, r, g, b, a]
 *   - With UVs:    [px, py, pz, nx, ny, nz, r, g, b, a, u, v]
 *   where p* are position floats, n* are normal floats, r,g,b,a are color components stored as floats in [0,1],
 *   and u,v are texture coordinates in [0,1].
 *
 * The exact layout can evolve, but this interleaved form is sufficient for a basic
 * colored, lit, non-textured pipeline in headless off-screen rendering.
 */
public final class RegionMesh {

    // Interleaved layout constants
    public static final int POS_COMPONENTS = 3;
    public static final int NORMAL_COMPONENTS = 3;
    public static final int COLOR_COMPONENTS = 4;
    public static final int UV_COMPONENTS = 2;
    // Backwards-compatible default (no UVs)
    public static final int VERTEX_STRIDE_FLOATS =
        POS_COMPONENTS + NORMAL_COMPONENTS + COLOR_COMPONENTS; // 10
    // Optional stride when UVs are present
    public static final int VERTEX_STRIDE_FLOATS_UV =
        POS_COMPONENTS + NORMAL_COMPONENTS + COLOR_COMPONENTS + UV_COMPONENTS; // 12

    // Region identity in chunk coordinates (top-left or any agreed convention)
    private final int regionChunkX;
    private final int regionChunkZ;
    private final int regionSizeChunks; // e.g., 8 for an 8x8 chunk region

    // Mesh data
    private final FloatBuffer interleavedVertices; // direct, native order
    private final IntBuffer indices; // direct, native order
    private final int vertexCount; // number of vertices (not floats)
    private final int indexCount; // number of indices

    // Simple bounding box in world coordinates for culling (optional)
    private final float minX, minY, minZ;
    private final float maxX, maxY, maxZ;

    // Metadata for caching/invalidation
    private final long buildTimestampNanos;
    private final long version; // increment when rebuilds occur

    private RegionMesh(
        int regionChunkX,
        int regionChunkZ,
        int regionSizeChunks,
        FloatBuffer interleavedVertices,
        IntBuffer indices,
        int vertexCount,
        int indexCount,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        long buildTimestampNanos,
        long version
    ) {
        this.regionChunkX = regionChunkX;
        this.regionChunkZ = regionChunkZ;
        this.regionSizeChunks = regionSizeChunks;

        this.interleavedVertices = interleavedVertices;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;

        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        this.buildTimestampNanos = buildTimestampNanos;
        this.version = version;
    }

    /**
     * Create a RegionMesh from plain Java arrays. This method allocates direct NIO buffers
     * and copies the provided data, so the input arrays can be reused or discarded afterwards.
     *
     * @param regionChunkX   region origin X (in chunk coordinates)
     * @param regionChunkZ   region origin Z (in chunk coordinates)
     * @param regionSizeChunks size of the region in chunks on each axis (e.g., 8 means 8x8)
     * @param interleavedVertexArray packed vertex data (stride = {@link #VERTEX_STRIDE_FLOATS})
     * @param indexArray     triangle indices
     * @param aabbMinMax     axis-aligned bounding box [minX,minY,minZ,maxX,maxY,maxZ] in world coords
     * @param version        version/invalidation counter
     * @return a new RegionMesh with direct buffers
     */
    public static RegionMesh fromArrays(
        int regionChunkX,
        int regionChunkZ,
        int regionSizeChunks,
        float[] interleavedVertexArray,
        int[] indexArray,
        float[] aabbMinMax,
        long version
    ) {
        if (interleavedVertexArray == null) {
            throw new IllegalArgumentException(
                "interleavedVertexArray is null"
            );
        }
        // Accept either legacy (10 floats/vertex) or UV-enabled (12 floats/vertex) layouts
        final boolean fitsNoUv =
            (interleavedVertexArray.length % VERTEX_STRIDE_FLOATS) == 0;
        final boolean fitsUv =
            (interleavedVertexArray.length % VERTEX_STRIDE_FLOATS_UV) == 0;
        if (!(fitsNoUv || fitsUv)) {
            throw new IllegalArgumentException(
                "interleavedVertexArray length must be a multiple of " +
                VERTEX_STRIDE_FLOATS +
                " (no UV) or " +
                VERTEX_STRIDE_FLOATS_UV +
                " (with UV)"
            );
        }
        if (indexArray == null) {
            throw new IllegalArgumentException("indexArray is null");
        }
        if (aabbMinMax == null || aabbMinMax.length != 6) {
            throw new IllegalArgumentException(
                "aabbMinMax must be length 6 [minX,minY,minZ,maxX,maxY,maxZ]"
            );
        }

        int stride = fitsUv ? VERTEX_STRIDE_FLOATS_UV : VERTEX_STRIDE_FLOATS;
        int vertexCount = interleavedVertexArray.length / stride;
        int indexCount = indexArray.length;

        // Allocate direct buffers in native order
        FloatBuffer vbo = directFloatBuffer(interleavedVertexArray.length);
        vbo.put(interleavedVertexArray).flip();

        IntBuffer ibo = directIntBuffer(indexArray.length);
        ibo.put(indexArray).flip();

        long ts = System.nanoTime();
        return new RegionMesh(
            regionChunkX,
            regionChunkZ,
            regionSizeChunks,
            vbo,
            ibo,
            vertexCount,
            indexCount,
            aabbMinMax[0],
            aabbMinMax[1],
            aabbMinMax[2],
            aabbMinMax[3],
            aabbMinMax[4],
            aabbMinMax[5],
            ts,
            version
        );
    }

    /**
     * Create a RegionMesh from already prepared direct buffers.
     * Buffers must be direct, in native byte order, and positioned/limited for reading.
     */
    public static RegionMesh fromBuffers(
        int regionChunkX,
        int regionChunkZ,
        int regionSizeChunks,
        FloatBuffer interleavedVertices,
        IntBuffer indices,
        int vertexCount,
        int indexCount,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        long version
    ) {
        if (interleavedVertices == null || !interleavedVertices.isDirect()) {
            throw new IllegalArgumentException(
                "interleavedVertices must be a non-null direct FloatBuffer"
            );
        }
        if (indices == null || !indices.isDirect()) {
            throw new IllegalArgumentException(
                "indices must be a non-null direct IntBuffer"
            );
        }
        long ts = System.nanoTime();
        return new RegionMesh(
            regionChunkX,
            regionChunkZ,
            regionSizeChunks,
            interleavedVertices,
            indices,
            vertexCount,
            indexCount,
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            ts,
            version
        );
    }

    public int getRegionChunkX() {
        return regionChunkX;
    }

    public int getRegionChunkZ() {
        return regionChunkZ;
    }

    public int getRegionSizeChunks() {
        return regionSizeChunks;
    }

    public FloatBuffer getInterleavedVertices() {
        return interleavedVertices;
    }

    public IntBuffer getIndices() {
        return indices;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public float getMinX() {
        return minX;
    }

    public float getMinY() {
        return minY;
    }

    public float getMinZ() {
        return minZ;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxY() {
        return maxY;
    }

    public float getMaxZ() {
        return maxZ;
    }

    public long getBuildTimestampNanos() {
        return buildTimestampNanos;
    }

    public long getVersion() {
        return version;
    }

    /**
     * @return size in bytes of the interleaved vertex buffer backing storage (remaining).
     */
    public int getVertexBufferSizeBytes() {
        return interleavedVertices.remaining() * Float.BYTES;
    }

    /**
     * @return size in bytes of the index buffer backing storage (remaining).
     */
    public int getIndexBufferSizeBytes() {
        return indices.remaining() * Integer.BYTES;
    }

    /**
     * Convenience to know if this region has any geometry.
     */
    public boolean isEmpty() {
        return vertexCount == 0 || indexCount == 0;
    }

    private static FloatBuffer directFloatBuffer(int floats) {
        ByteBuffer bb = ByteBuffer.allocateDirect(floats * Float.BYTES).order(
            ByteOrder.nativeOrder()
        );
        return bb.asFloatBuffer();
    }

    private static IntBuffer directIntBuffer(int ints) {
        ByteBuffer bb = ByteBuffer.allocateDirect(ints * Integer.BYTES).order(
            ByteOrder.nativeOrder()
        );
        return bb.asIntBuffer();
    }

    @Override
    public String toString() {
        return (
            "RegionMesh{" +
            "regionChunkX=" +
            regionChunkX +
            ", regionChunkZ=" +
            regionChunkZ +
            ", regionSizeChunks=" +
            regionSizeChunks +
            ", vertexCount=" +
            vertexCount +
            ", indexCount=" +
            indexCount +
            ", aabb=(" +
            minX +
            "," +
            minY +
            "," +
            minZ +
            " -> " +
            maxX +
            "," +
            maxY +
            "," +
            maxZ +
            ")" +
            ", version=" +
            version +
            '}'
        );
    }

    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + regionChunkX;
        h = 31 * h + regionChunkZ;
        h = 31 * h + regionSizeChunks;
        h = 31 * h + (int) (version ^ (version >>> 32));
        return h;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RegionMesh other)) return false;
        return (
            this.regionChunkX == other.regionChunkX &&
            this.regionChunkZ == other.regionChunkZ &&
            this.regionSizeChunks == other.regionSizeChunks &&
            this.version == other.version
        );
    }
}
