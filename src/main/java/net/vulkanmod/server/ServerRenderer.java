package net.vulkanmod.server;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side rendering API for VulkanMod.
 * Provides headless rendering capabilities for server environments.
 */
public class ServerRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ServerRenderer.class
    );

    private static ServerRenderer INSTANCE;

    // Render configuration
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 360;

    // Server reference (generic object to avoid direct Minecraft server dependencies)
    private Object server;
    private boolean initialized = false;
    private final ServerConfig config = ServerConfig.get();

    // Per-bot render contexts
    private final Map<String, BotRenderContext> renderContexts =
        new ConcurrentHashMap<>();

    // Async rendering executor
    private ExecutorService renderExecutor;

    /**
     * Individual bot render context
     */
    public static class BotRenderContext {

        final String botId;
        final int width;
        final int height;

        // Vulkan resources
        long colorImage;
        long colorImageMemory;
        long colorImageView;
        // Simplified context - will be expanded when VulkanMod integration works
        boolean initialized;

        // Camera state
        double x = 0.0,
            y = 0.0,
            z = 0.0;
        float pitch = 0.0f;
        float yaw = 0.0f;

        BotRenderContext(String botId, int width, int height) {
            this.botId = botId;
            this.width = width;
            this.height = height;
        }

        public void updatePosition(
            double x,
            double y,
            double z,
            float pitch,
            float yaw
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        void cleanup() {
            // Simple cleanup for now
            initialized = false;
        }
    }

    private ServerRenderer() {
        this.renderExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(
                    r,
                    "VulkanServerRenderer-" + System.currentTimeMillis()
                );
                t.setDaemon(true);
                return t;
            }
        );
    }

    /**
     * Get the singleton instance
     */
    public static ServerRenderer getInstance() {
        if (INSTANCE == null) {
            synchronized (ServerRenderer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ServerRenderer();
                    // Auto-initialize headless rendering so callers (e.g. ImageCapture) can use server-side rendering
                    // without needing to explicitly call initialize(...).
                    try {
                        INSTANCE.initialize(null);
                    } catch (Throwable t) {
                        LOGGER.warn("ServerRenderer auto-initialize failed", t);
                    }
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Initialize the server renderer with headless Vulkan
     */
    public boolean initialize(Object server) {
        if (initialized) {
            LOGGER.info("ServerRenderer already initialized");
            return true;
        }

        this.server = server;

        try {
            LOGGER.info("Initializing VulkanMod ServerRenderer...");

            // Initialize headless Vulkan context
            if (!initializeHeadlessVulkan()) {
                LOGGER.error("Failed to initialize headless Vulkan");
                return false;
            }

            initialized = true;
            LOGGER.info("VulkanMod ServerRenderer initialized successfully");
            return true;
        } catch (Exception e) {
            LOGGER.error("Exception during ServerRenderer initialization", e);
            return false;
        }
    }

    /**
     * Initialize Vulkan in headless mode
     */
    private boolean initializeHeadlessVulkan() {
        try {
            LOGGER.info(
                "Initializing VulkanMod headless offscreen renderer..."
            );
            OffscreenWorldRenderer offscreen =
                OffscreenWorldRenderer.getInstance();
            boolean ok = offscreen.initializeHeadless();
            if (!ok || !offscreen.isAvailable()) {
                LOGGER.warn("Headless Vulkan offscreen renderer unavailable");
                return false;
            }
            LOGGER.info("Headless Vulkan offscreen renderer initialized");
            return true;
        } catch (Exception e) {
            LOGGER.error(
                "Failed to initialize headless Vulkan offscreen renderer",
                e
            );
            return false;
        }
    }

    /**
     * Get or create render context for a bot
     */
    public BotRenderContext getOrCreateBotContext(String botId) {
        return getOrCreateBotContext(
            botId,
            ServerConfig.get().getCaptureWidth(),
            ServerConfig.get().getCaptureHeight()
        );
    }

    /**
     * Get or create render context for a bot with custom dimensions
     */
    public BotRenderContext getOrCreateBotContext(
        String botId,
        int width,
        int height
    ) {
        if (!initialized) {
            LOGGER.warn("ServerRenderer not initialized for bot: {}", botId);
            return null;
        }

        return renderContexts.computeIfAbsent(botId, id -> {
            try {
                LOGGER.debug(
                    "Creating render context for bot: {} ({}x{})",
                    id,
                    width,
                    height
                );
                BotRenderContext context = new BotRenderContext(
                    id,
                    width,
                    height
                );

                if (initializeBotContext(context)) {
                    return context;
                } else {
                    LOGGER.error(
                        "Failed to initialize render context for bot: {}",
                        id
                    );
                    return null;
                }
            } catch (Exception e) {
                LOGGER.error(
                    "Exception creating render context for bot: {}",
                    id,
                    e
                );
                return null;
            }
        });
    }

    /**
     * Initialize render context for a bot
     */
    private boolean initializeBotContext(BotRenderContext context) {
        try {
            // Simplified initialization for now
            context.initialized = true;

            LOGGER.debug(
                "Initialized render context for bot: {}",
                context.botId
            );
            return true;
        } catch (Exception e) {
            LOGGER.error(
                "Failed to initialize bot context: {}",
                context.botId,
                e
            );
            return false;
        }
    }

    /**
     * Render world view for a bot asynchronously
     */
    public CompletableFuture<BufferedImage> renderBotViewAsync(
        String botId,
        double x,
        double y,
        double z,
        float pitch,
        float yaw
    ) {
        if (!initialized) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(
            () -> {
                try {
                    return renderBotView(botId, x, y, z, pitch, yaw);
                } catch (Exception e) {
                    LOGGER.error(
                        "Exception during async bot view render: {}",
                        botId,
                        e
                    );
                    return null;
                }
            },
            renderExecutor
        );
    }

    /**
     * Render world view for a bot synchronously
     */
    public BufferedImage renderBotView(
        String botId,
        double x,
        double y,
        double z,
        float pitch,
        float yaw
    ) {
        BotRenderContext context = getOrCreateBotContext(botId);
        if (context == null) {
            LOGGER.warn("No render context for bot: {}", botId);
            return null;
        }

        try {
            // Update camera position
            context.updatePosition(x, y, z, pitch, yaw);

            // TODO: Integrate with VulkanMod's actual world rendering
            // This would call VulkanMod's chunk rendering, entity rendering, etc.
            // but render to our off-screen framebuffer instead of the swapchain

            return renderWorldToImage(context);
        } catch (Exception e) {
            LOGGER.error("Exception rendering view for bot: {}", botId, e);
            return null;
        }
    }

    /**
     * Render the Minecraft world to an image.
     * This resolves the current ServerWorld (overworld) from the stored MinecraftServer
     * and forwards the bot camera (x,y,z,pitch,yaw) to the headless OffscreenWorldRenderer.
     * Note: OffscreenWorldRenderer currently resolves the ServerWorld internally via ServerRenderer.getServer().
     */
    private BufferedImage renderWorldToImage(BotRenderContext context) {
        try {
            // Resolve ServerLevel from stored server reference (non-blocking)
            net.minecraft.server.MinecraftServer mcServer =
                (getServer() instanceof net.minecraft.server.MinecraftServer s)
                    ? s
                    : null;
            net.minecraft.server.level.ServerLevel serverWorld = mcServer !=
                null
                ? mcServer.overworld()
                : null;

            // Use headless Vulkan offscreen renderer when available
            OffscreenWorldRenderer offscreen =
                OffscreenWorldRenderer.getInstance();
            if (offscreen != null && offscreen.isAvailable()) {
                // Forward camera; renderer will snapshot world and draw. If unavailable/failed, returns null to trigger fallback.
                BufferedImage img = offscreen.render(
                    context.x,
                    context.y,
                    context.z,
                    context.pitch,
                    context.yaw,
                    ServerConfig.get().getCaptureWidth(),
                    ServerConfig.get().getCaptureHeight(),
                    ServerConfig.get().getRenderDistance()
                );
                if (img != null) {
                    return img;
                }
                LOGGER.warn(
                    "OffscreenWorldRenderer returned null image, using fallback pattern"
                );
            } else {
                LOGGER.debug(
                    "OffscreenWorldRenderer not available, using fallback pattern"
                );
            }

            // Fallback to test pattern to keep server responsive
            return createTestPattern(context);
        } catch (Exception e) {
            LOGGER.error(
                "Exception during offscreen render for bot: {}",
                context.botId,
                e
            );
            return createFallbackImage(context);
        }
    }

    /**
     * Create test pattern as fallback when world rendering fails
     */
    private BufferedImage createTestPattern(BotRenderContext context) {
        BufferedImage image = new BufferedImage(
            context.width,
            context.height,
            BufferedImage.TYPE_INT_RGB
        );

        // Create a more vibrant pattern that changes based on position and time
        long time = System.currentTimeMillis() / 500;
        int positionHash =
            (int) (context.x * 10 + context.y + context.z * 5 + time) & 0xFF;

        // Ensure we get bright, distinguishable colors
        int baseR = 100 + (positionHash % 100);
        int baseG = 80 + ((positionHash * 2) % 120);
        int baseB = 60 + ((positionHash * 3) % 140);

        // Create a colorful pattern with geometric shapes
        for (int y = 0; y < context.height; y++) {
            for (int x = 0; x < context.width; x++) {
                int quadrant =
                    (x < context.width / 2 ? 0 : 1) +
                    (y < context.height / 2 ? 0 : 2);

                int r, g, b;
                switch (quadrant) {
                    case 0:
                        boolean stripe = ((x + y) / 20 + (int) time) % 2 == 0;
                        r = stripe ? baseR : baseR / 2;
                        g = stripe ? baseG / 2 : baseG;
                        b = stripe ? baseB / 2 : baseB / 2;
                        break;
                    case 1:
                        int centerX = (context.width * 3) / 4;
                        int centerY = context.height / 4;
                        int dist = (int) Math.sqrt(
                            (x - centerX) * (x - centerX) +
                            (y - centerY) * (y - centerY)
                        );
                        boolean ring = ((dist / 15 + (int) time) % 3) == 0;
                        r = ring ? baseR : baseR / 3;
                        g = ring ? baseG / 2 : baseG;
                        b = ring ? baseB : baseB / 3;
                        break;
                    case 2:
                        boolean check =
                            ((x / 25) + (y / 25) + (int) time) % 2 == 0;
                        r = check ? baseR / 2 : baseR;
                        g = check ? baseG : baseG / 3;
                        b = check ? baseB / 2 : baseB;
                        break;
                    default:
                        r = (baseR + ((x * 100) / context.width)) & 0xFF;
                        g = (baseG + ((y * 100) / context.height)) & 0xFF;
                        b =
                            (baseB +
                                (((x + y) * 50) /
                                    (context.width + context.height))) &
                            0xFF;
                        break;
                }

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                int rgb = (r << 16) | (g << 8) | b;
                image.setRGB(x, y, rgb);
            }
        }

        // Add text overlay indicating this is VulkanMod test pattern
        try {
            java.awt.Graphics2D g2d = image.createGraphics();
            g2d.setColor(new java.awt.Color(255, 255, 0));
            try {
                g2d.setFont(
                    new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14)
                );
            } catch (Exception fontEx) {
                // Use default font
            }
            g2d.setColor(new java.awt.Color(0, 0, 0, 128));
            g2d.fillRect(5, 5, 250, 90);
            g2d.setColor(new java.awt.Color(255, 255, 0));
            g2d.drawString("VulkanMod Test Pattern", 10, 20);
            g2d.drawString("Real rendering in fabric-mod", 10, 35);
            g2d.drawString(
                String.format(
                    "Pos: %.1f,%.1f,%.1f",
                    context.x,
                    context.y,
                    context.z
                ),
                10,
                50
            );
            g2d.drawString(
                String.format("Rot: %.1f,%.1f", context.pitch, context.yaw),
                10,
                65
            );
            g2d.dispose();
        } catch (Exception e) {
            // Text overlay failed - continue without it
        }

        return image;
    }

    /**
     * Create a fallback image when rendering fails
     */
    private BufferedImage createFallbackImage(BotRenderContext context) {
        LOGGER.warn("Creating fallback image for bot: {}", context.botId);

        BufferedImage image = new BufferedImage(
            context.width,
            context.height,
            BufferedImage.TYPE_INT_RGB
        );

        // Create a distinctive red-tinted pattern so we know this is a fallback
        for (int y = 0; y < context.height; y++) {
            for (int x = 0; x < context.width; x++) {
                // Create a red gradient with some pattern
                int intensity = (x + y) % 100;
                int r = Math.min(255, 150 + intensity);
                int g = Math.min(255, 50 + intensity / 2);
                int b = Math.min(255, 50 + intensity / 3);

                int rgb = (r << 16) | (g << 8) | b;
                image.setRGB(x, y, rgb);
            }
        }

        return image;
    }

    /**
     * Remove bot render context when bot disconnects
     */
    public void removeBotContext(String botId) {
        BotRenderContext context = renderContexts.remove(botId);
        if (context != null) {
            LOGGER.debug("Removing render context for bot: {}", botId);
            context.cleanup();
        }
    }

    /**
     * Check if server rendering is available and initialized
     */
    public boolean isAvailable() {
        // Report availability only when headless Vulkan is actually usable
        return (
            initialized &&
            server != null &&
            OffscreenWorldRenderer.getInstance() != null &&
            OffscreenWorldRenderer.getInstance().isAvailable()
        );
    }

    /**
     * Get statistics about active render contexts
     */
    public String getStatistics() {
        OffscreenWorldRenderer off = OffscreenWorldRenderer.getInstance();
        boolean offscreen = off != null && off.isAvailable();
        if (!initialized || server == null) {
            return "VulkanMod ServerRenderer: UNINITIALIZED";
        }
        if (!offscreen) {
            return (
                "VulkanMod ServerRenderer: AVAILABLE (no Vulkan offscreen) | contexts=" +
                renderContexts.size()
            );
        }
        return (
            "VulkanMod ServerRenderer: Headless Vulkan OK | contexts=" +
            renderContexts.size()
        );
    }

    /**
     * Get the Minecraft server instance
     */
    public Object getServer() {
        return server;
    }

    /**
     * Supply the MinecraftServer reference after startup.
     * Safe to call at any time; if the renderer wasn't initialized yet, this will initialize it.
     */
    public synchronized void setServer(Object server) {
        this.server = server;
        LOGGER.info(
            "ServerRenderer received server instance: {}",
            server != null
        );
        if (!initialized) {
            initialize(server);
        }
    }

    /**
     * Shutdown the server renderer
     */
    public void shutdown() {
        if (!initialized) {
            return;
        }

        LOGGER.info("Shutting down VulkanMod ServerRenderer...");

        // Cleanup all bot contexts
        for (BotRenderContext context : renderContexts.values()) {
            try {
                context.cleanup();
            } catch (Exception e) {
                LOGGER.error(
                    "Error cleaning up render context: {}",
                    context.botId,
                    e
                );
            }
        }
        renderContexts.clear();

        // Shutdown executor
        renderExecutor.shutdown();

        // Shutdown rendering resources
        try {
            OffscreenWorldRenderer off = OffscreenWorldRenderer.getInstance();
            if (off != null) {
                off.shutdown();
            }
            LOGGER.info("Server rendering resources cleaned up");
        } catch (Exception e) {
            LOGGER.error("Error during rendering cleanup", e);
        }

        initialized = false;
        server = null;

        LOGGER.info("VulkanMod ServerRenderer shutdown complete");
    }
}
