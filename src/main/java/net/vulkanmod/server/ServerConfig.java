package net.vulkanmod.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-only configuration for the headless Vulkan renderer.
 *
 * This config is independent of any client classes and is safe to load on a dedicated server.
 * It stores:
 *  - captureWidth / captureHeight: output image size for RL captures
 *  - renderDistance: region radius in chunks to build/render around each bot (performance vs completeness)
 *
 * The file is stored in the Fabric config directory as: vulkanmod_server_renderer.json
 */
public final class ServerConfig {

    private static final String CONFIG_FILE_NAME = "vulkanmod_server_renderer.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // Reasonable defaults oriented for RL workloads
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 360;
    // Chunk radius around the bot (e.g., 8 means 17×17 chunk area checked/built); tune for your workload.
    private static final int DEFAULT_RENDER_DISTANCE = 8;

    // Hard clamps to avoid pathological values
    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 4096;
    private static final int MIN_HEIGHT = 90;
    private static final int MAX_HEIGHT = 2160;
    private static final int MIN_RENDER_DISTANCE = 2;
    private static final int MAX_RENDER_DISTANCE = 16;

    private static volatile ServerConfig INSTANCE;

    private final Path configPath;

    private volatile int captureWidth;
    private volatile int captureHeight;
    private volatile int renderDistance;

    private ServerConfig(Path configPath, int width, int height, int renderDistance) {
        this.configPath = configPath;
        this.captureWidth = width;
        this.captureHeight = height;
        this.renderDistance = renderDistance;
    }

    /**
     * Get the singleton instance, loading from disk if needed.
     */
    public static ServerConfig get() {
        ServerConfig local = INSTANCE;
        if (local != null) return local;

        synchronized (ServerConfig.class) {
            if (INSTANCE == null) {
                Path path = FabricLoader.getInstance()
                        .getConfigDir()
                        .resolve(CONFIG_FILE_NAME);
                INSTANCE = loadOrCreate(path);
            }
            return INSTANCE;
        }
    }

    /**
     * Reload the configuration from disk, keeping sane defaults if anything fails.
     */
    public void reload() {
        ServerConfig reloaded = loadOrCreate(this.configPath);
        this.captureWidth = reloaded.captureWidth;
        this.captureHeight = reloaded.captureHeight;
        this.renderDistance = reloaded.renderDistance;
    }

    private static ServerConfig loadOrCreate(Path path) {
        ConfigData data = null;

        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, ConfigData.class);
            } catch (IOException | JsonSyntaxException e) {
                // Fall through to defaults
            }
        }

        if (data == null) {
            data = new ConfigData();
            data.captureWidth = DEFAULT_WIDTH;
            data.captureHeight = DEFAULT_HEIGHT;
            data.renderDistance = DEFAULT_RENDER_DISTANCE;
            // Persist a fresh file with defaults to help users discover config knobs
            try {
                save(path, data);
            } catch (IOException ignored) {}
        }

        // Sanitize values
        data.captureWidth = clamp(data.captureWidth, MIN_WIDTH, MAX_WIDTH);
        data.captureHeight = clamp(data.captureHeight, MIN_HEIGHT, MAX_HEIGHT);
        data.renderDistance = clamp(data.renderDistance, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);

        return new ServerConfig(path, data.captureWidth, data.captureHeight, data.renderDistance);
    }

    private static void save(Path path, ConfigData data) throws IOException {
        // Ensure directory exists
        Path dir = path.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    /**
     * Width of the captured image (pixels).
     */
    public int getCaptureWidth() {
        return captureWidth;
    }

    /**
     * Height of the captured image (pixels).
     */
    public int getCaptureHeight() {
        return captureHeight;
    }

    /**
     * Render distance in chunks (radius) to mesh/render around each bot.
     * For example, 8 means ~17×17 chunk span is considered around the bot.
     */
    public int getRenderDistance() {
        return renderDistance;
    }

    /**
     * Update values in-memory and persist to disk.
     */
    public synchronized void updateAndSave(int width, int height, int renderDistance) throws IOException {
        ConfigData data = new ConfigData();
        data.captureWidth = clamp(width, MIN_WIDTH, MAX_WIDTH);
        data.captureHeight = clamp(height, MIN_HEIGHT, MAX_HEIGHT);
        data.renderDistance = clamp(renderDistance, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);

        save(this.configPath, data);

        this.captureWidth = data.captureWidth;
        this.captureHeight = data.captureHeight;
        this.renderDistance = data.renderDistance;
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "captureWidth=" + captureWidth +
                ", captureHeight=" + captureHeight +
                ", renderDistance=" + renderDistance +
                ", path=" + configPath +
                '}';
    }

    /**
     * JSON-serializable data holder.
     */
    private static final class ConfigData {
        int captureWidth;
        int captureHeight;
        int renderDistance;
    }
}
