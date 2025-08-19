package net.vulkanmod.server.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Server-side index of block models from a resource pack.
 *
 * Responsibilities:
 * - Discover and parse assets/<namespace>/models/block/*.json into a raw model map.
 * - Resolve a given namespaced model id (e.g., "minecraft:block/cactus") by:
 *   * Walking its parent chain
 *   * Merging/flattening the "textures" maps with support for "#var" indirections
 *   * Mapping known vanilla templates to a compact ResolvedModel (face textures + render type)
 *
 * Scope (Step A, minimal):
 * - Supports these templates:
 *   - minecraft:block/cube_all
 *   - minecraft:block/cube_bottom_top
 *   - minecraft:block/cube_column
 *   - minecraft:block/cactus
 *   - minecraft:block/cross
 *   - minecraft:block/tinted_cross
 *   - minecraft:block/water
 * - Ignores elements/multipart/rotation/UV locking/etc.
 * - Unknown templates return Optional.empty() and log a one-time notice.
 */
public final class ModelIndex {

    private final Map<String, JsonObject> rawModels = new HashMap<>();
    private final Map<String, ResolvedModel> resolved = new HashMap<>();
    private final Set<String> warnedTemplates = new HashSet<>();
    private final Gson gson = new Gson();

    /**
     * Read all block models from a resource pack ZIP into memory.
     * Stores models keyed by namespaced id without ".json", e.g., "minecraft:block/stone".
     */
    public void indexFromZip(Path zip) {
        if (zip == null || !Files.isRegularFile(zip)) return;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                String lower = n.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/")) continue;
                if (!lower.endsWith(".json")) continue;
                int iModels = lower.indexOf("/models/block/");
                if (iModels < 0) continue;

                // Extract namespace
                int iNsStart = "assets/".length();
                int iNsEnd = lower.indexOf('/', iNsStart);
                if (iNsEnd < 0) continue;
                String namespace = lower.substring(iNsStart, iNsEnd);
                String fileBase = baseName(lower);
                String modelId = namespace + ":block/" + fileBase;

                try (InputStream in = zf.getInputStream(e);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    JsonElement el = gson.fromJson(r, JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        rawModels.put(modelId, el.getAsJsonObject());
                    }
                } catch (Throwable t) {
                    System.err.println("[ModelIndex] Failed reading model JSON from zip: " + n + " : " + t);
                }
            }
        } catch (IOException io) {
            System.err.println("[ModelIndex] Failed opening pack zip: " + zip + " : " + io);
        }
    }

    /**
     * Read all block models from a resource pack directory (supports multiple namespaces).
     * Looks for assets/<namespace>/models/block/*.json.
     */
    public void indexFromDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/")) return;
                if (!lower.endsWith(".json")) return;
                int iModels = lower.indexOf("/models/block/");
                if (iModels < 0) return;

                int iNsStart = "assets/".length();
                int iNsEnd = lower.indexOf('/', iNsStart);
                if (iNsEnd < 0) return;
                String namespace = lower.substring(iNsStart, iNsEnd);
                String fileBase = baseName(lower);
                String modelId = namespace + ":block/" + fileBase;

                try (InputStream in = Files.newInputStream(p);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    JsonElement el = gson.fromJson(r, JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        rawModels.put(modelId, el.getAsJsonObject());
                    }
                } catch (Throwable t) {
                    System.err.println("[ModelIndex] Failed reading model JSON from dir: " + p + " : " + t);
                }
            });
        } catch (IOException io) {
            System.err.println("[ModelIndex] Failed walking pack dir: " + dir + " : " + io);
        }
    }

    /**
     * Resolve a namespaced model id (e.g., "minecraft:block/cactus") into a ResolvedModel,
     * applying parent-chain texture merging and template mapping.
     */
    public Optional<ResolvedModel> resolve(String namespacedModel) {
        if (namespacedModel == null || namespacedModel.isEmpty()) return Optional.empty();
        String modelId = canonicalizeModelName(namespacedModel);
        ResolvedModel cached = resolved.get(modelId);
        if (cached != null) return Optional.of(cached);

        // Walk parent chain and collect/merge textures
        Map<String, String> mergedTextures = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        String template = resolveParentChain(modelId, mergedTextures, visiting);

        // Flatten any "#key" indirections in textures (resolve with recursion and cycle guard)
        Map<String, String> flat = new HashMap<>();
        for (Map.Entry<String, String> e : mergedTextures.entrySet()) {
            flat.put(e.getKey(), resolveTextureRef(e.getValue(), mergedTextures, 8));
        }

        // Map template -> ResolvedModel
        ResolvedModel rm = mapTemplateToModel(template, flat);
        if (rm != null) {
            resolved.put(modelId, rm);
            return Optional.of(rm);
        }

        // Unknown/unhandled
        if (template != null && warnedTemplates.add(template)) {
            System.out.println("[ModelIndex] Unhandled model template: template='" + template + "' for model='" + modelId + "'");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------

    private static String baseName(String pathLower) {
        int slash = pathLower.lastIndexOf('/');
        String file = (slash >= 0) ? pathLower.substring(slash + 1) : pathLower;
        if (file.endsWith(".json")) file = file.substring(0, file.length() - 5);
        return file;
    }

    private static String canonicalizeModelName(String nameIn) {
        if (nameIn == null) return null;
        String s = nameIn.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.endsWith(".json")) s = s.substring(0, s.length() - 5);
        if (!s.contains(":")) {
            // Add default namespace
            s = "minecraft:" + s;
        }
        // If no folder segment after the namespace, assume "block/"
        int iColon = s.indexOf(':');
        if (iColon >= 0) {
            String after = s.substring(iColon + 1);
            if (!after.contains("/")) {
                s = s.substring(0, iColon + 1) + "block/" + after;
            }
        }
        return s;
    }

    private static String canonicalizeTextureName(String ref) {
        if (ref == null) return null;
        String s = ref.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) return s; // unresolved indirection stays as "#"
        if (s.contains(":")) return s;   // already namespaced
        if (s.contains("/")) {
            // Likely "block/stone"
            return "minecraft:" + s;
        }
        // Bare name -> assume block/<name> under minecraft
        return "minecraft:block/" + s;
    }

    private String resolveParentChain(String modelId,
                                      Map<String, String> outTextures,
                                      Set<String> visiting) {
        String id = canonicalizeModelName(modelId);
        if (!visiting.add(id)) {
            System.err.println("[ModelIndex] Detected parent cycle at model '" + id + "'");
            return null;
        }
        JsonObject obj = rawModels.get(id);
        String template = null;

        // Recurse to parent first
        String parent = getString(obj, "parent");
        if (parent != null && !parent.isEmpty()) {
            String parentCanon = canonicalizeModelName(parent);
            // Known templates may be directly referenced as parent
            template = knownTemplateOrNull(parentCanon);
            if (template == null) {
                // Walk further up if it's not a terminal template
                String t2 = resolveParentChain(parentCanon, outTextures, visiting);
                if (t2 != null) template = t2;
            }
        }

        // Merge this model's textures (child overrides parent)
        JsonObject texObj = (obj != null && obj.has("textures") && obj.get("textures").isJsonObject())
            ? obj.getAsJsonObject("textures") : null;
        if (texObj != null) {
            for (Map.Entry<String, JsonElement> e : texObj.entrySet()) {
                String key = e.getKey();
                String val = (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString())
                    ? e.getValue().getAsString()
                    : null;
                if (val != null) {
                    outTextures.put(key, canonicalizeTextureName(val));
                }
            }
        }

        // If this very node is a well-known template, prefer it
        if (template == null) {
            template = knownTemplateOrNull(id);
        }

        visiting.remove(id);
        return template;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        if (!el.getAsJsonPrimitive().isString()) return null;
        String s = el.getAsString();
        if (s == null) return null;
        return s.trim();
    }

    private static String resolveTextureRef(String ref,
                                            Map<String, String> textures,
                                            int depth) {
        if (ref == null) return null;
        String cur = ref;
        int iter = Math.max(1, depth);
        while (iter-- > 0 && cur.startsWith("#")) {
            String key = cur.substring(1);
            String next = textures.get(key);
            if (next == null || next.equals(cur)) break;
            cur = next;
        }
        return canonicalizeTextureName(cur);
    }

    private static String knownTemplateOrNull(String modelId) {
        // Normalize to namespaced id
        String id = canonicalizeModelName(modelId);
        // Match by suffix to be tolerant of non-minecraft namespaces referencing vanilla parents.
        if (id.endsWith("/cube_all")) return "cube_all";
        if (id.endsWith("/cube_bottom_top")) return "cube_bottom_top";
        if (id.endsWith("/cube_column")) return "cube_column";
        if (id.endsWith("/cactus")) return "cactus";
        if (id.endsWith("/cross")) return "cross";
        if (id.endsWith("/tinted_cross")) return "tinted_cross";
        if (id.endsWith("/water")) return "water";
        return null;
    }

    private ResolvedModel mapTemplateToModel(String template, Map<String, String> textures) {
        if (template == null) return null;
        switch (template) {
            case "cube_all" -> {
                String all = textures.get("all");
                if (all == null) return null;
                return ResolvedModel.cubeAll(all);
            }
            case "cube_bottom_top" -> {
                String top = textures.get("top");
                String bottom = textures.get("bottom");
                String side = textures.get("side");
                if (top == null || bottom == null || side == null) return null;
                return ResolvedModel.cubeBottomTop(top, bottom, side);
            }
            case "cube_column" -> {
                String end = textures.get("end");
                String side = textures.get("side");
                if (end == null || side == null) return null;
                return ResolvedModel.cubeColumn(end, side);
            }
            case "cactus" -> {
                String top = textures.get("top");
                String bottom = textures.get("bottom");
                String side = textures.get("side");
                if (top == null || bottom == null || side == null) return null;
                return ResolvedModel.cactus(top, bottom, side);
            }
            case "cross" -> {
                String cross = textures.get("cross");
                if (cross == null) return null;
                return ResolvedModel.cross(cross);
            }
            case "tinted_cross" -> {
                String cross = textures.get("cross");
                if (cross == null) return null;
                return ResolvedModel.tintedCross(cross);
            }
            case "water" -> {
                // Selection of still vs flow is deferred to atlas lookup.
                return ResolvedModel.water();
            }
            default -> {
                return null;
            }
        }
    }
}
