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
 * - Expose normalized UV regions per "block key" (keys used by server mesher like "grass","stone",...)
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
 * - If some textures are missing in the pack, a magenta checkerboard fallback will be used.
 * - The "block key" -> "texture name" mapping is intentionally small for now and aimed at the
 *   categories exposed by WorldSnapshotAccessor/MeshBuilder.
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

    // Mapping: texture logical name -> UV region
    private final Map<String, Region> regionByTexture =
        new ConcurrentHashMap<>();
    // Mapping: "block key" (mesher categories) -> texture logical name
    private final Map<String, String> keyToTexture = new ConcurrentHashMap<>();
    // Mapping: "block key#face" (e.g., grass#top, wood#side) -> texture logical name
    private final Map<String, String> keyFaceToTexture =
        new ConcurrentHashMap<>();

    // List of texture logical names required for our current categories
    private static final Map<String, String> DEFAULT_KEY_TO_BLOCK_TEXTURE =
        defaultKeyToTextureMapping();
    private static final Map<String, String> DEFAULT_FACE_KEY_TO_BLOCK_TEXTURE =
        defaultFaceKeyToTextureMapping();

    private ServerTextureAtlas() {
        // Prepare mapping table with defaults
        this.keyToTexture.putAll(DEFAULT_KEY_TO_BLOCK_TEXTURE);
        this.keyFaceToTexture.putAll(DEFAULT_FACE_KEY_TO_BLOCK_TEXTURE);
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

        // Build a minimal set of textures: at least those referenced by keyToTexture.
        Set<String> required = new LinkedHashSet<>(keyToTexture.values());
        // Include per-face mappings as well (e.g., grass#top, wood#side)
        required.addAll(keyFaceToTexture.values());
        // Also include their de-namespace short names if present (some packs have only "block/stone.png" form)
        for (String v : new ArrayList<>(required)) {
            if (v.startsWith("minecraft:")) {
                required.add(v.substring("minecraft:".length()));
            }
        }

        // Collect images to include
        List<LoadedImage> toPack = new ArrayList<>();
        for (String logicalName : required) {
            BufferedImage img = blockTextures.get(logicalName);
            if (img == null) {
                // Try alternative forms
                img = blockTextures.get(stripNamespace(logicalName));
            }
            if (img == null) {
                // Fallback to checkerboard
                img = fallbackTexture(16, 16);
                System.out.println(
                    "[ServerTextureAtlas] Missing texture " +
                    logicalName +
                    " -> using fallback"
                );
            }
            toPack.add(
                new LoadedImage(canonical(logicalName), ensureRGBA(img))
            );
        }

        // Deduplicate by name
        Map<String, LoadedImage> unique = new LinkedHashMap<>();
        for (LoadedImage li : toPack) {
            unique.putIfAbsent(li.name, li);
        }

        return buildAtlas(new ArrayList<>(unique.values()));
    }

    /**
     * Returns the UV region for a block key (e.g., "grass", "stone"...).
     * If no atlas is loaded or the key is unknown, a fallback region is returned (magenta checkerboard).
     */
    public Region getRegionForBlockKey(String blockKey) {
        if (blockKey == null) blockKey = "default";
        String textureName = keyToTexture.getOrDefault(
            blockKey.toLowerCase(Locale.ROOT),
            keyToTexture.get("default")
        );
        if (textureName == null) textureName = "minecraft:block/stone";

        Region r = regionByTexture.get(canonical(textureName));
        if (r == null) {
            // If atlas not loaded yet OR missing mapping, return the first region if available
            r = regionByTexture
                .values()
                .stream()
                .findFirst()
                .orElse(new Region(0f, 0f, 1f, 1f));
        }
        return r;
    }

    // Per-face region lookup: faceHint can be "top","bottom","side"; falls back to getRegionForBlockKey
    public Region getRegionForBlockFace(String blockKey, String faceHint) {
        if (blockKey == null) blockKey = "default";
        String face = (faceHint == null
                ? "side"
                : faceHint.toLowerCase(java.util.Locale.ROOT));
        String k = blockKey.toLowerCase(java.util.Locale.ROOT) + "#" + face;
        String textureName = keyFaceToTexture.get(k);
        if (textureName == null) {
            // try a reasonable fallback to "side"
            if (!"side".equals(face)) {
                textureName = keyFaceToTexture.get(
                    blockKey.toLowerCase(java.util.Locale.ROOT) + "#side"
                );
            }
            if (textureName == null) {
                // fallback to block-level mapping if no per-face mapping exists
                return getRegionForBlockKey(blockKey);
            }
        }
        Region r = regionByTexture.get(canonical(textureName));
        if (r == null) {
            r = regionByTexture
                .values()
                .stream()
                .findFirst()
                .orElse(new Region(0f, 0f, 1f, 1f));
        }
        return r;
    }

    /**
     * @return The atlas image (ARGB) for debugging. Do not mutate.
     */
    public BufferedImage getAtlasImage() {
        return atlas;
    }

    /**
     * Returns the atlas pixels encoded as tightly packed RGBA (8-8-8-8) in native byte order, row-major, no flips.
     * Returns null if the atlas is not loaded yet.
     */
    public ByteBuffer getAtlasPixelsRGBA() {
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
        return atlasWidth;
    }

    public int getAtlasHeight() {
        return atlasHeight;
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

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
            if (!lower.startsWith("assets/minecraft/textures/block/")) continue;
            if (!lower.endsWith(".png")) continue;

            try (InputStream in = zip.getInputStream(e)) {
                BufferedImage img = ImageIO.read(in);
                if (img == null) continue;

                // Logical name forms:
                //   "minecraft:block/<name>"
                //   "block/<name>"
                String shortName = "block/" + baseName(lower);
                String namespaced = "minecraft:" + shortName;

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
            return true;
        }

        // Grid packing parameters
        final int padding = 1; // 1px padding around each cell to reduce sampling bleed
        int cellSize = 0;
        for (LoadedImage li : textures) {
            cellSize = Math.max(
                cellSize,
                Math.max(li.img.getWidth(), li.img.getHeight())
            );
        }
        // Guarantee at least 16x16 cells
        cellSize = Math.max(cellSize, 16);
        final int cellStride = cellSize + padding * 2;

        int count = textures.size();
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) cols);

        int atlasW = cols * cellStride;
        int atlasH = rows * cellStride;

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
            int x = 0,
                y = 0,
                i = 0;
            this.regionByTexture.clear();

            for (LoadedImage li : textures) {
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

                    // Left/right 1px columns
                    // Left bleed
                    g.drawImage(
                        li.img,
                        dstX - 1,
                        dstY,
                        dstX,
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
                        dstX + iw,
                        dstY,
                        dstX + iw + 1,
                        dstY + ih,
                        iw - 1,
                        0,
                        iw,
                        ih,
                        null
                    );

                    // Top/bottom 1px rows
                    // Top bleed
                    g.drawImage(
                        li.img,
                        dstX - 1,
                        dstY - 1,
                        dstX + iw + 1,
                        dstY,
                        0,
                        0,
                        iw,
                        1,
                        null
                    );
                    // Bottom bleed
                    g.drawImage(
                        li.img,
                        dstX - 1,
                        dstY + ih,
                        dstX + iw + 1,
                        dstY + ih + 1,
                        0,
                        ih - 1,
                        iw,
                        ih,
                        null
                    );

                    // Corner pixels
                    // Top-left
                    g.drawImage(
                        li.img,
                        dstX - 1,
                        dstY - 1,
                        dstX,
                        dstY,
                        0,
                        0,
                        1,
                        1,
                        null
                    );
                    // Top-right
                    g.drawImage(
                        li.img,
                        dstX + iw,
                        dstY - 1,
                        dstX + iw + 1,
                        dstY,
                        iw - 1,
                        0,
                        iw,
                        1,
                        null
                    );
                    // Bottom-left
                    g.drawImage(
                        li.img,
                        dstX - 1,
                        dstY + ih,
                        dstX,
                        dstY + ih + 1,
                        0,
                        ih - 1,
                        1,
                        ih,
                        null
                    );
                    // Bottom-right
                    g.drawImage(
                        li.img,
                        dstX + iw,
                        dstY + ih,
                        dstX + iw + 1,
                        dstY + ih + 1,
                        iw - 1,
                        ih - 1,
                        iw,
                        ih,
                        null
                    );
                }

                // Compute normalized UVs for the content area (without padding).
                // We prefer to include only the inner content (not padding) in the UV range.
                float u0 = (dstX) / (float) atlasW;
                float v0 = (dstY) / (float) atlasH;
                float u1 = (dstX + li.img.getWidth()) / (float) atlasW;
                float v1 = (dstY + li.img.getHeight()) / (float) atlasH;

                this.regionByTexture.put(li.name, new Region(u0, v0, u1, v1));
                i++;
            }
        } finally {
            g.dispose();
        }

        this.atlas = out;
        this.atlasWidth = atlasW;
        this.atlasHeight = atlasH;
        return true;
    }

    // --------------------------------------------------------------------------------------------
    // Mapping for block keys (mesher) -> canonical texture names
    // --------------------------------------------------------------------------------------------

    private static Map<String, String> defaultKeyToTextureMapping() {
        // Keys currently produced by WorldSnapshotAccessor.classify(...)
        Map<String, String> m = new LinkedHashMap<>();
        // Explicit namespace for canonical lookups. We also accept short "block/<name>" during load.
        m.put("grass", "minecraft:block/grass_block_top");
        m.put("stone", "minecraft:block/stone");
        m.put("dirt", "minecraft:block/dirt");
        m.put("leaves", "minecraft:block/oak_leaves");
        m.put("wood", "minecraft:block/oak_log");
        m.put("sand", "minecraft:block/sand");
        m.put("water", "minecraft:block/water_still"); // transparent; acceptable for now
        m.put("glass", "minecraft:block/glass"); // transparent
        m.put("sandstone", "minecraft:block/sandstone");
        m.put("gravel", "minecraft:block/gravel");
        m.put("clay", "minecraft:block/clay");
        m.put("coal", "minecraft:block/coal_ore");
        m.put("iron", "minecraft:block/iron_ore");
        m.put("gold", "minecraft:block/gold_ore");
        m.put("diamond", "minecraft:block/diamond_ore");
        m.put("redstone", "minecraft:block/redstone_ore");
        m.put("lapis", "minecraft:block/lapis_ore");
        m.put("obsidian", "minecraft:block/obsidian");

        // Flora / non-full blocks (CUTOUT layer expected)
        m.put("short_grass", "minecraft:block/short_grass");
        m.put("tall_grass", "minecraft:block/tall_grass_top");
        m.put("fern", "minecraft:block/fern");
        m.put("dead_bush", "minecraft:block/dead_bush");
        m.put("kelp", "minecraft:block/kelp");
        m.put("torch", "minecraft:block/torch");

        m.put("default", "minecraft:block/stone");
        return m;
    }

    private static Map<String, String> defaultFaceKeyToTextureMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        // Per-face keys use the format "<blockKey>#<face>"
        // Grass: top, side, bottom (bottom uses dirt)
        m.put("grass#top", "minecraft:block/grass_block_top");
        m.put("grass#side", "minecraft:block/grass_block_side");
        m.put("grass#bottom", "minecraft:block/dirt");
        // Wood: side (bark) and top (log end)
        m.put("wood#side", "minecraft:block/oak_log");
        m.put("wood#top", "minecraft:block/oak_log_top");
        m.put("wood#bottom", "minecraft:block/oak_log_top");
        // Dirt: consistent texture for all faces
        m.put("dirt#top", "minecraft:block/dirt");
        m.put("dirt#side", "minecraft:block/dirt");
        m.put("dirt#bottom", "minecraft:block/dirt");
        // Stone: consistent texture for all faces
        m.put("stone#top", "minecraft:block/stone");
        m.put("stone#side", "minecraft:block/stone");
        m.put("stone#bottom", "minecraft:block/stone");
        return m;
    }

    // --------------------------------------------------------------------------------------------
    // Fallback texture utilities
    // --------------------------------------------------------------------------------------------

    private static BufferedImage fallbackTexture(int w, int h) {
        BufferedImage img = new BufferedImage(
            w,
            h,
            BufferedImage.TYPE_INT_ARGB
        );
        int c0 = 0xFFFF00FF; // magenta
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
                .filter(p -> Files.isRegularFile(p))
                .filter(p ->
                    p
                        .getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".zip")
                )
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
