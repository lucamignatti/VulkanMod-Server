package net.vulkanmod.server.mesh;

import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * WorldSnapshotAccessor captures a read-only snapshot of a rectangular server-world region
 * and exposes it via the MeshBuilder.BlockAccessor interface, without any client-side
 * dependencies. The snapshot precomputes air/solid, simple block "keys" for color lookup,
 * and sky/block light values to allow meshing on worker threads without hitting world APIs.
 *
 * Usage:
 *   WorldSnapshotAccessor snap = WorldSnapshotAccessor.capture(world, minX, minY, minZ, maxX, maxY, maxZ);
 *   MeshBuilder.BlockAccessor acc = snap; // pass to MeshBuilder.buildRegion(...)
 */
public final class WorldSnapshotAccessor implements MeshBuilder.BlockAccessor {

    // Discrete key codes for LUT categories to avoid storing many Strings
    // Mapping to MeshBuilder LUT names happens in keyName(byte)
    private static final byte KEY_DEFAULT = 0;
    private static final byte KEY_GRASS = 1;
    private static final byte KEY_STONE = 2;
    private static final byte KEY_DIRT = 3;
    private static final byte KEY_LEAVES = 4;
    private static final byte KEY_WOOD = 5;
    private static final byte KEY_SAND = 6;
    private static final byte KEY_WATER = 7;
    private static final byte KEY_GLASS = 8;
    private static final byte KEY_SANDSTONE = 9;
    private static final byte KEY_GRAVEL = 10;
    private static final byte KEY_CLAY = 11;
    private static final byte KEY_COAL = 12;
    private static final byte KEY_IRON = 13;
    private static final byte KEY_GOLD = 14;
    private static final byte KEY_DIAMOND = 15;
    private static final byte KEY_REDSTONE = 16;
    private static final byte KEY_LAPIS = 17;
    private static final byte KEY_OBSIDIAN = 18;
    // Flora / non-full blocks and light sources (billboard/cutout families)
    private static final byte KEY_SHORT_GRASS = 19;
    private static final byte KEY_TALL_GRASS = 20;
    private static final byte KEY_FERN = 21;
    private static final byte KEY_DEAD_BUSH = 22;
    private static final byte KEY_KELP = 23;
    private static final byte KEY_TORCH = 24;
    private static final byte KEY_ICE = 25;
    private static final byte KEY_HONEY = 26;
    private static final byte KEY_CACTUS = 27;
    private static final byte KEY_PLANKS = 28;
    private static final byte KEY_BEDROCK = 29;

    // Snapshot bounds in world block coordinates (min inclusive, max exclusive)
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    // World vertical build height range
    private final int worldMinY, worldMaxY;

    // Dimensions
    private final int sizeX, sizeY, sizeZ;

    // Snapshot arrays
    private final boolean[] air; // true if air/transparent for visibility check
    private final byte[] sky; // 0..15
    private final byte[] blk; // 0..15
    private final byte[] key; // category key code
    private String[] blockIds; // namespaced block id per voxel (e.g., "minecraft:stone")
    // Step B: compact per-voxel properties/connectivity (bitfields/bytes)
    // Slab type: 0=none, 1=bottom, 2=top, 3=double
    private byte[] slabType;
    // Stairs meta bits: 0-1=facing (0=N,1=E,2=S,3=W), 2=half (0=bottom,1=top), 3=shape(0=straight,1=other)
    private byte[] stairsMeta;
    // Panes connectivity bitmask: bit0=N, bit1=E, bit2=S, bit3=W
    private byte[] paneConn;
    // Fences connectivity bitmask: bit0=N, bit1=E, bit2=S, bit3=W
    private byte[] fenceConn;
    // Rails: 0=other/unknown, 1=NORTH_SOUTH, 2=EAST_WEST
    private byte[] railMeta;
    // Doors: bits 0-1=facing(0=N,1=E,2=S,3=W), bit2=half(1=upper), bit3=open(1=true)
    private byte[] doorMeta;
    // Trapdoors: bits 0-1=facing, bit2=half(1=top), bit3=open(1=true)
    private byte[] trapdoorMeta;
    // Coarse-grid biome tints (packed 0xRRGGBB) and grid metadata
    // 'tint' retained for backward compatibility (defaults to grass)
    private int[] tint;
    private int[] tintGrass;
    private int[] tintFoliage;
    private int[] tintWater;
    private int tintStep;
    private int tintW, tintH;

    // Client colormap PNGs (if available). Loaded lazily on first use.
    private static volatile int[] GRASS_MAP = null;
    private static volatile int[] FOLIAGE_MAP = null;
    private static volatile int MAP_W = 0,
        MAP_H = 0;

    // Configurable biome tint grid step. Default 4, override via:
    // - System property: -Dvulkanmod.tintGridStep=8
    // - Environment variable: VULKAN_TINT_GRID_STEP=8
    private static int getTintGridStep() {
        final int def = 4;
        // System property takes precedence
        try {
            String sp = System.getProperty("vulkanmod.tintGridStep");
            if (sp != null && !sp.isEmpty()) {
                int v = Integer.parseInt(sp.trim());
                if (v < 1) v = 1;
                if (v > 64) v = 64;
                return v;
            }
        } catch (Throwable ignored) {}
        // Fallback to environment variable
        try {
            String ev = System.getenv("VULKAN_TINT_GRID_STEP");
            if (ev != null && !ev.isEmpty()) {
                int v = Integer.parseInt(ev.trim());
                if (v < 1) v = 1;
                if (v > 64) v = 64;
                return v;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private WorldSnapshotAccessor(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        int worldMinY,
        int worldMaxY,
        boolean[] air,
        byte[] sky,
        byte[] blk,
        byte[] key
    ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;

        this.sizeX = Math.max(0, maxX - minX);
        this.sizeY = Math.max(0, maxY - minY);
        this.sizeZ = Math.max(0, maxZ - minZ);

        this.air = air;
        this.sky = sky;
        this.blk = blk;
        this.key = key;
    }

    /**
     * Capture a snapshot of the given world region.
     * Bounds are in world block coordinates, min inclusive and max exclusive.
     */
    public static WorldSnapshotAccessor capture(
        ServerLevel world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        // Clamp Y to world limits
        int wMinY = world.getMinBuildHeight();
        int wMaxY = world.getMaxBuildHeight();
        int cMinY = clamp(minY, wMinY, wMaxY);
        int cMaxY = clamp(maxY, wMinY, wMaxY);

        int sx = Math.max(0, maxX - minX);
        int sy = Math.max(0, cMaxY - cMinY);
        int sz = Math.max(0, maxZ - minZ);

        int total = safeMul(safeMul(sx, sy), sz);

        boolean[] air = new boolean[total];
        byte[] sky = new byte[total];
        byte[] blk = new byte[total];
        byte[] key = new byte[total];

        // Iterate region and sample state + light + key
        String[] blockIds = new String[total];
        // Step B arrays
        byte[] slabType = new byte[total];
        byte[] stairsMeta = new byte[total];
        byte[] paneConn = new byte[total];
        byte[] fenceConn = new byte[total];
        byte[] railMeta = new byte[total];
        byte[] doorMeta = new byte[total];
        byte[] trapdoorMeta = new byte[total];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = cMinY; y < cMaxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    int idx = idx(x, y, z, minX, cMinY, minZ, sx, sz);
                    pos.set(x, y, z);

                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();
                    // Store canonical namespaced block id (e.g., "minecraft:stone")
                    {
                        net.minecraft.resources.ResourceLocation rid =
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                block
                            );
                        blockIds[idx] = (rid != null)
                            ? rid.toString()
                            : "minecraft:air";
                    }
                    // Step B: capture block-family properties/connectivity
                    // Slab type
                    if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                        SlabType t = state.getValue(
                            BlockStateProperties.SLAB_TYPE
                        );
                        slabType[idx] = (byte) (t == SlabType.BOTTOM
                                ? 1
                                : (t == SlabType.TOP ? 2 : 3));
                    } else {
                        slabType[idx] = 0;
                    }
                    // Stairs: facing/half/shape (shape collapsed to straight/non-straight)
                    {
                        byte sm = 0;
                        try {
                            if (
                                state.hasProperty(
                                    BlockStateProperties.HORIZONTAL_FACING
                                )
                            ) {
                                net.minecraft.core.Direction d = state.getValue(
                                    BlockStateProperties.HORIZONTAL_FACING
                                );
                                int f = switch (d) {
                                    case NORTH -> 0;
                                    case EAST -> 1;
                                    case SOUTH -> 2;
                                    case WEST -> 3;
                                    default -> 0;
                                };
                                sm |= (byte) (f & 0x3);
                            }
                            if (state.hasProperty(BlockStateProperties.HALF)) {
                                var h = state.getValue(
                                    BlockStateProperties.HALF
                                );
                                // Half.TOP means bit2=1
                                if (
                                    h ==
                                    net.minecraft.world.level.block.state.properties.Half.TOP
                                ) sm |= (1 << 2);
                            }
                            // STAIRS_SHAPE may not exist on non-stair blocks; guard with hasProperty
                            try {
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.STAIRS_SHAPE
                                    )
                                ) {
                                    net.minecraft.world.level.block.state.properties.StairsShape shp =
                                        state.getValue(
                                            BlockStateProperties.STAIRS_SHAPE
                                        );
                                    if (
                                        shp !=
                                        net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT
                                    ) {
                                        sm |= (1 << 3);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                        stairsMeta[idx] = sm;
                    }
                    // Panes connectivity (IronBars / stained glass panes)
                    {
                        boolean isPane =
                            (block instanceof
                                net.minecraft.world.level.block.IronBarsBlock) ||
                            (block instanceof StainedGlassPaneBlock);
                        byte pm = 0;
                        if (isPane) {
                            if (
                                state.hasProperty(BlockStateProperties.NORTH) &&
                                state.getValue(BlockStateProperties.NORTH)
                            ) pm |= 1;
                            if (
                                state.hasProperty(BlockStateProperties.EAST) &&
                                state.getValue(BlockStateProperties.EAST)
                            ) pm |= 2;
                            if (
                                state.hasProperty(BlockStateProperties.SOUTH) &&
                                state.getValue(BlockStateProperties.SOUTH)
                            ) pm |= 4;
                            if (
                                state.hasProperty(BlockStateProperties.WEST) &&
                                state.getValue(BlockStateProperties.WEST)
                            ) pm |= 8;
                        }
                        paneConn[idx] = pm;
                    }
                    // Fences connectivity (FenceBlock)
                    {
                        boolean isFence =
                            block instanceof
                            net.minecraft.world.level.block.FenceBlock;
                        byte fm = 0;
                        if (isFence) {
                            if (
                                state.hasProperty(BlockStateProperties.NORTH) &&
                                state.getValue(BlockStateProperties.NORTH)
                            ) fm |= 1;
                            if (
                                state.hasProperty(BlockStateProperties.EAST) &&
                                state.getValue(BlockStateProperties.EAST)
                            ) fm |= 2;
                            if (
                                state.hasProperty(BlockStateProperties.SOUTH) &&
                                state.getValue(BlockStateProperties.SOUTH)
                            ) fm |= 4;
                            if (
                                state.hasProperty(BlockStateProperties.WEST) &&
                                state.getValue(BlockStateProperties.WEST)
                            ) fm |= 8;
                        }
                        fenceConn[idx] = fm;
                    }
                    // Rails (orientation via neighbor rail presence; avoids relying on SHAPE property)
                    {
                        byte rm = 0;
                        if (
                            block instanceof
                            net.minecraft.world.level.block.BaseRailBlock
                        ) {
                            int cx = x,
                                cy = y,
                                cz = z;
                            boolean ns = false,
                                ew = false;
                            Block n0 = world
                                .getBlockState(pos.set(cx, cy, cz - 1))
                                .getBlock();
                            ns |= (n0 instanceof
                                net.minecraft.world.level.block.BaseRailBlock);
                            Block n1 = world
                                .getBlockState(pos.set(cx, cy, cz + 1))
                                .getBlock();
                            ns |= (n1 instanceof
                                net.minecraft.world.level.block.BaseRailBlock);
                            Block e0 = world
                                .getBlockState(pos.set(cx - 1, cy, cz))
                                .getBlock();
                            ew |= (e0 instanceof
                                net.minecraft.world.level.block.BaseRailBlock);
                            Block e1 = world
                                .getBlockState(pos.set(cx + 1, cy, cz))
                                .getBlock();
                            ew |= (e1 instanceof
                                net.minecraft.world.level.block.BaseRailBlock);
                            pos.set(x, y, z);
                            if (ns && !ew) rm = 1;
                            else if (ew && !ns) rm = 2;
                            else rm = 0;
                        }
                        railMeta[idx] = rm;
                    }
                    // Doors
                    {
                        byte dm = 0;
                        if (
                            block instanceof
                            net.minecraft.world.level.block.DoorBlock
                        ) {
                            try {
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.HORIZONTAL_FACING
                                    )
                                ) {
                                    net.minecraft.core.Direction d =
                                        state.getValue(
                                            BlockStateProperties.HORIZONTAL_FACING
                                        );
                                    int f = switch (d) {
                                        case NORTH -> 0;
                                        case EAST -> 1;
                                        case SOUTH -> 2;
                                        case WEST -> 3;
                                        default -> 0;
                                    };
                                    dm |= (byte) (f & 0x3);
                                }
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.DOUBLE_BLOCK_HALF
                                    )
                                ) {
                                    var h = state.getValue(
                                        BlockStateProperties.DOUBLE_BLOCK_HALF
                                    );
                                    if (
                                        h ==
                                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                                    ) dm |= (1 << 2);
                                }
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.OPEN
                                    ) &&
                                    state.getValue(BlockStateProperties.OPEN)
                                ) {
                                    dm |= (1 << 3);
                                }
                            } catch (Throwable ignored) {}
                        }
                        doorMeta[idx] = dm;
                    }
                    // Trapdoors
                    {
                        byte tm = 0;
                        if (
                            block instanceof
                            net.minecraft.world.level.block.TrapDoorBlock
                        ) {
                            try {
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.HORIZONTAL_FACING
                                    )
                                ) {
                                    net.minecraft.core.Direction d =
                                        state.getValue(
                                            BlockStateProperties.HORIZONTAL_FACING
                                        );
                                    int f = switch (d) {
                                        case NORTH -> 0;
                                        case EAST -> 1;
                                        case SOUTH -> 2;
                                        case WEST -> 3;
                                        default -> 0;
                                    };
                                    tm |= (byte) (f & 0x3);
                                }
                                if (
                                    state.hasProperty(BlockStateProperties.HALF)
                                ) {
                                    var h = state.getValue(
                                        BlockStateProperties.HALF
                                    );
                                    if (
                                        h ==
                                        net.minecraft.world.level.block.state.properties.Half.TOP
                                    ) tm |= (1 << 2);
                                }
                                if (
                                    state.hasProperty(
                                        BlockStateProperties.OPEN
                                    ) &&
                                    state.getValue(BlockStateProperties.OPEN)
                                ) {
                                    tm |= (1 << 3);
                                }
                            } catch (Throwable ignored) {}
                        }
                        trapdoorMeta[idx] = tm;
                    }

                    // Air/transparent check
                    air[idx] = isAirLike(state, block);

                    // Lights (0..15)
                    sky[idx] = (byte) clamp(
                        world.getBrightness(LightLayer.SKY, pos),
                        0,
                        15
                    );
                    blk[idx] = (byte) clamp(
                        world.getBrightness(LightLayer.BLOCK, pos),
                        0,
                        15
                    );

                    // Key category
                    byte kcode = classify(block, state);
                    key[idx] = kcode;
                    // Optional debug: log blocks that fell back to 'default' mapping when enabled via sysprop/env.
                    {
                        boolean __log = false;
                        try {
                            String sp = System.getProperty(
                                "vulkanmod.logUnknownBlocks"
                            );
                            if (sp != null && !sp.isEmpty()) {
                                String s = sp
                                    .trim()
                                    .toLowerCase(java.util.Locale.ROOT);
                                if (
                                    s.equals("1") ||
                                    s.equals("true") ||
                                    s.equals("yes") ||
                                    s.equals("on")
                                ) __log = true;
                            }
                        } catch (Throwable ignored) {}
                        try {
                            String ev = System.getenv(
                                "VULKANMOD_LOG_UNKNOWN_BLOCKS"
                            );
                            if (!__log && ev != null && !ev.isEmpty()) {
                                String s = ev
                                    .trim()
                                    .toLowerCase(java.util.Locale.ROOT);
                                if (
                                    s.equals("1") ||
                                    s.equals("true") ||
                                    s.equals("yes") ||
                                    s.equals("on")
                                ) __log = true;
                            }
                        } catch (Throwable ignored) {}
                        if (__log && kcode == KEY_DEFAULT) {
                            net.minecraft.resources.ResourceLocation rid =
                                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                    block
                                );
                            System.out.println(
                                "[WorldSnapshotAccessor] Unknown block -> default key mapping: " +
                                (rid != null
                                        ? rid.toString()
                                        : String.valueOf(block))
                            );
                        }
                    }
                }
            }
        }

        // Build instance then precompute coarse-grid biome tints
        WorldSnapshotAccessor snap = new WorldSnapshotAccessor(
            minX,
            cMinY,
            minZ,
            maxX,
            cMaxY,
            maxZ,
            wMinY,
            wMaxY,
            air,
            sky,
            blk,
            key
        );

        // Coarse grid step (in blocks). Keep small for reasonable fidelity; configurable via system property/env.
        final int step = getTintGridStep();
        final int sxTot = Math.max(0, maxX - minX);
        final int szTot = Math.max(0, maxZ - minZ);
        final int gx = Math.max(1, (sxTot + step - 1) / step);
        final int gz = Math.max(1, (szTot + step - 1) / step);
        final int yMid = cMinY + (Math.max(0, cMaxY - cMinY) / 2);

        int[] tt = new int[gx * gz];
        int[] tg = new int[gx * gz];
        int[] tf = new int[gx * gz];
        int[] tw = new int[gx * gz];

        // Lazy-load client colormaps (grass/foliage) from Minecraft assets if not yet loaded
        if (GRASS_MAP == null || FOLIAGE_MAP == null) {
            try {
                java.io.InputStream gIn =
                    WorldSnapshotAccessor.class.getClassLoader().getResourceAsStream(
                        "assets/minecraft/textures/colormap/grass.png"
                    );
                java.io.InputStream fIn =
                    WorldSnapshotAccessor.class.getClassLoader().getResourceAsStream(
                        "assets/minecraft/textures/colormap/foliage.png"
                    );
                if (gIn != null) {
                    java.awt.image.BufferedImage img =
                        javax.imageio.ImageIO.read(gIn);
                    if (img != null) {
                        MAP_W = img.getWidth();
                        MAP_H = img.getHeight();
                        GRASS_MAP = img.getRGB(
                            0,
                            0,
                            MAP_W,
                            MAP_H,
                            null,
                            0,
                            MAP_W
                        );
                    }
                    gIn.close();
                }
                if (fIn != null) {
                    java.awt.image.BufferedImage img2 =
                        javax.imageio.ImageIO.read(fIn);
                    if (img2 != null) {
                        if (MAP_W == 0 || MAP_H == 0) {
                            MAP_W = img2.getWidth();
                            MAP_H = img2.getHeight();
                        }
                        FOLIAGE_MAP = img2.getRGB(
                            0,
                            0,
                            img2.getWidth(),
                            img2.getHeight(),
                            null,
                            0,
                            img2.getWidth()
                        );
                    }
                    fIn.close();
                }
            } catch (Throwable __e) {
                GRASS_MAP = null;
                FOLIAGE_MAP = null;
                MAP_W = 0;
                MAP_H = 0;
            }
        }

        for (int j = 0; j < gz; j++) {
            int z0 = minZ + j * step + (step / 2);
            if (z0 >= maxZ) z0 = Math.max(minZ, maxZ - 1);
            for (int i = 0; i < gx; i++) {
                int x0 = minX + i * step + (step / 2);
                if (x0 >= maxX) x0 = Math.max(minX, maxX - 1);

                pos.set(x0, yMid, z0);

                // Compute temperature and downfall once
                float temp = 0.5f;
                float moist = 0.5f;
                int gcol = 0xFFFFFF;
                int fcol = 0xFFFFFF;
                int wcol = 0xFFFFFF;

                try {
                    Holder<Biome> h = world.getBiome(pos);
                    Biome biome = h.value();
                    BiomeSpecialEffects fx = biome.getSpecialEffects();
                    // Water tint from biome special effects (server-only safe)
                    wcol = fx.getWaterColor();

                    // Reflective temperature read for mapping compatibility
                    try {
                        java.lang.reflect.Method m =
                            net.minecraft.world.level.biome
                                .Biome.class.getMethod(
                                "getTemperature",
                                net.minecraft.core.BlockPos.class
                            );
                        Object tv = m.invoke(biome, pos);
                        temp = (tv instanceof Float f)
                            ? f
                            : ((Number) tv).floatValue();
                    } catch (Throwable __t0) {
                        try {
                            java.lang.reflect.Method m2 =
                                net.minecraft.world.level.biome
                                    .Biome.class.getMethod(
                                    "getBaseTemperature"
                                );
                            Object tv2 = m2.invoke(biome);
                            temp = (tv2 instanceof Float f2)
                                ? f2
                                : ((Number) tv2).floatValue();
                        } catch (Throwable __t1) {
                            try {
                                java.lang.reflect.Method m3 =
                                    net.minecraft.world.level.biome
                                        .Biome.class.getMethod("temperature");
                                Object tv3 = m3.invoke(biome);
                                temp = (tv3 instanceof Float f3)
                                    ? f3
                                    : ((Number) tv3).floatValue();
                            } catch (Throwable __t2) {
                                temp = 0.5f;
                            }
                        }
                    }
                    try {
                        java.lang.reflect.Method md =
                            net.minecraft.world.level.biome
                                .Biome.class.getMethod("getDownfall");
                        Object mv = md.invoke(biome);
                        moist = (mv instanceof Float f4)
                            ? f4
                            : ((Number) mv).floatValue();
                    } catch (Throwable __t3) {
                        try {
                            java.lang.reflect.Method md2 =
                                net.minecraft.world.level.biome
                                    .Biome.class.getMethod("downfall");
                            Object mv2 = md2.invoke(biome);
                            moist = (mv2 instanceof Float f5)
                                ? f5
                                : ((Number) mv2).floatValue();
                        } catch (Throwable __t4) {
                            moist = 0.5f;
                        }
                    }

                    // Clamp to [0..1]
                    if (temp < 0f) temp = 0f;
                    else if (temp > 1f) temp = 1f;
                    if (moist < 0f) moist = 0f;
                    else if (moist > 1f) moist = 1f;

                    // Overrides
                    var grass = fx.getGrassColorOverride();
                    var foliage = fx.getFoliageColorOverride();

                    // Grass color: override > colormap > HSV approx
                    if (grass.isPresent()) {
                        gcol = grass.get();
                    } else if (GRASS_MAP != null && MAP_W > 0 && MAP_H > 0) {
                        int ix = (int) (temp * (MAP_W - 1) + 0.5f);
                        int iy = (int) (moist * (MAP_H - 1) + 0.5f);
                        if (ix < 0) ix = 0;
                        if (iy < 0) iy = 0;
                        if (ix >= MAP_W) ix = MAP_W - 1;
                        if (iy >= MAP_H) iy = MAP_H - 1;
                        gcol = GRASS_MAP[iy * MAP_W + ix] & 0xFFFFFF;
                    } else {
                        // HSV approx fallback
                        float hue =
                            100.0f +
                            (moist - 0.5f) * 10.0f +
                            (0.5f - temp) * 20.0f;
                        if (hue < 80f) hue = 80f;
                        else if (hue > 140f) hue = 140f;
                        float sat =
                            0.6f +
                            (moist - 0.5f) * 0.2f -
                            Math.abs(temp - 0.5f) * 0.1f;
                        if (sat < 0.4f) sat = 0.4f;
                        else if (sat > 0.9f) sat = 0.9f;
                        float val =
                            0.7f + (temp - 0.5f) * 0.2f + (moist - 0.5f) * 0.1f;
                        if (val < 0.6f) val = 0.6f;
                        else if (val > 0.95f) val = 0.95f;
                        float c = val * sat;
                        float hh = (hue % 360.0f) / 60.0f;
                        float xx = c * (1.0f - Math.abs((hh % 2.0f) - 1.0f));
                        float r1 = 0f,
                            g1 = 0f,
                            b1 = 0f;
                        int sect = (int) Math.floor(hh);
                        switch (sect) {
                            case 0 -> {
                                r1 = c;
                                g1 = xx;
                                b1 = 0f;
                            }
                            case 1 -> {
                                r1 = xx;
                                g1 = c;
                                b1 = 0f;
                            }
                            case 2 -> {
                                r1 = 0f;
                                g1 = c;
                                b1 = xx;
                            }
                            case 3 -> {
                                r1 = 0f;
                                g1 = xx;
                                b1 = c;
                            }
                            case 4 -> {
                                r1 = xx;
                                g1 = 0f;
                                b1 = c;
                            }
                            default -> {
                                r1 = c;
                                g1 = 0f;
                                b1 = xx;
                            }
                        }
                        float m = val - c;
                        int rr = (int) ((r1 + m) * 255.0f + 0.5f);
                        int gg2 = (int) ((g1 + m) * 255.0f + 0.5f);
                        int bb2 = (int) ((b1 + m) * 255.0f + 0.5f);
                        if (rr < 0) rr = 0;
                        else if (rr > 255) rr = 255;
                        if (gg2 < 0) gg2 = 0;
                        else if (gg2 > 255) gg2 = 255;
                        if (bb2 < 0) bb2 = 0;
                        else if (bb2 > 255) bb2 = 255;
                        gcol = (rr << 16) | (gg2 << 8) | bb2;
                    }

                    // Foliage color: override > colormap > reuse grass HSV approx
                    if (foliage.isPresent()) {
                        fcol = foliage.get();
                    } else if (FOLIAGE_MAP != null && MAP_W > 0 && MAP_H > 0) {
                        int ix = (int) (temp * (MAP_W - 1) + 0.5f);
                        int iy = (int) (moist * (MAP_H - 1) + 0.5f);
                        if (ix < 0) ix = 0;
                        if (iy < 0) iy = 0;
                        if (ix >= MAP_W) ix = MAP_W - 1;
                        if (iy >= MAP_H) iy = MAP_H - 1;
                        fcol = FOLIAGE_MAP[iy * MAP_W + ix] & 0xFFFFFF;
                    } else {
                        fcol = gcol;
                    }
                } catch (Throwable __e) {
                    // Keep defaults (white) on failure
                    gcol = 0xFFFFFF;
                    fcol = 0xFFFFFF;
                    wcol = 0xFFFFFF;
                }

                int __idx = j * gx + i;
                // Legacy combined tint (use grass)
                tt[__idx] = gcol;
                // Separate grids
                tg[__idx] = gcol;
                tf[__idx] = fcol;
                tw[__idx] = wcol;
            }
        }

        snap.tint = tt;
        snap.tintGrass = tg;
        snap.tintFoliage = tf;
        snap.tintWater = tw;
        snap.tintStep = step;
        snap.tintW = gx;
        snap.tintH = gz;

        snap.setBlockIdsArray(blockIds);
        // Step B: attach property/connectivity arrays
        snap.setSlabTypeArray(slabType);
        snap.setStairsMetaArray(stairsMeta);
        snap.setPaneConnArray(paneConn);
        snap.setFenceConnArray(fenceConn);
        snap.setRailMetaArray(railMeta);
        snap.setDoorMetaArray(doorMeta);
        snap.setTrapdoorMetaArray(trapdoorMeta);
        return snap;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        if (!inBounds(x, y, z)) return true;
        return air[idxLocal(x, y, z)];
    }

    @Override
    public String getBlockKey(int x, int y, int z) {
        if (!inBounds(x, y, z)) return "default";
        return keyName(key[idxLocal(x, y, z)]);
    }

    // Internal setter used by capture(...) to attach per-voxel block ids
    private void setBlockIdsArray(String[] blockIds) {
        this.blockIds = blockIds;
    }

    /**
     * Returns the exact namespaced block id for the block at (x,y,z), e.g., "minecraft:stone".
     * Returns "minecraft:air" when out of bounds.
     */
    public String getBlockId(int x, int y, int z) {
        if (!inBounds(x, y, z)) return "minecraft:air";
        return blockIds[idxLocal(x, y, z)];
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 15; // outside region -> treat as bright to encourage face emission
        return sky[idxLocal(x, y, z)] & 0xFF;
    }

    @Override
    public int getBlockLight(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return blk[idxLocal(x, y, z)] & 0xFF;
    }

    // Grass tint accessor (packed 0xRRGGBB). Uses coarse-grid precomputed values.
    public int getGrassTintRGB(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0xFFFFFF;
        if (
            this.tintW <= 0 || this.tintH <= 0 || this.tintStep <= 0
        ) return 0xFFFFFF;
        int ix = (x - this.minX) / this.tintStep;
        int iz = (z - this.minZ) / this.tintStep;
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= this.tintW) ix = this.tintW - 1;
        if (iz >= this.tintH) iz = this.tintH - 1;
        int idx = iz * this.tintW + ix;
        if (
            this.tintGrass != null && idx >= 0 && idx < this.tintGrass.length
        ) return this.tintGrass[idx];
        if (
            this.tint != null && idx >= 0 && idx < this.tint.length
        ) return this.tint[idx];
        return 0xFFFFFF;
    }

    // Foliage tint accessor (packed 0xRRGGBB). Uses coarse-grid precomputed values.
    public int getFoliageTintRGB(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0xFFFFFF;
        if (
            this.tintW <= 0 || this.tintH <= 0 || this.tintStep <= 0
        ) return 0xFFFFFF;
        int ix = (x - this.minX) / this.tintStep;
        int iz = (z - this.minZ) / this.tintStep;
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= this.tintW) ix = this.tintW - 1;
        if (iz >= this.tintH) iz = this.tintH - 1;
        int idx = iz * this.tintW + ix;
        if (
            this.tintFoliage != null &&
            idx >= 0 &&
            idx < this.tintFoliage.length
        ) return this.tintFoliage[idx];
        if (
            this.tint != null && idx >= 0 && idx < this.tint.length
        ) return this.tint[idx];
        return 0xFFFFFF;
    }

    // Water tint accessor (packed 0xRRGGBB). Uses coarse-grid precomputed values (neutral for now).
    public int getWaterTintRGB(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0xFFFFFF;
        if (
            this.tintW <= 0 || this.tintH <= 0 || this.tintStep <= 0
        ) return 0xFFFFFF;
        int ix = (x - this.minX) / this.tintStep;
        int iz = (z - this.minZ) / this.tintStep;
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= this.tintW) ix = this.tintW - 1;
        if (iz >= this.tintH) iz = this.tintH - 1;
        int idx = iz * this.tintW + ix;
        if (
            this.tintWater != null && idx >= 0 && idx < this.tintWater.length
        ) return this.tintWater[idx];
        return 0xFFFFFF;
    }

    // Backward compatibility: biome tint defaults to grass tint
    public int getBiomeTintRGB(int x, int y, int z) {
        return getGrassTintRGB(x, y, z);
    }

    // Offset helpers to avoid repeated addition and bounds checks at call sites
    public boolean isAirOffset(int x, int y, int z, int ox, int oy, int oz) {
        int nx = x + ox,
            ny = y + oy,
            nz = z + oz;
        if (!inBounds(nx, ny, nz)) return true;
        return air[idxLocal(nx, ny, nz)];
    }

    public int getSkyLightOffset(int x, int y, int z, int ox, int oy, int oz) {
        int nx = x + ox,
            ny = y + oy,
            nz = z + oz;
        if (!inBounds(nx, ny, nz)) return 15;
        return sky[idxLocal(nx, ny, nz)] & 0xFF;
    }

    public int getBlockLightOffset(
        int x,
        int y,
        int z,
        int ox,
        int oy,
        int oz
    ) {
        int nx = x + ox,
            ny = y + oy,
            nz = z + oz;
        if (!inBounds(nx, ny, nz)) return 0;
        return blk[idxLocal(nx, ny, nz)] & 0xFF;
    }

    @Override
    public int getMinY() {
        return Math.max(minY, worldMinY);
    }

    @Override
    public int getMaxY() {
        return Math.min(maxY, worldMaxY);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private boolean inBounds(int x, int y, int z) {
        return (
            x >= minX &&
            x < maxX &&
            y >= minY &&
            y < maxY &&
            z >= minZ &&
            z < maxZ
        );
    }

    private int idxLocal(int x, int y, int z) {
        return idx(x, y, z, minX, minY, minZ, sizeX, sizeZ);
    }

    private static int idx(
        int x,
        int y,
        int z,
        int minX,
        int minY,
        int minZ,
        int sizeX,
        int sizeZ
    ) {
        int lx = x - minX;
        int ly = y - minY;
        int lz = z - minZ;
        // Row-major: ((ly * sizeZ) + lz) * sizeX + lx
        return ((ly * sizeZ) + lz) * sizeX + lx;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    private static int safeMul(int a, int b) {
        long r = (long) a * (long) b;
        if (r > Integer.MAX_VALUE) throw new IllegalArgumentException(
            "Snapshot too large"
        );
        return (int) r;
    }

    private static boolean isAirLike(BlockState state, Block block) {
        // Consider "air" for visibility: true air OR fully transparent blocks that shouldn't occlude
        if (state.isAir()) return true;

        // Water and other fluids should not occlude blocks in our basic meshing
        if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) return true;

        // Glass and stained glass are translucent but should not be treated as air-like here,
        // so they can occlude neighbors and emit their own faces correctly (handled as TRANSLUCENT elsewhere).

        // Flora/billboard and torches should not occlude neighboring faces
        if (
            block == Blocks.SHORT_GRASS ||
            block == Blocks.TALL_GRASS ||
            block == Blocks.FERN ||
            block == Blocks.LARGE_FERN ||
            block == Blocks.DEAD_BUSH ||
            block == Blocks.KELP ||
            block == Blocks.KELP_PLANT ||
            block == Blocks.TORCH ||
            block == Blocks.WALL_TORCH ||
            block == Blocks.SOUL_TORCH ||
            block == Blocks.SOUL_WALL_TORCH ||
            block == Blocks.REDSTONE_TORCH ||
            block == Blocks.REDSTONE_WALL_TORCH
        ) {
            return true;
        }

        // Version-tolerant: treat legacy 'minecraft:grass' plant as air-like (older versions)
        {
            Block __grass =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(
                    net.minecraft.resources.ResourceLocation.tryParse(
                        "minecraft:grass"
                    )
                ).orElse(null);
            if (__grass == block) {
                return true;
            }
        }

        // Most transparent blocks (but keep it conservative). Exclude glass variants so they are not treated as air-like.
        if (
            block instanceof TransparentBlock &&
            !(block instanceof SnowLayerBlock) &&
            block != Blocks.GLASS &&
            block != Blocks.TINTED_GLASS &&
            !(block instanceof StainedGlassBlock) &&
            !(block instanceof StainedGlassPaneBlock)
        ) {
            return true;
        }

        // Half slabs that don't fully occupy the cube space: treat as not fully occluding
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = state.getValue(BlockStateProperties.SLAB_TYPE);
            if (t != SlabType.DOUBLE) return true;
        }

        return false;
    }

    private static byte classify(Block block, BlockState state) {
        // Specific blocks
        if (block == Blocks.GRASS_BLOCK) return KEY_GRASS;

        if (
            block == Blocks.DIRT ||
            block == Blocks.COARSE_DIRT ||
            block == Blocks.ROOTED_DIRT ||
            block == Blocks.PODZOL
        ) return KEY_DIRT;

        if (block == Blocks.SAND || block == Blocks.RED_SAND) return KEY_SAND;

        if (
            block == Blocks.SANDSTONE ||
            block == Blocks.CUT_SANDSTONE ||
            block == Blocks.SMOOTH_SANDSTONE
        ) return KEY_SANDSTONE;

        if (block == Blocks.GRAVEL) return KEY_GRAVEL;

        if (block == Blocks.CLAY) return KEY_CLAY;

        if (
            block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
        ) return KEY_COAL;

        if (
            block == Blocks.IRON_ORE ||
            block == Blocks.DEEPSLATE_IRON_ORE ||
            block == Blocks.IRON_BLOCK
        ) return KEY_IRON;

        if (
            block == Blocks.GOLD_ORE ||
            block == Blocks.DEEPSLATE_GOLD_ORE ||
            block == Blocks.GOLD_BLOCK
        ) return KEY_GOLD;

        if (
            block == Blocks.DIAMOND_ORE ||
            block == Blocks.DEEPSLATE_DIAMOND_ORE ||
            block == Blocks.DIAMOND_BLOCK
        ) return KEY_DIAMOND;

        if (
            block == Blocks.REDSTONE_ORE ||
            block == Blocks.DEEPSLATE_REDSTONE_ORE ||
            block == Blocks.REDSTONE_BLOCK
        ) return KEY_REDSTONE;

        if (
            block == Blocks.LAPIS_ORE ||
            block == Blocks.DEEPSLATE_LAPIS_ORE ||
            block == Blocks.LAPIS_BLOCK
        ) return KEY_LAPIS;

        if (block == Blocks.OBSIDIAN) return KEY_OBSIDIAN;

        if (
            block == Blocks.STONE ||
            block == Blocks.COBBLESTONE ||
            block == Blocks.ANDESITE ||
            block == Blocks.DIORITE ||
            block == Blocks.GRANITE ||
            block == Blocks.DEEPSLATE ||
            block == Blocks.TUFF ||
            block == Blocks.BASALT ||
            block == Blocks.BLACKSTONE ||
            block == Blocks.END_STONE ||
            block == Blocks.NETHERRACK
        ) return KEY_STONE;

        if (block instanceof LeavesBlock) return KEY_LEAVES;

        if (
            block == Blocks.OAK_LOG ||
            block == Blocks.SPRUCE_LOG ||
            block == Blocks.BIRCH_LOG ||
            block == Blocks.JUNGLE_LOG ||
            block == Blocks.ACACIA_LOG ||
            block == Blocks.DARK_OAK_LOG ||
            block == Blocks.MANGROVE_LOG ||
            block == Blocks.CHERRY_LOG ||
            block == Blocks.BAMBOO_BLOCK ||
            block == Blocks.CRIMSON_STEM ||
            block == Blocks.WARPED_STEM
        ) return KEY_WOOD;

        if (block == Blocks.WATER) return KEY_WATER;
        if (
            block == Blocks.ICE ||
            block == Blocks.PACKED_ICE ||
            block == Blocks.BLUE_ICE
        ) return KEY_ICE;
        if (block == Blocks.HONEY_BLOCK) return KEY_HONEY;
        if (block == Blocks.CACTUS) return KEY_CACTUS;
        if (
            block == Blocks.OAK_PLANKS ||
            block == Blocks.SPRUCE_PLANKS ||
            block == Blocks.BIRCH_PLANKS ||
            block == Blocks.JUNGLE_PLANKS ||
            block == Blocks.ACACIA_PLANKS ||
            block == Blocks.DARK_OAK_PLANKS ||
            block == Blocks.MANGROVE_PLANKS ||
            block == Blocks.CHERRY_PLANKS ||
            block == Blocks.BAMBOO_PLANKS ||
            block == Blocks.CRIMSON_PLANKS ||
            block == Blocks.WARPED_PLANKS
        ) return KEY_PLANKS;
        if (block == Blocks.BEDROCK) return KEY_BEDROCK;

        // Flora / non-full blocks (billboard/cutout)
        {
            Block __grass =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(
                    net.minecraft.resources.ResourceLocation.tryParse(
                        "minecraft:grass"
                    )
                ).orElse(null);
            if (
                block == Blocks.SHORT_GRASS || block == __grass
            ) return KEY_SHORT_GRASS; // short grass (legacy 'grass' treated as short_grass)
        }
        if (block == Blocks.TALL_GRASS) return KEY_TALL_GRASS;
        if (block == Blocks.FERN || block == Blocks.LARGE_FERN) return KEY_FERN;
        if (block == Blocks.DEAD_BUSH) return KEY_DEAD_BUSH;
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT) return KEY_KELP;

        // Torches (non-occluding light sources)
        if (
            block == Blocks.TORCH ||
            block == Blocks.WALL_TORCH ||
            block == Blocks.SOUL_TORCH ||
            block == Blocks.SOUL_WALL_TORCH ||
            block == Blocks.REDSTONE_TORCH ||
            block == Blocks.REDSTONE_WALL_TORCH
        ) return KEY_TORCH;

        if (
            block == Blocks.GLASS ||
            block == Blocks.TINTED_GLASS ||
            block instanceof StainedGlassBlock ||
            block instanceof StainedGlassPaneBlock
        ) return KEY_GLASS;

        // Fallback
        return KEY_DEFAULT;
    }

    private static String keyName(byte k) {
        return switch (k) {
            case KEY_GRASS -> "grass";
            case KEY_STONE -> "stone";
            case KEY_DIRT -> "dirt";
            case KEY_LEAVES -> "leaves";
            case KEY_WOOD -> "wood";
            case KEY_SAND -> "sand";
            case KEY_WATER -> "water";
            case KEY_GLASS -> "glass";
            case KEY_SANDSTONE -> "sandstone";
            case KEY_GRAVEL -> "gravel";
            case KEY_CLAY -> "clay";
            case KEY_COAL -> "coal";
            case KEY_IRON -> "iron";
            case KEY_GOLD -> "gold";
            case KEY_DIAMOND -> "diamond";
            case KEY_REDSTONE -> "redstone";
            case KEY_LAPIS -> "lapis";
            case KEY_OBSIDIAN -> "obsidian";
            case KEY_SHORT_GRASS -> "short_grass";
            case KEY_TALL_GRASS -> "tall_grass";
            case KEY_FERN -> "fern";
            case KEY_DEAD_BUSH -> "dead_bush";
            case KEY_KELP -> "kelp";
            case KEY_TORCH -> "torch";
            case KEY_ICE -> "ice";
            case KEY_HONEY -> "honey";
            case KEY_CACTUS -> "cactus";
            case KEY_PLANKS -> "planks";
            case KEY_BEDROCK -> "bedrock";
            default -> "default";
        };
    }

    @Override
    public String toString() {
        return (
            "WorldSnapshotAccessor[" +
            "min=(" +
            minX +
            "," +
            minY +
            "," +
            minZ +
            ")," +
            "max=(" +
            maxX +
            "," +
            maxY +
            "," +
            maxZ +
            ")," +
            "dims=(" +
            sizeX +
            "x" +
            sizeY +
            "x" +
            sizeZ +
            ")]"
        );
    }

    /**
     * Helper to determine if the block at (x,y,z) should be emitted into the CUTOUT layer.
     * This uses the internal lightweight classification to avoid any client-only dependencies.
     */
    public boolean isCutout(int x, int y, int z) {
        if (!inBounds(x, y, z)) return false;
        byte k = key[idxLocal(x, y, z)];
        return k == KEY_LEAVES || k == KEY_GLASS;
    }

    /**
     * Static helper for code that only has a block key/category name.
     * Returns true for categories that should go to the CUTOUT layer.
     */
    public static boolean isCutoutKeyName(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(java.util.Locale.ROOT);
        if ("cactus".equals(s)) return true;
        return "leaves".equals(s) || "glass".equals(s);
    }

    /**
     * Helper to detect flora/torch-style billboard cutouts that should be meshed as crossed quads
     * and rendered in the CUTOUT layer (non-occluding).
     */
    public boolean isBillboardCutout(int x, int y, int z) {
        if (!inBounds(x, y, z)) return false;
        byte k = key[idxLocal(x, y, z)];
        return (
            k == KEY_SHORT_GRASS ||
            k == KEY_TALL_GRASS ||
            k == KEY_FERN ||
            k == KEY_DEAD_BUSH ||
            k == KEY_KELP ||
            k == KEY_TORCH
        );
    }

    /**
     * Static helper when only a block key/category name is available.
     */
    public static boolean isBillboardCutoutKeyName(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return (
            "short_grass".equals(s) ||
            "tall_grass".equals(s) ||
            "fern".equals(s) ||
            "dead_bush".equals(s) ||
            "kelp".equals(s) ||
            "torch".equals(s)
        );
    }

    /**
     * Helper to determine if the block at (x,y,z) should be emitted to the TRANSLUCENT layer.
     * Translucent blocks: water, ice variants, honey.
     */
    public boolean isTranslucent(int x, int y, int z) {
        if (!inBounds(x, y, z)) return false;
        byte k = key[idxLocal(x, y, z)];
        return k == KEY_WATER || k == KEY_ICE || k == KEY_HONEY;
    }

    /**
     * Static helper when only a block key/category name is available.
     */
    public static boolean isTranslucentKeyName(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return "water".equals(s) || "ice".equals(s) || "honey".equals(s);
    }

    // --------------------------------------------------------------------------------------------
    // Step B setters (called by capture) and getters for mesher access
    // --------------------------------------------------------------------------------------------

    // Internal setters to attach arrays after capture
    private void setSlabTypeArray(byte[] a) {
        this.slabType = a;
    }

    private void setStairsMetaArray(byte[] a) {
        this.stairsMeta = a;
    }

    private void setPaneConnArray(byte[] a) {
        this.paneConn = a;
    }

    private void setFenceConnArray(byte[] a) {
        this.fenceConn = a;
    }

    private void setRailMetaArray(byte[] a) {
        this.railMeta = a;
    }

    private void setDoorMetaArray(byte[] a) {
        this.doorMeta = a;
    }

    private void setTrapdoorMetaArray(byte[] a) {
        this.trapdoorMeta = a;
    }

    // Slabs
    // 0=none, 1=bottom, 2=top, 3=double
    public byte getSlabType(int x, int y, int z) {
        if (!inBounds(x, y, z) || slabType == null) return 0;
        return slabType[idxLocal(x, y, z)];
    }

    // Stairs
    // Facing: 0=N,1=E,2=S,3=W
    public byte getStairFacing(int x, int y, int z) {
        if (!inBounds(x, y, z) || stairsMeta == null) return 0;
        return (byte) (stairsMeta[idxLocal(x, y, z)] & 0x3);
    }

    // Half: 0=bottom,1=top
    public byte getStairHalf(int x, int y, int z) {
        if (!inBounds(x, y, z) || stairsMeta == null) return 0;
        return (byte) (((stairsMeta[idxLocal(x, y, z)] >> 2) & 0x1));
    }

    // Shape: 0=straight,1=other
    public byte getStairShape(int x, int y, int z) {
        if (!inBounds(x, y, z) || stairsMeta == null) return 0;
        return (byte) (((stairsMeta[idxLocal(x, y, z)] >> 3) & 0x1));
    }

    // Panes connectivity
    public boolean isPaneConnectedN(int x, int y, int z) {
        if (!inBounds(x, y, z) || paneConn == null) return false;
        return (paneConn[idxLocal(x, y, z)] & 1) != 0;
    }

    public boolean isPaneConnectedE(int x, int y, int z) {
        if (!inBounds(x, y, z) || paneConn == null) return false;
        return (paneConn[idxLocal(x, y, z)] & 2) != 0;
    }

    public boolean isPaneConnectedS(int x, int y, int z) {
        if (!inBounds(x, y, z) || paneConn == null) return false;
        return (paneConn[idxLocal(x, y, z)] & 4) != 0;
    }

    public boolean isPaneConnectedW(int x, int y, int z) {
        if (!inBounds(x, y, z) || paneConn == null) return false;
        return (paneConn[idxLocal(x, y, z)] & 8) != 0;
    }

    // Fences connectivity
    public boolean isFenceConnectedN(int x, int y, int z) {
        if (!inBounds(x, y, z) || fenceConn == null) return false;
        return (fenceConn[idxLocal(x, y, z)] & 1) != 0;
    }

    public boolean isFenceConnectedE(int x, int y, int z) {
        if (!inBounds(x, y, z) || fenceConn == null) return false;
        return (fenceConn[idxLocal(x, y, z)] & 2) != 0;
    }

    public boolean isFenceConnectedS(int x, int y, int z) {
        if (!inBounds(x, y, z) || fenceConn == null) return false;
        return (fenceConn[idxLocal(x, y, z)] & 4) != 0;
    }

    public boolean isFenceConnectedW(int x, int y, int z) {
        if (!inBounds(x, y, z) || fenceConn == null) return false;
        return (fenceConn[idxLocal(x, y, z)] & 8) != 0;
    }

    // Rails
    // 0=other,1=NS,2=EW
    public byte getRailShape(int x, int y, int z) {
        if (!inBounds(x, y, z) || railMeta == null) return 0;
        return railMeta[idxLocal(x, y, z)];
    }

    // Doors
    public byte getDoorFacing(int x, int y, int z) {
        if (!inBounds(x, y, z) || doorMeta == null) return 0;
        return (byte) (doorMeta[idxLocal(x, y, z)] & 0x3);
    }

    public boolean isDoorUpperHalf(int x, int y, int z) {
        if (!inBounds(x, y, z) || doorMeta == null) return false;
        return ((doorMeta[idxLocal(x, y, z)] >> 2) & 0x1) != 0;
    }

    public boolean isDoorOpen(int x, int y, int z) {
        if (!inBounds(x, y, z) || doorMeta == null) return false;
        return ((doorMeta[idxLocal(x, y, z)] >> 3) & 0x1) != 0;
    }

    // Trapdoors
    public byte getTrapdoorFacing(int x, int y, int z) {
        if (!inBounds(x, y, z) || trapdoorMeta == null) return 0;
        return (byte) (trapdoorMeta[idxLocal(x, y, z)] & 0x3);
    }

    public boolean isTrapdoorTopHalf(int x, int y, int z) {
        if (!inBounds(x, y, z) || trapdoorMeta == null) return false;
        return ((trapdoorMeta[idxLocal(x, y, z)] >> 2) & 0x1) != 0;
    }

    public boolean isTrapdoorOpen(int x, int y, int z) {
        if (!inBounds(x, y, z) || trapdoorMeta == null) return false;
        return ((trapdoorMeta[idxLocal(x, y, z)] >> 3) & 0x1) != 0;
    }
}
