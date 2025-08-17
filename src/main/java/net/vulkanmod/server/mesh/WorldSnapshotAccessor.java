package net.vulkanmod.server.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
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
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = cMinY; y < cMaxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    int idx = idx(x, y, z, minX, cMinY, minZ, sx, sz);
                    pos.set(x, y, z);

                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

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
                    key[idx] = classify(block, state);
                }
            }
        }

        return new WorldSnapshotAccessor(
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

    // Biome tint accessor (packed 0xRRGGBB).
    // Current snapshot does not store tints; returns neutral white as a safe default.
    public int getBiomeTintRGB(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0xFFFFFF;
        return 0xFFFFFF;
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

        // Glass/stained glass are transparent; treat as air for face visibility in this simple phase
        if (
            block == Blocks.GLASS ||
            block == Blocks.TINTED_GLASS ||
            block instanceof StainedGlassBlock ||
            block instanceof StainedGlassPaneBlock
        ) {
            return true;
        }

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

        // Most transparent blocks (but keep it conservative)
        if (
            block instanceof TransparentBlock &&
            !(block instanceof SnowLayerBlock)
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
}
