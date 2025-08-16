package net.vulkanmod.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side initializer for VulkanMod-Server.
 * This provides the server-side rendering capabilities that can be used
 * by other mods (like the SurvivalMode bot control mod).
 */
public class ServerInitializer implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "VulkanMod-Server"
    );

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing VulkanMod-Server for server-side rendering");

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        LOGGER.info("VulkanMod-Server initialization complete");
    }

    private void onServerStarted(MinecraftServer server) {
        LOGGER.info("Server started, initializing VulkanMod ServerRenderer");

        try {
            ServerRenderer renderer = ServerRenderer.getInstance();
            if (renderer.initialize(server)) {
                LOGGER.info(
                    "VulkanMod ServerRenderer initialized successfully"
                );
            } else {
                LOGGER.warn(
                    "VulkanMod ServerRenderer initialization failed - headless rendering unavailable"
                );
            }
        } catch (Exception e) {
            LOGGER.error("Exception during ServerRenderer initialization", e);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("Server stopping, shutting down VulkanMod ServerRenderer");

        try {
            ServerRenderer.getInstance().shutdown();
        } catch (Exception e) {
            LOGGER.error("Error shutting down ServerRenderer", e);
        }
    }
}
