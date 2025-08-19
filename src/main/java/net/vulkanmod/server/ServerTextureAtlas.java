package net.vulkanmod.server;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Server-side texture atlas loader and mapper that does NOT depend on client classes.
 *
 * What this provides right now:
 * - Load a Minecraft resource pack ZIP (1.21.x compatible) and extract block textures from
 *   assets/minecraft/textures/block/*.png
 * - Build a single RGBA texture atlas (BufferedImage) with simple grid packing + padding
 * - Expose normalized UV regions with automatic key->texture resolution (keys like grass/stone resolve heuristically; explicit logical names also supported)
 * - Allow retrieving the atlas pixels as a native-ordered RGBA ByteBuffer for Vulkan upload
 *
 * This is an intentionally small, self-contained runtime that can evolve toward 1:1 client parity.
 * The atlas layout is simple and deterministic: each unique texture is placed in a grid cell of
 * size = max(texture.width, texture.height) with 1px padding. This avoids bleeding and keeps math easy.
 *
 * Usage:
 *   ServerTextureAtlas atlas = ServerTextureAtlas.getInstance();
 *   atlas.loadFromZip(Paths.get("/path/to/YourPack.zip"));
 *   ServerTextureAtlas.Region uv = atlas.getRegionForBlockKey("grass");
 *   ByteBuffer pixels = atlas.getAtlasPixelsRGBA(); // upload to a VulkanImage
 *
 * Notes:
 * - All block textures under assets/&lt;namespace&gt;/textures/block are packed; UV lookup is automatic via common-name heuristics (e.g., stone -> minecraft:block/stone, wood#top -> *_log_top, water sides -> water_flow).
 * - Manual overrides are still available via putKeyMapping and putFaceKeyMapping but should be used only for edge cases; automatic mapping is preferred.
 * - If a texture cannot be resolved, a built-in checkerboard fallback will be used.
 */
public final class ServerTextureAtlas {

    public static final class Region {

        public final float u0, v0, u1, v1; // normalized [0..1] UVs in atlas

        public Region(float u0, float v0, float u1, float v1) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }

        @Override
        public String toString() {
            return (
                "Region{" +
                "u0=" +
                u0 +
                ", v0=" +
                v0 +
                ", u1=" +
                u1 +
                ", v1=" +
                v1 +
                '}'
            );
        }
    }

    private static final class LoadedImage {

        final String name; // logical name (e.g., "minecraft:block/stone")
        final BufferedImage img;

        LoadedImage(String name, BufferedImage img) {
            this.name = name;
            this.img = img;
        }
    }

    private static volatile ServerTextureAtlas INSTANCE;

    public static ServerTextureAtlas getInstance() {
        ServerTextureAtlas local = INSTANCE;
        if (local != null) return local;
        synchronized (ServerTextureAtlas.class) {
            if (INSTANCE == null) {
                INSTANCE = new ServerTextureAtlas();
            }
            return INSTANCE;
        }
    }

    // Atlas image (ARGB) and UVs
    private volatile BufferedImage atlas;
    private volatile int atlasWidth;
    private volatile int atlasHeight;
    // Guard to avoid repeated auto-load attempts per process
    private volatile boolean attemptedAutoLoad = false;
    private static final int MAX_TILE_SIZE = 64;
    // Debug: track unique keys we've seen
    private final Set<String> debuggedKeys = ConcurrentHashMap.newKeySet();
    // Track unique 'key#face' fallback logs to avoid spamming
    private final Set<String> debuggedFaceKeys = ConcurrentHashMap.newKeySet();

    // Mapping: texture logical name -> UV region
    private final Map<String, Region> regionByTexture =
        new ConcurrentHashMap<>();
    // Mapping: "block key" (mesher categories) -> texture logical name
    private final Map<String, String> keyToTexture = new ConcurrentHashMap<>();
    // Mapping: "block key#face" (e.g., grass#top, wood#side) -> texture logical name
    private final Map<String, String> keyFaceToTexture =
        new ConcurrentHashMap<>();

    // Pack-driven model/blockstate index (populated during load to enable blockId lookups)
    private volatile net.vulkanmod.server.pack.PackIndex packIndex;

    // List of texture logical names required for our current categories

    private ServerTextureAtlas() {
        // Automatic atlas uses discovered textures only; manual defaults disabled
        // Add common key aliases to catch likely mismatches
        setupKeyAliases();
    }

    private void setupKeyAliases() {
        // Simple direct mappings for common cases and precise aliases for common blocks/ores
        keyToTexture.put("stone", "minecraft:block/stone");
        keyToTexture.put("cobblestone", "minecraft:block/cobblestone");

        keyToTexture.put("dirt", "minecraft:block/dirt");
        // Map generic "grass" to side; face lookups will select top/bottom as needed
        keyToTexture.put("grass", "minecraft:block/grass_block_side");

        keyToTexture.put("sand", "minecraft:block/sand");
        keyToTexture.put("gravel", "minecraft:block/gravel");
        keyToTexture.put("clay", "minecraft:block/clay");
        keyToTexture.put("sandstone", "minecraft:block/sandstone");

        keyToTexture.put("wood", "minecraft:block/oak_log");
        keyToTexture.put("leaves", "minecraft:block/oak_leaves");
        keyToTexture.put("pines", "minecraft:block/spruce_leaves");
        keyToTexture.put("bedrock", "minecraft:block/bedrock");

        keyToTexture.put("water", "minecraft:block/water_still"); // sides resolved to flow in face lookup
        keyToTexture.put("glass", "minecraft:block/glass");
        keyToTexture.put("obsidian", "minecraft:block/obsidian");
        keyToTexture.put("ice", "minecraft:block/ice");

        // Honey: prefer honey_block, not honeycomb
        keyToTexture.put("honey", "minecraft:block/honey_block_side");

        // Flora / cutout
        keyToTexture.put("short_grass", "minecraft:block/short_grass");
        keyToTexture.put("tall_grass", "minecraft:block/tall_grass_top");
        keyToTexture.put("fern", "minecraft:block/fern");
        keyToTexture.put("dead_bush", "minecraft:block/dead_bush");
        keyToTexture.put("torch", "minecraft:block/torch");
        // Additional plants / blocks commonly encountered
        keyToTexture.put("kelp", "minecraft:block/kelp_plant");
        keyToTexture.put("cactus", "minecraft:block/cactus_side"); // solid block; not cutout

        // Ores
        keyToTexture.put("coal", "minecraft:block/coal_ore");
        keyToTexture.put("iron", "minecraft:block/iron_ore");
        keyToTexture.put("gold", "minecraft:block/gold_ore");
        keyToTexture.put("diamond", "minecraft:block/diamond_ore");
        keyToTexture.put("redstone", "minecraft:block/redstone_ore");
        keyToTexture.put("lapis", "minecraft:block/lapis_ore");

        // Broader aliases to reduce misses when callers are more specific
        keyToTexture.put("log", "minecraft:block/oak_log");
        keyToTexture.put("planks", "minecraft:block/oak_planks");

        // Face-specific mappings for blocks with distinct top/bottom/side textures
        keyFaceToTexture.put("grass#top", "minecraft:block/grass_block_top");
        keyFaceToTexture.put("grass#bottom", "minecraft:block/dirt");
        keyFaceToTexture.put("grass#side", "minecraft:block/grass_block_side");

        keyFaceToTexture.put("wood#top", "minecraft:block/oak_log_top");
        keyFaceToTexture.put("wood#bottom", "minecraft:block/oak_log_top");
        keyFaceToTexture.put("wood#side", "minecraft:block/oak_log");

        keyFaceToTexture.put("sandstone#top", "minecraft:block/sandstone_top");
        keyFaceToTexture.put(
            "sandstone#bottom",
            "minecraft:block/sandstone_bottom"
        );
        keyFaceToTexture.put("sandstone#side", "minecraft:block/sandstone");

        keyFaceToTexture.put("honey#top", "minecraft:block/honey_block_top");
        keyFaceToTexture.put(
            "honey#bottom",
            "minecraft:block/honey_block_bottom"
        );
        keyFaceToTexture.put("honey#side", "minecraft:block/honey_block_side");

        keyFaceToTexture.put("cactus#top", "minecraft:block/cactus_top");
        keyFaceToTexture.put("cactus#bottom", "minecraft:block/cactus_bottom");
        keyFaceToTexture.put("cactus#side", "minecraft:block/cactus_side");

        keyFaceToTexture.put("kelp#top", "minecraft:block/kelp_top");
        keyFaceToTexture.put("kelp#side", "minecraft:block/kelp_plant");
        keyFaceToTexture.put("kelp#bottom", "minecraft:block/kelp_plant");

        keyFaceToTexture.put("water#top", "minecraft:block/water_still");
        keyFaceToTexture.put("water#bottom", "minecraft:block/water_still");
        keyFaceToTexture.put("water#side", "minecraft:block/water_flow");

        // Fallback
        keyToTexture.put("default", "minecraft:block/fallback");
    }

    /**
     * Load the texture atlas from a resource pack ZIP. This replaces any previously loaded atlas.
     *
     * @param zipPath Path to a Minecraft resource pack zip compatible with 1.21.x
     * @return true on success, false otherwise
     */
    public synchronized boolean loadFromZip(Path zipPath) {
        Objects.requireNonNull(zipPath, "zipPath");
        if (!Files.exists(zipPath)) {
            System.err.println(
                "[ServerTextureAtlas] Pack zip not found: " + zipPath
            );
            return false;
        }

        Map<String, BufferedImage> blockTextures;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            blockTextures = readBlockTextures(zip);
        } catch (IOException e) {
            System.err.println(
                "[ServerTextureAtlas] Failed to open pack zip: " + e
            );
            return false;
        }

        if (blockTextures.isEmpty()) {
            System.err.println(
                "[ServerTextureAtlas] No block textures found in pack: " +
                zipPath
            );
            // still proceed with fallback-only atlas so renderer doesn't crash
        }

        // Build pack index to resolve models/blockstates and prefetch referenced textures
        try {
            this.packIndex = new net.vulkanmod.server.pack.PackIndex();
            this.packIndex.loadFromZip(zipPath);
        } catch (Throwable t) {
            System.err.println(
                "[ServerTextureAtlas] PackIndex load failed: " + t
            );
            this.packIndex = null;
        }

        // Collect all discovered block textures into the atlas (dedup by canonical logical name)
        Map<String, LoadedImage> unique = new LinkedHashMap<>();
        for (Map.Entry<String, BufferedImage> e : blockTextures.entrySet()) {
            String logicalName = canonical(e.getKey());
            BufferedImage orig = ensureRGBA(e.getValue());
            BufferedImage img = orig;
            if (
                orig.getWidth() > MAX_TILE_SIZE ||
                orig.getHeight() > MAX_TILE_SIZE
            ) {
                int sw = Math.min(MAX_TILE_SIZE, orig.getWidth());
                int sh = Math.min(MAX_TILE_SIZE, orig.getHeight());
                float scale = Math.min(
                    MAX_TILE_SIZE / (float) orig.getWidth(),
                    MAX_TILE_SIZE / (float) orig.getHeight()
                );
                sw = Math.max(1, Math.round(orig.getWidth() * scale));
                sh = Math.max(1, Math.round(orig.getHeight() * scale));
                BufferedImage scaled = new BufferedImage(
                    sw,
                    sh,
                    BufferedImage.TYPE_INT_ARGB
                );
                Graphics2D sg = scaled.createGraphics();
                try {
                    sg.setComposite(AlphaComposite.Src);
                    sg.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    );
                    sg.drawImage(orig, 0, 0, sw, sh, null);
                } finally {
                    sg.dispose();
                }
                img = scaled;
            }
            unique.putIfAbsent(logicalName, new LoadedImage(logicalName, img));
        }

        // Include all model-referenced textures from PackIndex (if available)
        if (this.packIndex != null) {
            java.util.Set<String> refs =
                this.packIndex.collectAllReferencedTextures();
            for (String name : refs) {
                String cand = canonical(name);
                BufferedImage img = blockTextures.get(cand);
                if (img == null) {
                    String stripped = stripNamespace(cand);
                    img = blockTextures.get(stripped);
                    if (img == null && stripped.indexOf('/') < 0) {
                        img = blockTextures.get("minecraft:block/" + stripped);
                        if (img == null) img = blockTextures.get(
                            "block/" + stripped
                        );
                    }
                }
                if (img != null) {
                    unique.putIfAbsent(
                        cand,
                        new LoadedImage(cand, ensureRGBA(img))
                    );
                } else {
                    System.out.println(
                        "[ServerTextureAtlas] Missing referenced texture: " +
                        cand
                    );
                }
            }
        }

        // Ensure explicit fallback tile is always present in atlas
        unique.putIfAbsent(
            "minecraft:block/fallback",
            new LoadedImage(
                "minecraft:block/fallback",
                ensureRGBA(fallbackTexture(16, 16))
            )
        );
        {
            int count = unique.size();
            System.out.println(
                "[ServerTextureAtlas] Packing " +
                count +
                " textures into atlas (zip)"
            );
            boolean ok = buildAtlas(new ArrayList<>(unique.values()));
            if (ok) {
                System.out.println(
                    "[ServerTextureAtlas] Atlas ready: " +
                    atlasWidth +
                    "x" +
                    atlasHeight +
                    ", regions=" +
                    regionByTexture.size()
                );
            } else {
                System.err.println(
                    "[ServerTextureAtlas] Failed to build atlas from zip"
                );
            }
            return ok;
        }
    }

    /**
     * Returns the UV region for a block key (e.g., "grass", "stone"...).
     * If no atlas is loaded or the key is unknown, a fallback region is returned (magenta checkerboard).
     */
    public Region getRegionForBlockKey(String blockKey) {
        if (blockKey == null || blockKey.isEmpty()) blockKey = "default";
        String k = blockKey.toLowerCase(Locale.ROOT);

        // Debug: log each unique key once
        boolean __firstForKey = debuggedKeys.add(k);
        if (__firstForKey) {
            System.out.println(
                "[ServerTextureAtlas] First request for key: '" + k + "'"
            );
        }

        // Check simple aliases first
        String aliased = keyToTexture.get(k);
        if (aliased != null) {
            String cand = canonical(aliased);
            Region r = regionByTexture.get(cand);
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   alias -> '" +
                    cand +
                    "', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
        } else if (__firstForKey) {
            System.out.println("[ServerTextureAtlas]   alias -> (none)");
        }

        // Try direct texture name
        String directName = canonical("minecraft:block/" + k);
        Region direct = regionByTexture.get(directName);
        if (__firstForKey) {
            System.out.println(
                "[ServerTextureAtlas]   direct -> '" +
                directName +
                "', exists=" +
                (direct != null)
            );
        }
        if (direct != null) return direct;

        // Gentle compatibility fallbacks for common/legacy names
        if ("short_grass".equals(k)) {
            Region r = regionByTexture.get("minecraft:block/grass");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat short_grass -> 'minecraft:block/grass', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
            r = regionByTexture.get("minecraft:block/tall_grass_top");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat short_grass -> 'minecraft:block/tall_grass_top', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
        } else if ("cactus".equals(k)) {
            Region r = regionByTexture.get("minecraft:block/cactus_side");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat cactus -> 'minecraft:block/cactus_side', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
        } else if ("kelp".equals(k)) {
            // Try multiple common kelp names across packs
            Region r = regionByTexture.get("minecraft:block/kelp_plant");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat kelp -> 'minecraft:block/kelp_plant', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
            r = regionByTexture.get("minecraft:block/kelp_top");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat kelp -> 'minecraft:block/kelp_top', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
            r = regionByTexture.get("block/kelp");
            if (__firstForKey) {
                System.out.println(
                    "[ServerTextureAtlas]   compat kelp -> 'block/kelp', exists=" +
                    (r != null)
                );
            }
            if (r != null) return r;
        }

        // Fallback
        Region fb = regionByTexture.get("minecraft:block/fallback");
        if (__firstForKey) {
            System.out.println(
                "[ServerTextureAtlas]   fallback -> 'minecraft:block/fallback', exists=" +
                (fb != null)
            );
        }
        if (fb != null) return fb;

        // Last resort
        if (__firstForKey) {
            System.out.println(
                "[ServerTextureAtlas]   last-resort -> first available region"
            );
        }
        return regionByTexture
            .values()
            .stream()
            .findFirst()
            .orElse(new Region(0f, 0f, 1f, 1f));
    }

    // Per-face region lookup: faceHint can be "top","bottom","side"; falls back to getRegionForBlockKey
    public Region getRegionForBlockFace(String blockKey, String faceHint) {
        if (blockKey == null || blockKey.isEmpty()) return getRegionForBlockKey(
            "default"
        );
        String face = (faceHint == null
                ? "side"
                : faceHint.toLowerCase(java.util.Locale.ROOT));
        String k = blockKey.toLowerCase(java.util.Locale.ROOT);

        // 1) Explicit face mapping table (populated in setupKeyAliases / user overrides)
        String mapped = keyFaceToTexture.get(k + "#" + face);
        if (mapped != null) {
            Region r = regionByTexture.get(canonical(mapped));
            if (r != null) return r;
        }

        // 2) Built-in face-specific mappings for common blocks
        if ("grass".equals(k)) {
            if ("top".equals(face)) {
                Region r = regionByTexture.get(
                    "minecraft:block/grass_block_top"
                );
                if (r != null) return r;
            } else if ("bottom".equals(face)) {
                Region r = regionByTexture.get("minecraft:block/dirt");
                if (r != null) return r;
            } else {
                Region r = regionByTexture.get(
                    "minecraft:block/grass_block_side"
                );
                if (r != null) return r;
            }
        } else if ("water".equals(k)) {
            if ("side".equals(face)) {
                Region r = regionByTexture.get("minecraft:block/water_flow");
                if (r != null) return r;
            } else {
                Region r = regionByTexture.get("minecraft:block/water_still");
                if (r != null) return r;
            }
        }

        // Fallback to block-level resolution; log once if this is a miss for face-specific mapping
        String __kf = (k + "#" + face);
        if (debuggedFaceKeys.add(__kf)) {
            System.out.println(
                "[ServerTextureAtlas] Face miss; falling back: '" + __kf + "'"
            );
        }
        return getRegionForBlockKey(blockKey);
    }

    /**
     * @return The atlas image (ARGB) for debugging. Do not mutate.
     */
    public BufferedImage getAtlasImage() {
        ensureLoaded();
        return atlas;
    }

    /**
     * Returns the atlas pixels encoded as tightly packed RGBA (8-8-8-8) in native byte order, row-major, no flips.
     * Returns null if the atlas is not loaded yet.
     */
    public ByteBuffer getAtlasPixelsRGBA() {
        ensureLoaded();
        BufferedImage img = atlas;
        if (img == null) return null;

        int w = img.getWidth();
        int h = img.getHeight();
        int[] abgr = img.getRGB(0, 0, w, h, null, 0, w);

        // Convert ABGR (Java ARGB read as INTs) -> RGBA bytes
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(
            ByteOrder.nativeOrder()
        );
        for (int c : abgr) {
            int a = (c >>> 24) & 0xFF;
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = (c) & 0xFF;
            buf.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        }
        buf.flip();
        return buf;
    }

    public int getAtlasWidth() {
        ensureLoaded();
        return atlasWidth;
    }

    public int getAtlasHeight() {
        ensureLoaded();
        return atlasHeight;
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

    /**
     * Lazily ensure the atlas is loaded. Searches multiple locations:
     * 1) Explicit pack zip via sysprops/env: survivalmode.resourcePack, minecraft.resourcePack, SURVIVALMODE_RESOURCEPACK
     * 2) Game dirs via sysprops/env: survivalmode.gameDir, minecraft.gameDir, SURVIVALMODE_GAMEDIR, MINECRAFT_HOME, MINECRAFT_DIR
     * 3) Common locations: CWD, ~/.minecraft, %APPDATA%/.minecraft, ~/Library/Application Support/minecraft
     * For each gameDir candidate, scans "<gameDir>/resourcepacks" for the newest .zip.
     * Builds a minimal fallback atlas if nothing is found or loading fails.
     */
    private void ensureLoaded() {
        if (this.atlas != null || this.attemptedAutoLoad) return;
        this.attemptedAutoLoad = true;
        try {
            java.util.List<java.nio.file.Path> candidates =
                new java.util.ArrayList<>();
            // 1) Explicit resource pack hints (zip or directory)
            String packZipProp = System.getProperty(
                "survivalmode.resourcePack",
                System.getProperty("minecraft.resourcePack")
            );
            String packZipEnv = System.getenv("SURVIVALMODE_RESOURCEPACK");
            if (packZipProp != null && !packZipProp.isEmpty()) {
                java.nio.file.Path p = java.nio.file.Paths.get(packZipProp);
                if (java.nio.file.Files.isRegularFile(p)) {
                    System.out.println(
                        "[ServerTextureAtlas] Auto-loading explicit pack (sysprop): " +
                        p
                    );
                    if (loadFromPackPath(p) && this.atlas != null) return;
                } else if (java.nio.file.Files.isDirectory(p)) {
                    System.out.println(
                        "[ServerTextureAtlas] Auto-loading explicit pack dir (sysprop): " +
                        p
                    );
                    if (loadFromPackPath(p) && this.atlas != null) return;
                    candidates.add(p);
                } else if (p.getParent() != null) {
                    candidates.add(p.getParent());
                }
            }
            if (packZipEnv != null && !packZipEnv.isEmpty()) {
                java.nio.file.Path p = java.nio.file.Paths.get(packZipEnv);
                if (java.nio.file.Files.isRegularFile(p)) {
                    System.out.println(
                        "[ServerTextureAtlas] Auto-loading explicit pack (env): " +
                        p
                    );
                    if (loadFromPackPath(p) && this.atlas != null) return;
                } else if (java.nio.file.Files.isDirectory(p)) {
                    System.out.println(
                        "[ServerTextureAtlas] Auto-loading explicit pack dir (env): " +
                        p
                    );
                    if (loadFromPackPath(p) && this.atlas != null) return;
                    candidates.add(p);
                } else if (p.getParent() != null) {
                    candidates.add(p.getParent());
                }
            }

            // 2) Game directory hints
            String gameDirProp = System.getProperty(
                "survivalmode.gameDir",
                System.getProperty("minecraft.gameDir")
            );
            String gameDirEnv = System.getenv("SURVIVALMODE_GAMEDIR");
            String mcHomeEnv = System.getenv("MINECRAFT_HOME");
            String mcDirEnv = System.getenv("MINECRAFT_DIR");
            if (gameDirProp != null && !gameDirProp.isEmpty()) candidates.add(
                java.nio.file.Paths.get(gameDirProp)
            );
            if (gameDirEnv != null && !gameDirEnv.isEmpty()) candidates.add(
                java.nio.file.Paths.get(gameDirEnv)
            );
            if (mcHomeEnv != null && !mcHomeEnv.isEmpty()) candidates.add(
                java.nio.file.Paths.get(mcHomeEnv)
            );
            if (mcDirEnv != null && !mcDirEnv.isEmpty()) candidates.add(
                java.nio.file.Paths.get(mcDirEnv)
            );

            // 3) Common defaults
            java.nio.file.Path cwd = java.nio.file.Paths.get(
                ""
            ).toAbsolutePath();
            java.nio.file.Path home = java.nio.file.Paths.get(
                System.getProperty("user.home", ".")
            ).toAbsolutePath();
            candidates.add(cwd);
            candidates.add(home.resolve(".minecraft"));
            String appdata = System.getenv("APPDATA");
            if (appdata != null && !appdata.isEmpty()) {
                candidates.add(
                    java.nio.file.Paths.get(appdata).resolve(".minecraft")
                );
            }
            candidates.add(
                home
                    .resolve("Library")
                    .resolve("Application Support")
                    .resolve("minecraft")
            );

            // 4) Scan each candidate gameDir for resourcepacks (zip or directory packs)
            for (java.nio.file.Path base : candidates) {
                if (base == null) continue;
                java.util.Optional<java.nio.file.Path> z =
                    findDefaultResourcePack(base);
                if (z.isPresent()) {
                    java.nio.file.Path packPath = z.get();
                    System.out.println(
                        "[ServerTextureAtlas] Auto-loading resource pack: " +
                        packPath
                    );
                    if (
                        loadFromPackPath(packPath) && this.atlas != null
                    ) return;
                }
            }
        } catch (Throwable t) {
            System.err.println("[ServerTextureAtlas] Auto-load failed: " + t);
        }
        // Last resort: build a 1-tile fallback atlas
        if (this.atlas == null) {
            System.out.println(
                "[ServerTextureAtlas] Building fallback atlas (no resource pack found)"
            );
            buildAtlas(java.util.Collections.emptyList());
        }
    }

    private static String stripNamespace(String name) {
        int i = name.indexOf(':');
        return (i >= 0) ? name.substring(i + 1) : name;
    }

    private static String canonical(String name) {
        // Ensure we always store and lookup with a "minecraft:" namespace
        if (name.indexOf(':') < 0) return "minecraft:" + name;
        return name;
    }

    private static BufferedImage ensureRGBA(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage dst = new BufferedImage(
            src.getWidth(),
            src.getHeight(),
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = dst.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private Map<String, BufferedImage> readBlockTextures(ZipFile zip) {
        // We accept entries in either:
        //  - assets/minecraft/textures/block/<name>.png
        // and we will store logical names both with and without "minecraft:" namespace for convenience
        Map<String, BufferedImage> out = new HashMap<>();

        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (e.isDirectory()) continue;

            // Normalize path separators
            String lower = n.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (
                !lower.startsWith("assets/") ||
                lower.indexOf("/textures/block/") < 0
            ) continue;
            if (!lower.endsWith(".png")) continue;

            try (InputStream in = zip.getInputStream(e)) {
                BufferedImage img = ImageIO.read(in);
                if (img == null) continue;

                // Logical name forms:
                //   "<namespace>:block/<name>"
                //   "block/<name>"
                int nsStart = "assets/".length();
                int nsEnd = lower.indexOf('/', nsStart);
                String namespace = (nsEnd > nsStart)
                    ? lower.substring(nsStart, nsEnd)
                    : "minecraft";
                String shortName = "block/" + baseName(lower);
                String namespaced = namespace + ":" + shortName;

                out.put(shortName, img);
                out.put(namespaced, img);
            } catch (IOException io) {
                System.err.println(
                    "[ServerTextureAtlas] Failed reading PNG from pack: " +
                    n +
                    " : " +
                    io
                );
            }
        }
        return out;
    }

    private static String baseName(String pathLower) {
        int i = pathLower.lastIndexOf('/');
        String file = (i >= 0) ? pathLower.substring(i + 1) : pathLower;
        if (file.endsWith(".png")) file = file.substring(0, file.length() - 4);
        return file;
    }

    private boolean buildAtlas(List<LoadedImage> textures) {
        if (textures.isEmpty()) {
            // Build a 16x16 fallback atlas with a single region
            BufferedImage fb = fallbackTexture(16, 16);
            this.atlas = fb;
            this.atlasWidth = fb.getWidth();
            this.atlasHeight = fb.getHeight();
            this.regionByTexture.clear();
            this.regionByTexture.put(
                "minecraft:block/fallback",
                new Region(0f, 0f, 1f, 1f)
            );
            System.out.println(
                "[ServerTextureAtlas] Built fallback atlas (no textures found); size=" +
                atlasWidth +
                "x" +
                atlasHeight +
                " regions=" +
                regionByTexture.size()
            );
            return true;
        }

        // Grid packing parameters with size limits
        final int padding = 4; // 4px padding around each cell to reduce sampling bleed and improve mip sampling stability
        final int MAX_ATLAS_SIZE = 8192; // GPU-friendly limit
        final int MAX_TEXTURES = Integer.MAX_VALUE; // Include all textures; scaling keeps atlas small

        // Pin critical textures so they are never filtered out (kept first)
        java.util.Set<String> pinnedNames = new java.util.LinkedHashSet<>();
        pinnedNames.add("minecraft:block/fallback");
        pinnedNames.add("minecraft:block/stone");
        pinnedNames.add("minecraft:block/dirt");
        pinnedNames.add("minecraft:block/grass_block_top");
        pinnedNames.add("minecraft:block/grass_block_side");
        pinnedNames.add("minecraft:block/water_still");
        pinnedNames.add("minecraft:block/water_flow");
        pinnedNames.add("minecraft:block/oak_log");
        pinnedNames.add("minecraft:block/oak_log_top");
        pinnedNames.add("minecraft:block/oak_leaves");
        pinnedNames.add("minecraft:block/glass");
        pinnedNames.add("minecraft:block/obsidian");
        pinnedNames.add("minecraft:block/honey_block_side");
        pinnedNames.add("minecraft:block/honey_block_top");
        pinnedNames.add("minecraft:block/honey_block_bottom");
        // Ensure cactus textures are always included and sampled as solid
        pinnedNames.add("minecraft:block/cactus_side");
        pinnedNames.add("minecraft:block/cactus_top");
        pinnedNames.add("minecraft:block/cactus_bottom");

        java.util.List<LoadedImage> pinned = new java.util.ArrayList<>();
        java.util.List<LoadedImage> nonPinned = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LoadedImage li : textures) {
            if (!seen.add(li.name)) continue;
            if (pinnedNames.contains(li.name)) pinned.add(li);
            else nonPinned.add(li);
        }

        // Build initial selection honoring MAX_TEXTURES
        java.util.List<LoadedImage> finalTextures = new java.util.ArrayList<>();
        if (textures.size() > MAX_TEXTURES) {
            int allowOthers = Math.max(0, MAX_TEXTURES - pinned.size());
            if (allowOthers < nonPinned.size()) {
                System.out.println(
                    "[ServerTextureAtlas] Too many textures (" +
                    textures.size() +
                    "), filtering others to " +
                    allowOthers +
                    " while keeping pinned=" +
                    pinned.size()
                );
                java.util.List<LoadedImage> pickedOthers =
                    filterEssentialTextures(nonPinned, allowOthers);
                finalTextures.addAll(pinned);
                finalTextures.addAll(pickedOthers);
            } else {
                finalTextures.addAll(pinned);
                finalTextures.addAll(nonPinned);
            }
        } else {
            finalTextures.addAll(pinned);
            finalTextures.addAll(nonPinned);
        }

        int cellSize = 0;
        for (LoadedImage li : finalTextures) {
            cellSize = Math.max(
                cellSize,
                Math.max(li.img.getWidth(), li.img.getHeight())
            );
        }
        // Guarantee at least 16x16 cells but cap at reasonable size
        cellSize = Math.max(cellSize, 16);
        cellSize = Math.min(cellSize, MAX_TILE_SIZE); // Cap individual texture size
        final int cellStride = cellSize + padding * 2;

        int count = finalTextures.size();
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) cols);

        int atlasW = cols * cellStride;
        int atlasH = rows * cellStride;

        // Ensure atlas doesn't exceed GPU limits (while keeping all pinned)
        if (atlasW > MAX_ATLAS_SIZE || atlasH > MAX_ATLAS_SIZE) {
            System.out.println(
                "[ServerTextureAtlas] Atlas too large (" +
                atlasW +
                "x" +
                atlasH +
                "), reducing non-pinned texture count"
            );
            int maxCells =
                (MAX_ATLAS_SIZE / cellStride) * (MAX_ATLAS_SIZE / cellStride);
            int allowCells = Math.max(0, maxCells - pinned.size());
            java.util.List<LoadedImage> reducedOthers = filterEssentialTextures(
                nonPinned,
                allowCells
            );
            finalTextures.clear();
            finalTextures.addAll(pinned);
            finalTextures.addAll(reducedOthers);

            count = finalTextures.size();
            cols = (int) Math.ceil(Math.sqrt(count));
            rows = (int) Math.ceil(count / (double) cols);
            atlasW = cols * cellStride;
            atlasH = rows * cellStride;
        }

        BufferedImage out = new BufferedImage(
            atlasW,
            atlasH,
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            // clear with transparent
            g.setBackground(new Color(0, 0, 0, 0));
            g.clearRect(0, 0, atlasW, atlasH);

            // Paint each texture into its grid cell, centered with padding
            int i = 0;
            this.regionByTexture.clear();

            for (LoadedImage li : finalTextures) {
                int col = i % cols;
                int row = i / cols;
                int dstX =
                    col * cellStride +
                    padding +
                    (cellSize - li.img.getWidth()) / 2;
                int dstY =
                    row * cellStride +
                    padding +
                    (cellSize - li.img.getHeight()) / 2;

                // Draw image and bleed 1px edges into padding to avoid seams
                g.drawImage(li.img, dstX, dstY, null);
                {
                    int iw = li.img.getWidth();
                    int ih = li.img.getHeight();

                    // Bleed edges across the full padding width
                    for (int p = 1; p <= padding; p++) {
                        // Left bleed (1px column replicated outward)
                        g.drawImage(
                            li.img,
                            dstX - p,
                            dstY,
                            dstX - (p - 1),
                            dstY + ih,
                            0,
                            0,
                            1,
                            ih,
                            null
                        );
                        // Right bleed
                        g.drawImage(
                            li.img,
                            dstX + iw + (p - 1),
                            dstY,
                            dstX + iw + p,
                            dstY + ih,
                            iw - 1,
                            0,
                            iw,
                            ih,
                            null
                        );

                        // Top bleed (1px row replicated upward across full padded width)
                        g.drawImage(
                            li.img,
                            dstX - padding,
                            dstY - p,
                            dstX + iw + padding,
                            dstY - (p - 1),
                            0,
                            0,
                            iw,
                            1,
                            null
                        );
                        // Bottom bleed
                        g.drawImage(
                            li.img,
                            dstX - padding,
                            dstY + ih + (p - 1),
                            dstX + iw + padding,
                            dstY + ih + p,
                            0,
                            ih - 1,
                            iw,
                            ih,
                            null
                        );

                        // Corner pixels replicated outward
                        // Top-left
                        g.drawImage(
                            li.img,
                            dstX - p,
                            dstY - p,
                            dstX - (p - 1),
                            dstY - (p - 1),
                            0,
                            0,
                            1,
                            1,
                            null
                        );
                        // Top-right
                        g.drawImage(
                            li.img,
                            dstX + iw + (p - 1),
                            dstY - p,
                            dstX + iw + p,
                            dstY - (p - 1),
                            iw - 1,
                            0,
                            iw,
                            1,
                            null
                        );
                        // Bottom-left
                        g.drawImage(
                            li.img,
                            dstX - p,
                            dstY + ih + (p - 1),
                            dstX - (p - 1),
                            dstY + ih + p,
                            0,
                            ih - 1,
                            1,
                            ih,
                            null
                        );
                        // Bottom-right
                        g.drawImage(
                            li.img,
                            dstX + iw + (p - 1),
                            dstY + ih + (p - 1),
                            dstX + iw + p,
                            dstY + ih + p,
                            iw - 1,
                            ih - 1,
                            iw,
                            ih,
                            null
                        );
                    }
                }

                // Compute normalized UVs for the content area (exclude bleed).
                // With 4px padding + full edge bleed and CLAMP_TO_EDGE, no expansion is needed.
                // Inset by half a texel to avoid sampling padded/transparent border
                float u0 = (dstX + 0.5f) / (float) atlasW;
                float v0 = (dstY + 0.5f) / (float) atlasH;
                float u1 = (dstX + li.img.getWidth() - 0.5f) / (float) atlasW;
                float v1 = (dstY + li.img.getHeight() - 0.5f) / (float) atlasH;

                this.regionByTexture.put(li.name, new Region(u0, v0, u1, v1));
                i++;
            }
        } finally {
            g.dispose();
        }

        this.atlas = out;
        this.atlasWidth = atlasW;
        this.atlasHeight = atlasH;
        System.out.println(
            "[ServerTextureAtlas] Built atlas: " +
            atlasWidth +
            "x" +
            atlasHeight +
            " cells=" +
            finalTextures.size() +
            " regions=" +
            regionByTexture.size()
        );
        return true;
    }

    /**
     * Filter textures to keep only the most essential ones for rendering.
     * Prioritizes common block textures over decorative/rare ones.
     */
    private static List<LoadedImage> filterEssentialTextures(
        List<LoadedImage> textures,
        int maxCount
    ) {
        if (textures.size() <= maxCount) return textures;

        List<LoadedImage> essential = new ArrayList<>();
        List<LoadedImage> common = new ArrayList<>();
        List<LoadedImage> others = new ArrayList<>();

        // Priority categories
        String[] essentialNames = {
            "stone",
            "dirt",
            "grass",
            "water",
            "sand",
            "wood",
            "log",
            "oak",
            "cobblestone",
            "bedrock",
            "gravel",
            "clay",
            "iron",
            "coal",
            "diamond",
        };
        String[] commonNames = {
            "leaves",
            "glass",
            "brick",
            "planks",
            "fence",
            "door",
            "trapdoor",
            "stairs",
            "slab",
            "ore",
            "gold",
            "redstone",
            "lapis",
            "emerald",
        };

        for (LoadedImage li : textures) {
            String name = li.name.toLowerCase();
            boolean isEssential = false;
            boolean isCommon = false;

            for (String essential_name : essentialNames) {
                if (name.contains(essential_name)) {
                    isEssential = true;
                    break;
                }
            }
            if (!isEssential) {
                for (String common_name : commonNames) {
                    if (name.contains(common_name)) {
                        isCommon = true;
                        break;
                    }
                }
            }

            if (isEssential) {
                essential.add(li);
            } else if (isCommon) {
                common.add(li);
            } else {
                others.add(li);
            }
        }

        List<LoadedImage> result = new ArrayList<>();
        result.addAll(essential);

        int remaining = maxCount - essential.size();
        if (remaining > 0) {
            int toTakeFromCommon = Math.min(remaining, common.size());
            result.addAll(common.subList(0, toTakeFromCommon));
            remaining -= toTakeFromCommon;
        }

        if (remaining > 0) {
            int toTakeFromOthers = Math.min(remaining, others.size());
            result.addAll(others.subList(0, toTakeFromOthers));
        }

        System.out.println(
            "[ServerTextureAtlas] Filtered to " +
            result.size() +
            " textures (essential=" +
            essential.size() +
            ", common=" +
            Math.min(maxCount - essential.size(), common.size()) +
            ")"
        );
        return result;
    }

    // --------------------------------------------------------------------------------------------
    // Mapping for block keys (mesher) -> canonical texture names
    // --------------------------------------------------------------------------------------------

    // removed: defaultKeyToTextureMapping (manual override mapping)

    // removed: defaultFaceKeyToTextureMapping (manual override mapping)

    // --------------------------------------------------------------------------------------------
    // Fallback texture utilities
    // --------------------------------------------------------------------------------------------

    private static BufferedImage fallbackTexture(int w, int h) {
        BufferedImage img = new BufferedImage(
            w,
            h,
            BufferedImage.TYPE_INT_ARGB
        );
        int c0 = 0xFF0000FF; // blue
        int c1 = 0xFF000000; // black
        int size = Math.max(2, Math.min(w, h) / 4);
        for (int y = 0; y < h; y++) {
            int yy = (y / size) & 1;
            for (int x = 0; x < w; x++) {
                int xx = (x / size) & 1;
                int c = ((xx ^ yy) == 0) ? c0 : c1;
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    // --------------------------------------------------------------------------------------------
    // Directory/Zip pack loader entry-point and directory reader
    // --------------------------------------------------------------------------------------------

    /**
     * Load the texture atlas from either a resource pack ZIP or a directory.
     * If a directory is provided, all namespaces under assets/&lt;namespace&gt;/textures/block are included.
     */
    public synchronized boolean loadFromPackPath(Path packPath) {
        Objects.requireNonNull(packPath, "packPath");
        if (Files.isDirectory(packPath)) {
            Map<String, BufferedImage> blockTextures = readBlockTexturesFromDir(
                packPath
            );
            if (blockTextures.isEmpty()) {
                System.err.println(
                    "[ServerTextureAtlas] No block textures found in pack dir: " +
                    packPath
                );
                // still proceed with fallback-only atlas so renderer doesn't crash
            }
            // Build pack index to resolve models/blockstates and prefetch referenced textures
            try {
                this.packIndex = new net.vulkanmod.server.pack.PackIndex();
                this.packIndex.loadFromDir(packPath);
            } catch (Throwable t) {
                System.err.println(
                    "[ServerTextureAtlas] PackIndex load failed: " + t
                );
                this.packIndex = null;
            }
            Map<String, LoadedImage> unique = new LinkedHashMap<>();
            for (Map.Entry<
                String,
                BufferedImage
            > e : blockTextures.entrySet()) {
                String logicalName = canonical(e.getKey());
                BufferedImage img = ensureRGBA(e.getValue());
                unique.putIfAbsent(
                    logicalName,
                    new LoadedImage(logicalName, img)
                );
            }
            // Include all model-referenced textures from PackIndex (if available)
            if (this.packIndex != null) {
                java.util.Set<String> refs =
                    this.packIndex.collectAllReferencedTextures();
                for (String name : refs) {
                    String cand = canonical(name);
                    BufferedImage img = blockTextures.get(cand);
                    if (img == null) {
                        String stripped = stripNamespace(cand);
                        img = blockTextures.get(stripped);
                        if (img == null && stripped.indexOf('/') < 0) {
                            img = blockTextures.get(
                                "minecraft:block/" + stripped
                            );
                            if (img == null) img = blockTextures.get(
                                "block/" + stripped
                            );
                        }
                    }
                    if (img != null) {
                        unique.putIfAbsent(
                            cand,
                            new LoadedImage(cand, ensureRGBA(img))
                        );
                    } else {
                        System.out.println(
                            "[ServerTextureAtlas] Missing referenced texture: " +
                            cand
                        );
                    }
                }
            }

            // Ensure explicit fallback tile is always present in atlas
            unique.putIfAbsent(
                "minecraft:block/fallback",
                new LoadedImage(
                    "minecraft:block/fallback",
                    ensureRGBA(fallbackTexture(16, 16))
                )
            );
            {
                int count = unique.size();
                System.out.println(
                    "[ServerTextureAtlas] Packing " +
                    count +
                    " textures into atlas (dir)"
                );
                boolean ok = buildAtlas(new ArrayList<>(unique.values()));
                if (ok) {
                    System.out.println(
                        "[ServerTextureAtlas] Atlas ready: " +
                        atlasWidth +
                        "x" +
                        atlasHeight +
                        ", regions=" +
                        regionByTexture.size()
                    );
                } else {
                    System.err.println(
                        "[ServerTextureAtlas] Failed to build atlas from directory"
                    );
                }
                return ok;
            }
        } else {
            return loadFromZip(packPath);
        }
    }

    /**
     * Read block textures from a directory-based resource pack.
     * Supports all namespaces: assets/<namespace>/textures/block/*.png
     */
    private Map<String, BufferedImage> readBlockTexturesFromDir(Path packDir) {
        Map<String, BufferedImage> out = new HashMap<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(packDir)) {
            stream
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String rel = packDir.relativize(p).toString();
                    String lower = rel
                        .replace('\\', '/')
                        .toLowerCase(Locale.ROOT);
                    if (
                        !lower.startsWith("assets/") ||
                        !lower.endsWith(".png") ||
                        lower.indexOf("/textures/block/") < 0
                    ) {
                        return;
                    }
                    try (InputStream in = Files.newInputStream(p)) {
                        BufferedImage img = ImageIO.read(in);
                        if (img == null) return;

                        int nsStart = "assets/".length();
                        int nsEnd = lower.indexOf('/', nsStart);
                        String namespace = (nsEnd > nsStart)
                            ? lower.substring(nsStart, nsEnd)
                            : "minecraft";

                        String shortName = "block/" + baseName(lower);
                        String namespaced = namespace + ":" + shortName;

                        out.put(shortName, img);
                        out.put(namespaced, img);
                    } catch (IOException io) {
                        System.err.println(
                            "[ServerTextureAtlas] Failed reading PNG from dir: " +
                            p +
                            " : " +
                            io
                        );
                    }
                });
        } catch (IOException e) {
            System.err.println(
                "[ServerTextureAtlas] Failed walking pack dir: " + e
            );
        }
        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Debug helpers (optional)
    // --------------------------------------------------------------------------------------------

    /**
     * Write the current atlas PNG for inspection.
     */
    public boolean writeDebugAtlas(Path outPng) {
        BufferedImage img = this.atlas;
        if (img == null) return false;
        try {
            Path parent = outPng.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return ImageIO.write(img, "png", outPng.toFile());
        } catch (IOException e) {
            System.err.println(
                "[ServerTextureAtlas] Failed to write debug atlas: " + e
            );
            return false;
        }
    }

    /**
     * Override or extend the mapping of a block key to a texture logical name.
     * Example name formats accepted: "minecraft:block/stone" or "block/stone".
     */
    public void putKeyMapping(String blockKey, String textureLogicalName) {
        if (blockKey == null || textureLogicalName == null) return;
        keyToTexture.put(
            blockKey.toLowerCase(Locale.ROOT),
            canonical(textureLogicalName)
        );
    }

    /**
     * Override or extend the per-face mapping of a block key.
     * Face hint expected values: "top", "bottom", "side".
     * Example name formats accepted: "minecraft:block/stone" or "block/stone".
     */
    public void putFaceKeyMapping(
        String blockKey,
        String faceHint,
        String textureLogicalName
    ) {
        if (
            blockKey == null || faceHint == null || textureLogicalName == null
        ) return;
        String face = faceHint.toLowerCase(Locale.ROOT);
        keyFaceToTexture.put(
            (blockKey.toLowerCase(Locale.ROOT) + "#" + face),
            canonical(textureLogicalName)
        );
    }

    /**
     * @return immutable snapshot of current key->texture mapping
     */
    public Map<String, String> getKeyMapping() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(keyToTexture));
    }

    /**
     * @return immutable snapshot of current texture->region mapping (valid only after load)
     */
    public Map<String, Region> getTextureRegions() {
        return Collections.unmodifiableMap(
            new LinkedHashMap<>(regionByTexture)
        );
    }

    /**
     * Returns true if the atlas contains a region for the given logical name.
     * Accepts "minecraft:block/stone", "block/stone" or just "stone".
     */
    public boolean hasTexture(String logicalName) {
        if (logicalName == null || logicalName.isEmpty()) return false;
        if (regionByTexture.containsKey(canonical(logicalName))) return true;
        String stripped = stripNamespace(logicalName);
        if (regionByTexture.containsKey(stripped)) return true;
        if (stripped.indexOf('/') < 0) {
            if (
                regionByTexture.containsKey("minecraft:block/" + stripped)
            ) return true;
            if (regionByTexture.containsKey("block/" + stripped)) return true;
        }
        return false;
    }

    /**
     * Resolve a logical texture name into a UV region if present, otherwise null.
     * Accepts names with or without namespace.
     */
    public Region getRegionByLogicalName(String logicalName) {
        if (logicalName == null || logicalName.isEmpty()) return null;
        Region r = regionByTexture.get(canonical(logicalName));
        if (r == null) r = regionByTexture.get(stripNamespace(logicalName));
        if (r == null) {
            String stripped = stripNamespace(logicalName);
            if (stripped.indexOf('/') < 0) {
                r = regionByTexture.get("minecraft:block/" + stripped);
                if (r == null) r = regionByTexture.get("block/" + stripped);
            }
        }
        return r;
    }

    /**
     * Resolve a UV region by exact blockId using resource-pack models and blockstates.
     * faceHint can be "top","bottom","north","south","west","east","side" (defaults to "side").
     * Falls back to category-key lookup when model resolution is unavailable.
     */
    public Region getRegionForBlockFaceByBlockId(
        String blockId,
        String faceHint
    ) {
        ensureLoaded();
        if (blockId != null && this.packIndex != null) {
            var opt = this.packIndex.get(blockId);
            if (opt.isPresent()) {
                net.vulkanmod.server.pack.ResolvedModel rm = opt.get();
                String face = (faceHint == null
                        ? "side"
                        : faceHint.toLowerCase(java.util.Locale.ROOT));
                String logicalName = null;

                switch (rm.getRenderType()) {
                    case BILLBOARD_CROSS, BILLBOARD_CROSS_TINTED -> {
                        logicalName = rm.getCrossSprite();
                    }
                    case WATER -> {
                        logicalName = "side".equals(face)
                            ? "minecraft:block/water_flow"
                            : "minecraft:block/water_still";
                    }
                    default -> {
                        net.vulkanmod.server.pack.ResolvedModel.Face f =
                            switch (face) {
                                case "top" -> net.vulkanmod.server.pack.ResolvedModel.Face.TOP;
                                case "bottom" -> net.vulkanmod.server.pack.ResolvedModel.Face.BOTTOM;
                                case
                                    "north",
                                    "side" -> net.vulkanmod.server.pack.ResolvedModel.Face.NORTH;
                                case "south" -> net.vulkanmod.server.pack.ResolvedModel.Face.SOUTH;
                                case "west" -> net.vulkanmod.server.pack.ResolvedModel.Face.WEST;
                                case "east" -> net.vulkanmod.server.pack.ResolvedModel.Face.EAST;
                                default -> net.vulkanmod.server.pack.ResolvedModel.Face.NORTH;
                            };
                        logicalName = rm.getFaceTexture(f);
                    }
                }

                if (logicalName != null) {
                    Region r = getRegionByLogicalName(logicalName);
                    if (r != null) return r;
                }
            }
        }
        // Fallback to category-key approach with heuristics to reduce checkerboards when PackIndex misses
        {
            String bid = stripNamespace(
                blockId == null ? "default" : blockId
            ).toLowerCase(java.util.Locale.ROOT);
            String face = (faceHint == null
                    ? "side"
                    : faceHint.toLowerCase(java.util.Locale.ROOT));
            String keyCompat = null;

            if ("grass_block".equals(bid)) keyCompat = "grass";
            else if (bid.endsWith("_log")) keyCompat = "wood";
            else if (bid.endsWith("_wood")) keyCompat = "wood";
            else if (bid.endsWith("_leaves")) keyCompat = "leaves";
            else if ("water".equals(bid) || bid.endsWith("_water")) keyCompat =
                "water";
            else if (
                bid.endsWith("_glass") || bid.endsWith("_glass_pane")
            ) keyCompat = "glass";
            else if (bid.endsWith("_pane")) keyCompat = "glass";
            else if (bid.endsWith("_planks")) keyCompat = "planks";
            else if (
                "stone".equals(bid) ||
                bid.endsWith("_stone") ||
                bid.endsWith("_stone_bricks")
            ) keyCompat = "stone";
            else if (
                "sand".equals(bid) ||
                bid.endsWith("_sand") ||
                bid.endsWith("_sandstone")
            ) keyCompat = "sandstone";
            else if (bid.contains("rail")) keyCompat = "rail";
            else if (bid.endsWith("_door")) keyCompat = "door";
            else if (bid.endsWith("_trapdoor")) keyCompat = "trapdoor";
            else if (
                bid.endsWith("_fence") || bid.endsWith("_fence_gate")
            ) keyCompat = "planks";
            else if (bid.endsWith("_slab")) keyCompat = "planks";
            else if (bid.endsWith("_stairs")) keyCompat = "planks";
            else if ("cactus".equals(bid)) keyCompat = "cactus";
            else if (bid.contains("ice")) keyCompat = "ice";
            else if (bid.contains("honey")) keyCompat = "honey";

            if (keyCompat != null) {
                return getRegionForBlockFace(keyCompat, face);
            }
            return getRegionForBlockFace(bid, face);
        }
    }

    /**
     * Return the render type for a blockId based on resolved model; defaults to SOLID.
     */
    public net.vulkanmod.server.pack.RenderType getRenderTypeForBlockId(
        String blockId
    ) {
        if (blockId != null && this.packIndex != null) {
            var opt = this.packIndex.get(blockId);
            if (opt.isPresent()) {
                return opt.get().getRenderType();
            }
        }
        return net.vulkanmod.server.pack.RenderType.SOLID;
    }

    // Convenience: load the newest .zip from <gameDir>/resourcepacks if present
    public boolean loadDefaultFromGameDir(Path gameDir) {
        Optional<Path> zip = findDefaultResourcePack(gameDir);
        return zip.isPresent() && loadFromZip(zip.get());
    }

    // Find most recently modified .zip under <gameDir>/resourcepacks
    public static Optional<Path> findDefaultResourcePack(Path gameDir) {
        if (gameDir == null) return Optional.empty();
        Path rp = gameDir.resolve("resourcepacks");
        if (!Files.isDirectory(rp)) return Optional.empty();
        try (java.util.stream.Stream<Path> stream = Files.list(rp)) {
            return stream
                .filter(p -> {
                    if (Files.isRegularFile(p)) {
                        String name = p
                            .getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT);
                        return name.endsWith(".zip");
                    } else if (Files.isDirectory(p)) {
                        // Directory pack: contains pack.mcmeta or assets/*
                        if (Files.exists(p.resolve("pack.mcmeta"))) return true;
                        Path assets = p.resolve("assets");
                        if (Files.isDirectory(assets)) {
                            try (
                                java.util.stream.Stream<Path> s2 = Files.walk(
                                    assets,
                                    2
                                )
                            ) {
                                return s2.anyMatch(q ->
                                    q
                                        .toString()
                                        .replace('\\', '/')
                                        .toLowerCase(Locale.ROOT)
                                        .contains("/textures/block/")
                                );
                            } catch (IOException e) {
                                return false;
                            }
                        }
                    }
                    return false;
                })
                .sorted((a, b) -> {
                    try {
                        long mb = Files.getLastModifiedTime(b).toMillis();
                        long ma = Files.getLastModifiedTime(a).toMillis();
                        return Long.compare(mb, ma);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
