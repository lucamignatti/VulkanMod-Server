package net.vulkanmod.server.pack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server-side index of blockstates from a resource pack.
 *
 * Responsibilities (Step A scope):
 * - Discover and parse assets/<namespace>/blockstates/*.json into a raw state map (blockId -> JsonObject).
 * - Provide a minimal primary-model picker that:
 *   * Ignores multipart (returns empty and logs once).
 *   * Supports variants:
 *     - Prefer the "" (default) variant when present.
 *     - Otherwise, if there is exactly one variant entry, return its model.
 *     - If the selected variant value is an array, pick the first element.
 *   * Returns the selected "model" as a namespaced path (e.g., "minecraft:block/stone").
 *
 * Notes:
 * - Canonicalization:
 *   * If the returned model lacks a namespace, "minecraft:" is assumed.
 *   * If it lacks a folder segment after the namespace, "block/" is assumed.
 */
public final class BlockstateIndex {

    private final Map<String, JsonObject> rawStates = new HashMap<>();
    private final Set<String> warnedMultipart = new HashSet<>();
    private final Set<String> warnedAmbiguous = new HashSet<>();
    private final Gson gson = new Gson();

    /**
     * Read all blockstates from a resource pack ZIP into memory.
     * Keys are block ids like "minecraft:stone".
     */
    public void indexFromZip(Path zip) {
        if (zip == null || !Files.isRegularFile(zip)) return;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                String lower = n.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/")) continue;
                if (!lower.endsWith(".json")) continue;
                int idx = lower.indexOf("/blockstates/");
                if (idx < 0) continue;

                // assets/<namespace>/blockstates/<file>.json
                int nsStart = "assets/".length();
                int nsEnd = lower.indexOf('/', nsStart);
                if (nsEnd < 0) continue;
                String namespace = lower.substring(nsStart, nsEnd);
                String base = baseName(lower); // without .json
                String blockId = namespace + ":" + base;

                try (InputStream in = zf.getInputStream(e);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    JsonElement el = gson.fromJson(r, JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        rawStates.put(blockId, el.getAsJsonObject());
                    }
                } catch (Throwable t) {
                    System.err.println("[BlockstateIndex] Failed reading blockstate JSON from zip: " + n + " : " + t);
                }
            }
        } catch (IOException io) {
            System.err.println("[BlockstateIndex] Failed opening pack zip: " + zip + " : " + io);
        }
    }

    /**
     * Read all blockstates from a resource pack directory (supports multiple namespaces).
     * Looks for assets/<namespace>/blockstates/*.json.
     */
    public void indexFromDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/")) return;
                if (!lower.endsWith(".json")) return;
                int idx = lower.indexOf("/blockstates/");
                if (idx < 0) return;

                // assets/<namespace>/blockstates/<file>.json
                int nsStart = "assets/".length();
                int nsEnd = lower.indexOf('/', nsStart);
                if (nsEnd < 0) return;
                String namespace = lower.substring(nsStart, nsEnd);
                String base = baseName(lower);
                String blockId = namespace + ":" + base;

                try (InputStream in = Files.newInputStream(p);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    JsonElement el = gson.fromJson(r, JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        rawStates.put(blockId, el.getAsJsonObject());
                    }
                } catch (Throwable t) {
                    System.err.println("[BlockstateIndex] Failed reading blockstate JSON from dir: " + p + " : " + t);
                }
            });
        } catch (IOException io) {
            System.err.println("[BlockstateIndex] Failed walking pack dir: " + dir + " : " + io);
        }
    }

    /**
     * Minimal primary-model selection for Step A.
     * - Returns model id (namespaced, canonical) if a single/default variant is available.
     * - Returns empty for multipart or ambiguous multi-variant states.
     */
    public Optional<String> pickPrimaryModel(String blockId) {
        if (blockId == null || blockId.isEmpty()) return Optional.empty();
        String id = canonicalizeBlockId(blockId);
        JsonObject obj = rawStates.get(id);
        if (obj == null) return Optional.empty();

        // Multipart is out of scope for Step A
        if (obj.has("multipart")) {
            if (warnedMultipart.add(id)) {
                System.out.println("[BlockstateIndex] Multipart unsupported for blockId='" + id + "'");
            }
            return Optional.empty();
        }

        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) {
            return Optional.empty();
        }

        JsonObject variants = obj.getAsJsonObject("variants");
        if (variants.size() == 0) return Optional.empty();

        JsonElement chosen = null;

        // Prefer the default "" variant if present
        if (variants.has("")) {
            chosen = variants.get("");
        } else if (variants.size() == 1) {
            // Exactly one entry -> use it regardless of key content (e.g., "axis=y")
            Map.Entry<String, JsonElement> only = variants.entrySet().iterator().next();
            chosen = only.getValue();
        } else {
            // Ambiguous multi-variant state (property-dependent). Out of scope for Step A.
            if (warnedAmbiguous.add(id)) {
                System.out.println("[BlockstateIndex] Ambiguous variants (multiple entries) for blockId='" + id + "', no default ''. Ignoring for Step A.");
            }
            return Optional.empty();
        }

        // Extract model from chosen element (object or array-of-objects)
        String model = extractModelField(chosen);
        if (model == null || model.isEmpty()) return Optional.empty();
        return Optional.of(canonicalizeModelName(model));
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

    private static String canonicalizeBlockId(String blockId) {
        String s = blockId.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        return s;
    }

    /**
     * Canonicalize a model id:
     * - Ensure namespace ("minecraft:" default).
     * - Ensure a folder segment after namespace (assume "block/").
     */
    private static String canonicalizeModelName(String model) {
        if (model == null) return null;
        String s = model.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.endsWith(".json")) s = s.substring(0, s.length() - 5);
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        // After namespace, ensure it contains a path segment
        int iColon = s.indexOf(':');
        if (iColon >= 0) {
            String after = s.substring(iColon + 1);
            if (!after.contains("/")) {
                s = s.substring(0, iColon + 1) + "block/" + after;
            }
        }
        return s;
    }

    /**
     * Extract a "model" string from a variant element, which can be either:
     * - JsonObject with "model" field
     * - JsonArray of such JsonObjects (weighted variants) => first element wins
     */
    private static String extractModelField(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;

        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("model") && obj.get("model").isJsonPrimitive()) {
                return obj.get("model").getAsString();
            }
            // Some packs might nest {"model": "...", "x": 90, ...} only. If missing, nothing to do.
            return null;
        }

        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() == 0) return null;
            JsonElement first = arr.get(0);
            if (first != null && first.isJsonObject()) {
                JsonObject obj = first.getAsJsonObject();
                if (obj.has("model") && obj.get("model").isJsonPrimitive()) {
                    return obj.get("model").getAsString();
                }
            }
        }

        return null;
    }
}
