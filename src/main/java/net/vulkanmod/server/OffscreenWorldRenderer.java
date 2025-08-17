package net.vulkanmod.server;

//
// TODO(Phase 2): Integrate MeshBuilder + WorldSnapshotAccessor
// High-level plan to render real world views server-side (no client deps):
// - Snapshot server world region around the bot:
//   * Implement a ServerWorld-backed snapshot (WorldSnapshotAccessor) that reads block states and sky/block light.
//   * Bounds: e.g., 8x8 chunks around the bot, clamped to world Y range.
// - Build a mesh from the snapshot:
//   * Use MeshBuilder to emit only visible faces and generate a RegionMesh (interleaved vertex buffer + index buffer).
//   * Per-face color via LUT + simple lighting with sun direction and sky/block light.
//   * Cache RegionMesh per-region and reuse across frames to avoid full rebuilds.
// - Vulkan pipeline updates:
//   * Add vertex input state for interleaved attributes: position(3), normal(3), color(4).
//   * Add push constants/uniforms for MVP matrices derived from bot x,y,z,yaw,pitch (FOV≈70°, aspect 640x360).
// - Frame render flow in render(...):
//   * Build or fetch cached RegionMesh for the bot’s current region.
//   * Upload vertex/index data to device-local buffers (staging copy).
//   * Bind pipeline, set viewport/scissor, bind buffers, push MVP, and vkCmdDrawIndexed.
//   * Read back PNG as done today.
// - Robustness and fallback:
//   * If Vulkan init/compile/upload/draw fails, fall back to the existing ray/pattern paths so the server stays responsive.

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.vulkanmod.server.mesh.MeshBuilder;
import net.vulkanmod.server.mesh.RegionMesh;
import net.vulkanmod.server.mesh.WorldSnapshotAccessor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresource;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSubresourceLayout;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * OffscreenWorldRenderer implements a true server-only, headless Vulkan off-screen renderer.
 *
 * Phase 1 scope:
 * - Initialize a Vulkan device (headless) with a graphics queue.
 * - Create a minimal render pass with color + depth attachments.
 * - Create an off-screen framebuffer (color + depth images).
 * - Render a simple triangle using a pipeline that derives positions from gl_VertexIndex.
 * - Copy the framebuffer color image to a linear-tiled image and read back to CPU.
 * - Convert to a BufferedImage (PNG encoding handled by upstream API).
 *
 * Notes:
 * - This class avoids any client-side classes or APIs.
 * - It creates and owns a separate Vulkan instance/device for headless usage.
 * - If initialization fails (no ICD, etc.), it marks itself unavailable. Callers must fall back.
 * - Keep resources alive between frames and clean up on shutdown.
 */
public final class OffscreenWorldRenderer {

    // Diagnostics toggles
    private static final boolean LOG_CAMERA_BASIS = true; // Log camera basis vectors and computed roll
    private static final boolean TRANSPOSE_MVP_FOR_SHADER = false; // Toggle to send transposed MVP if shader expects row-major

    // Singleton
    private static volatile OffscreenWorldRenderer INSTANCE;

    public static OffscreenWorldRenderer getInstance() {
        if (INSTANCE == null) {
            synchronized (OffscreenWorldRenderer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OffscreenWorldRenderer();
                }
            }
        }
        return INSTANCE;
    }

    // Configuration
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 360;
    private static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    // A common depth format; in production we should probe for support, but for smoke test this is fine
    private static int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    // Availability
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);

    // Vulkan handles
    private org.lwjgl.vulkan.VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private org.lwjgl.vulkan.VkDevice device;
    private int graphicsQueueFamilyIndex = -1;
    private org.lwjgl.vulkan.VkQueue graphicsQueue;
    private long commandPool;

    // Render targets
    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private long colorImage;
    private long colorImageMemory;
    private long colorImageView;

    private long depthImage;
    private long depthImageMemory;
    private long depthImageView;

    private long framebuffer;

    // Texture atlas resources
    private volatile boolean atlasPrepared = false;
    private int atlasW = 0;
    private int atlasH = 0;
    private java.nio.ByteBuffer atlasPixels;
    private long atlasImage;
    private long atlasImageMemory;
    private long atlasImageView;
    private long atlasSampler;

    // Descriptor set for atlas sampling
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;

    private long renderPass;

    // Pipeline
    private long pipelineLayout;
    private long pipeline;
    private long pipelineCutout;
    private long vertModule;
    private long fragModule;
    private long fragModuleCutout;

    // Readback image (linear tiled, host-visible)
    private long readbackImage;
    private long readbackImageMemory;

    // Cached memory props for allocations
    private VkPhysicalDeviceMemoryProperties memProps;

    // Region mesh GPU cache
    private static final class RegionKey {

        final int rx, rz, size;

        RegionKey(int rx, int rz, int size) {
            this.rx = rx;
            this.rz = rz;
            this.size = size;
        }

        @Override
        public int hashCode() {
            int h = 17;
            h = 31 * h + rx;
            h = 31 * h + rz;
            h = 31 * h + size;
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegionKey other)) return false;
            return (
                this.rx == other.rx &&
                this.rz == other.rz &&
                this.size == other.size
            );
        }
    }

    private static final class RegionCacheEntry {

        long solidVertexBuffer, solidVertexMemory;
        long solidIndexBuffer, solidIndexMemory;
        int solidIndexCount;

        long cutoutVertexBuffer, cutoutVertexMemory;
        long cutoutIndexBuffer, cutoutIndexMemory;
        int cutoutIndexCount;

        long version;
        long lastUsedNanos;
    }

    private final java.util.Map<RegionKey, RegionCacheEntry> regionCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    private OffscreenWorldRenderer() {}

    /**
     * Initialize headless Vulkan renderer. Safe to call multiple times.
     *
     * @return true if initialized and available; false otherwise
     */
    public synchronized boolean initializeHeadless() {
        if (initialized.get()) {
            return available.get();
        }
        try (MemoryStack stack = stackPush()) {
            // 1) Instance
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType(
                VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
            );
            // Minimal application info is optional; leaving null is okay.
            PointerBuffer pInstance = stack.mallocPointer(1);
            System.out.println(
                "OffscreenWorldRenderer: creating Vulkan instance..."
            );
            int err = vkCreateInstance(ici, null, pInstance);
            if (err != VK_SUCCESS) {
                return markFailed("vkCreateInstance failed: " + toVk(err));
            }
            instance = new org.lwjgl.vulkan.VkInstance(pInstance.get(0), ici);

            // 2) Physical device and queue family
            IntBuffer pCount = stack.ints(0);
            vkEnumeratePhysicalDevices(instance, pCount, null);
            int pdevCount = pCount.get(0);
            if (pdevCount <= 0) {
                return markFailed("No Vulkan physical devices found");
            }
            PointerBuffer pPhys = stack.mallocPointer(pdevCount);
            vkEnumeratePhysicalDevices(instance, pCount.rewind(), pPhys);

            VkPhysicalDevice selected = null;
            int selectedQueueFamily = -1;

            for (int i = 0; i < pdevCount; i++) {
                VkPhysicalDevice pdev = new VkPhysicalDevice(
                    pPhys.get(i),
                    instance
                );
                IntBuffer qCount = stack.ints(0);
                vkGetPhysicalDeviceQueueFamilyProperties(pdev, qCount, null);
                int numFamilies = qCount.get(0);
                if (numFamilies <= 0) {
                    continue;
                }
                VkQueueFamilyProperties.Buffer qProps =
                    VkQueueFamilyProperties.calloc(numFamilies, stack);
                vkGetPhysicalDeviceQueueFamilyProperties(
                    pdev,
                    qCount.rewind(),
                    qProps
                );
                for (int q = 0; q < numFamilies; q++) {
                    int flags = qProps.get(q).queueFlags();
                    if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        selected = pdev;
                        selectedQueueFamily = q;
                        break;
                    }
                }
                if (selected != null) break;
            }

            if (selected == null) {
                return markFailed("No suitable graphics queue family found");
            }
            physicalDevice = selected;
            graphicsQueueFamilyIndex = selectedQueueFamily;

            // Memory properties
            memProps = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

            // Probe supported depth format (prefer D32_SFLOAT, fallback to D24_UNORM_S8_UINT, then D16_UNORM)
            int probedDepth = probeSupportedDepthFormat();
            if (probedDepth == 0) {
                return markFailed("No supported depth format found");
            }
            DEPTH_FORMAT = probedDepth;

            // 3) Device and queue
            float priority = 1.0f;
            VkDeviceQueueCreateInfo.Buffer queueInfo =
                VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo
                .get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamilyIndex)
                .pQueuePriorities(stack.floats(priority));

            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(
                stack
            );
            // Enable sampler anisotropy only if the device supports it
            VkPhysicalDeviceFeatures supported =
                VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, supported);
            features.samplerAnisotropy(supported.samplerAnisotropy());

            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfo)
                .pEnabledFeatures(features);

            PointerBuffer pDevice = stack.mallocPointer(1);
            err = vkCreateDevice(physicalDevice, dci, null, pDevice);
            if (err != VK_SUCCESS) {
                return markFailed("vkCreateDevice failed: " + toVk(err));
            }
            device = new org.lwjgl.vulkan.VkDevice(
                pDevice.get(0),
                physicalDevice,
                dci
            );

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamilyIndex, 0, pQueue);
            graphicsQueue = new org.lwjgl.vulkan.VkQueue(pQueue.get(0), device);

            // 4) Command pool
            VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamilyIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            LongBuffer pCmdPool = stack.mallocLong(1);
            err = vkCreateCommandPool(device, cpci, null, pCmdPool);
            if (err != VK_SUCCESS) {
                return markFailed("vkCreateCommandPool failed: " + toVk(err));
            }
            commandPool = pCmdPool.get(0);

            // 5) Render pass
            if (!createRenderPass()) {
                return markFailed("Failed to create render pass");
            }

            // 6) Offscreen images and framebuffer
            if (!createOffscreenTargets(DEFAULT_WIDTH, DEFAULT_HEIGHT)) {
                return markFailed("Failed to create offscreen targets");
            }

            // 7) Pipeline
            if (!createPipeline()) {
                return markFailed("Failed to create pipeline");
            }

            // 8) Readback image (linear)
            if (!createReadbackImage()) {
                return markFailed("Failed to create readback image");
            }

            initialized.set(true);
            available.set(true);
            return true;
        } catch (Throwable t) {
            markFailed("Initialization exception: " + t.getMessage());
            return false;
        }
    }

    /**
     * Render a simple triangle off-screen and return the image.
     * If unavailable, returns null so callers can fall back gracefully.
     */
    public synchronized BufferedImage render(
        double x,
        double y,
        double z,
        float pitch,
        float yaw,
        int width,
        int height,
        int renderDistance
    ) {
        if (!initialized.get() || !available.get()) {
            return null;
        }

        // Resolve a ServerLevel from the ServerRenderer's stored server reference
        ServerLevel world = null;
        try {
            Object srv = ServerRenderer.getInstance() != null
                ? ServerRenderer.getInstance().getServer()
                : null;
            if (srv instanceof MinecraftServer ms) {
                world = ms.overworld();
            }
        } catch (Throwable ignored) {}
        // ServerLevel unavailable (server not wired yet): we'll return a cleared frame
        if (world == null) {
            System.out.println(
                "OffscreenWorldRenderer: no ServerLevel (ServerRenderer.server == null) — returning cleared frame"
            );
        }
        // Ensure offscreen targets match requested output size (with sane clamps)
        int reqW = width < 160 ? 160 : Math.min(width, 4096);
        int reqH = height < 90 ? 90 : Math.min(height, 2160);
        if (this.width != reqW || this.height != reqH) {
            // Recreate offscreen targets to the requested size
            if (framebuffer != 0L) {
                vkDestroyFramebuffer(device, framebuffer, null);
                framebuffer = 0L;
            }
            if (colorImageView != 0L) {
                vkDestroyImageView(device, colorImageView, null);
                colorImageView = 0L;
            }
            if (colorImage != 0L) {
                vkDestroyImage(device, colorImage, null);
                colorImage = 0L;
            }
            if (colorImageMemory != 0L) {
                vkFreeMemory(device, colorImageMemory, null);
                colorImageMemory = 0L;
            }
            if (depthImageView != 0L) {
                vkDestroyImageView(device, depthImageView, null);
                depthImageView = 0L;
            }
            if (depthImage != 0L) {
                vkDestroyImage(device, depthImage, null);
                depthImage = 0L;
            }
            if (depthImageMemory != 0L) {
                vkFreeMemory(device, depthImageMemory, null);
                depthImageMemory = 0L;
            }
            boolean targetsOk = createOffscreenTargets(reqW, reqH);
            if (!targetsOk) {
                System.err.println(
                    "OffscreenWorldRenderer: createOffscreenTargets failed for " +
                    reqW +
                    "x" +
                    reqH
                );
            }
            // Recreate readback image (linear) to match new size
            if (readbackImage != 0L) {
                vkDestroyImage(device, readbackImage, null);
                readbackImage = 0L;
            }
            if (readbackImageMemory != 0L) {
                vkFreeMemory(device, readbackImageMemory, null);
                readbackImageMemory = 0L;
            }
            createReadbackImage();
            // Lazy-load texture atlas pixels and create GPU resources (image + sampler + descriptor)
            if (!this.atlasPrepared) {
                try {
                    net.vulkanmod.server.ServerTextureAtlas at =
                        net.vulkanmod.server.ServerTextureAtlas.getInstance();
                    java.nio.ByteBuffer px = at.getAtlasPixelsRGBA();
                    if (px != null && px.remaining() > 0) {
                        this.atlasPixels = px;
                        this.atlasW = at.getAtlasWidth();
                        this.atlasH = at.getAtlasHeight();
                        this.atlasPrepared = true;
                    }
                } catch (Throwable t) {
                    System.err.println(
                        "OffscreenWorldRenderer: atlas preload failed: " + t
                    );
                    this.atlasPrepared = false;
                }
            }
            if (
                this.atlasPrepared &&
                this.atlasImage == 0L &&
                this.atlasPixels != null
            ) {
                try (
                    org.lwjgl.system.MemoryStack st =
                        org.lwjgl.system.MemoryStack.stackPush()
                ) {
                    // Create atlas image (optimal, sampled, transfer dst)
                    int __maxDim = Math.max(atlasW, atlasH);
                    int __mipLevels =
                        1 +
                        Integer.numberOfTrailingZeros(
                            Integer.highestOneBit(__maxDim)
                        );
                    boolean okImg = false;
                    {
                        LongBuffer pImg = st.mallocLong(1);
                        VkImageCreateInfo ici = VkImageCreateInfo.calloc(st)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(COLOR_FORMAT)
                            .extent(VkExtent3D(st, atlasW, atlasH, 1))
                            .mipLevels(__mipLevels)
                            .arrayLayers(1)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK_IMAGE_TILING_OPTIMAL)
                            .usage(
                                VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                VK_IMAGE_USAGE_SAMPLED_BIT
                            )
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                        int errImg = vkCreateImage(device, ici, null, pImg);
                        if (errImg == VK_SUCCESS) {
                            long img = pImg.get(0);
                            VkMemoryRequirements req =
                                VkMemoryRequirements.calloc(st);
                            vkGetImageMemoryRequirements(device, img, req);
                            int memTypeIndex = findMemoryType(
                                req.memoryTypeBits(),
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                            );
                            if (memTypeIndex >= 0) {
                                VkMemoryAllocateInfo mai =
                                    VkMemoryAllocateInfo.calloc(st)
                                        .sType(
                                            VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
                                        )
                                        .allocationSize(req.size())
                                        .memoryTypeIndex(memTypeIndex);
                                LongBuffer pMem = st.mallocLong(1);
                                int errMem = vkAllocateMemory(
                                    device,
                                    mai,
                                    null,
                                    pMem
                                );
                                if (errMem == VK_SUCCESS) {
                                    long mem = pMem.get(0);
                                    vkBindImageMemory(device, img, mem, 0);
                                    atlasImage = img;
                                    atlasImageMemory = mem;
                                    okImg = true;
                                }
                            }
                        }
                    }
                    if (!okImg) {
                        System.err.println(
                            "OffscreenWorldRenderer: failed to create atlas image"
                        );
                    } else {
                        // Image view
                        LongBuffer pView = st.mallocLong(1);
                        VkImageViewCreateInfo ivci =
                            VkImageViewCreateInfo.calloc(st)
                                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                                .image(atlasImage)
                                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                                .format(COLOR_FORMAT);
                        VkImageSubresourceRange sub =
                            VkImageSubresourceRange.calloc(st)
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0)
                                .levelCount(__mipLevels)
                                .baseArrayLayer(0)
                                .layerCount(1);
                        ivci.subresourceRange(sub);
                        int errIv = vkCreateImageView(
                            device,
                            ivci,
                            null,
                            pView
                        );
                        if (errIv != VK_SUCCESS) {
                            System.err.println(
                                "OffscreenWorldRenderer: vkCreateImageView (atlas) failed: " +
                                toVk(errIv)
                            );
                        } else {
                            atlasImageView = pView.get(0);
                            // Sampler
                            LongBuffer pSampler = st.mallocLong(1);
                            VkSamplerCreateInfo sci =
                                VkSamplerCreateInfo.calloc(st)
                                    .sType(
                                        VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
                                    )
                                    .magFilter(VK_FILTER_LINEAR)
                                    .minFilter(VK_FILTER_LINEAR)
                                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                                    .addressModeU(
                                        VK_SAMPLER_ADDRESS_MODE_REPEAT
                                    )
                                    .addressModeV(
                                        VK_SAMPLER_ADDRESS_MODE_REPEAT
                                    )
                                    .addressModeW(
                                        VK_SAMPLER_ADDRESS_MODE_REPEAT
                                    )
                                    .maxLod((float) (__mipLevels - 1))
                                    .minLod(0.0f)
                                    .mipLodBias(0.0f)
                                    .unnormalizedCoordinates(false);
                            // Enable anisotropy if supported; clamp to device limit
                            {
                                org.lwjgl.vulkan.VkPhysicalDeviceFeatures avail =
                                    org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc(
                                        st
                                    );
                                vkGetPhysicalDeviceFeatures(
                                    physicalDevice,
                                    avail
                                );
                                org.lwjgl.vulkan.VkPhysicalDeviceProperties props =
                                    org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(
                                        st
                                    );
                                vkGetPhysicalDeviceProperties(
                                    physicalDevice,
                                    props
                                );
                                if (avail.samplerAnisotropy()) {
                                    sci
                                        .anisotropyEnable(true)
                                        .maxAnisotropy(
                                            props
                                                .limits()
                                                .maxSamplerAnisotropy()
                                        );
                                } else {
                                    sci
                                        .anisotropyEnable(false)
                                        .maxAnisotropy(1.0f);
                                }
                            }
                            int errSp = vkCreateSampler(
                                device,
                                sci,
                                null,
                                pSampler
                            );
                            if (errSp != VK_SUCCESS) {
                                System.err.println(
                                    "OffscreenWorldRenderer: vkCreateSampler failed: " +
                                    toVk(errSp)
                                );
                            } else {
                                atlasSampler = pSampler.get(0);
                                // Upload pixels via staging buffer
                                BufferAlloc staging = createBuffer(
                                    atlasPixels.remaining(),
                                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                                );
                                if (staging != null) {
                                    PointerBuffer pData = st.mallocPointer(1);
                                    vkMapMemory(
                                        device,
                                        staging.memory,
                                        0,
                                        VK_WHOLE_SIZE,
                                        0,
                                        pData
                                    );
                                    long ptr = pData.get(0);
                                    org.lwjgl.system.MemoryUtil.memCopy(
                                        org.lwjgl.system.MemoryUtil.memAddress(
                                            atlasPixels
                                        ),
                                        ptr,
                                        atlasPixels.remaining()
                                    );
                                    vkUnmapMemory(device, staging.memory);
                                    // Record copy
                                    org.lwjgl.vulkan.VkCommandBuffer cmd =
                                        beginOneTimeCommands();
                                    transitionImageLayout(
                                        st,
                                        cmd,
                                        atlasImage,
                                        COLOR_FORMAT,
                                        VK_IMAGE_LAYOUT_UNDEFINED,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                        VK_IMAGE_ASPECT_COLOR_BIT
                                    );
                                    VkBufferImageCopy.Buffer r =
                                        VkBufferImageCopy.calloc(1, st);
                                    r
                                        .get(0)
                                        .bufferOffset(0)
                                        .bufferRowLength(0)
                                        .bufferImageHeight(0)
                                        .imageSubresource(
                                            isrl(
                                                st,
                                                VK_IMAGE_ASPECT_COLOR_BIT,
                                                0,
                                                0
                                            )
                                        )
                                        .imageOffset()
                                        .set(0, 0, 0);
                                    r
                                        .get(0)
                                        .imageExtent()
                                        .set(atlasW, atlasH, 1);
                                    vkCmdCopyBufferToImage(
                                        cmd,
                                        staging.buffer,
                                        atlasImage,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                        r
                                    );
                                    // Generate mipmaps via blit chain
                                    int srcW = atlasW;
                                    int srcH = atlasH;
                                    for (
                                        int level = 1;
                                        level < __mipLevels;
                                        level++
                                    ) {
                                        // Previous level: TRANSFER_DST -> TRANSFER_SRC
                                        {
                                            VkImageMemoryBarrier.Buffer barrier =
                                                VkImageMemoryBarrier.calloc(
                                                    1,
                                                    st
                                                )
                                                    .sType(
                                                        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                    )
                                                    .oldLayout(
                                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                                    )
                                                    .newLayout(
                                                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                                                    )
                                                    .srcQueueFamilyIndex(
                                                        VK_QUEUE_FAMILY_IGNORED
                                                    )
                                                    .dstQueueFamilyIndex(
                                                        VK_QUEUE_FAMILY_IGNORED
                                                    )
                                                    .image(atlasImage);
                                            VkImageSubresourceRange range =
                                                VkImageSubresourceRange.calloc(
                                                    st
                                                )
                                                    .aspectMask(
                                                        VK_IMAGE_ASPECT_COLOR_BIT
                                                    )
                                                    .baseMipLevel(level - 1)
                                                    .levelCount(1)
                                                    .baseArrayLayer(0)
                                                    .layerCount(1);
                                            barrier.subresourceRange(range);
                                            barrier.srcAccessMask(
                                                VK_ACCESS_TRANSFER_WRITE_BIT
                                            );
                                            barrier.dstAccessMask(
                                                VK_ACCESS_TRANSFER_READ_BIT
                                            );
                                            vkCmdPipelineBarrier(
                                                cmd,
                                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                0,
                                                null,
                                                null,
                                                barrier
                                            );
                                        }
                                        // Next level: UNDEFINED -> TRANSFER_DST
                                        {
                                            VkImageMemoryBarrier.Buffer barrier =
                                                VkImageMemoryBarrier.calloc(
                                                    1,
                                                    st
                                                )
                                                    .sType(
                                                        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                    )
                                                    .oldLayout(
                                                        VK_IMAGE_LAYOUT_UNDEFINED
                                                    )
                                                    .newLayout(
                                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                                    )
                                                    .srcQueueFamilyIndex(
                                                        VK_QUEUE_FAMILY_IGNORED
                                                    )
                                                    .dstQueueFamilyIndex(
                                                        VK_QUEUE_FAMILY_IGNORED
                                                    )
                                                    .image(atlasImage);
                                            VkImageSubresourceRange range =
                                                VkImageSubresourceRange.calloc(
                                                    st
                                                )
                                                    .aspectMask(
                                                        VK_IMAGE_ASPECT_COLOR_BIT
                                                    )
                                                    .baseMipLevel(level)
                                                    .levelCount(1)
                                                    .baseArrayLayer(0)
                                                    .layerCount(1);
                                            barrier.subresourceRange(range);
                                            barrier.srcAccessMask(0);
                                            barrier.dstAccessMask(
                                                VK_ACCESS_TRANSFER_WRITE_BIT
                                            );
                                            vkCmdPipelineBarrier(
                                                cmd,
                                                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                0,
                                                null,
                                                null,
                                                barrier
                                            );
                                        }
                                        org.lwjgl.vulkan.VkImageBlit.Buffer blit =
                                            org.lwjgl.vulkan.VkImageBlit.calloc(
                                                1,
                                                st
                                            );
                                        blit
                                            .get(0)
                                            .srcSubresource()
                                            .aspectMask(
                                                VK_IMAGE_ASPECT_COLOR_BIT
                                            )
                                            .mipLevel(level - 1)
                                            .baseArrayLayer(0)
                                            .layerCount(1);
                                        blit.get(0).srcOffsets(0).set(0, 0, 0);
                                        blit
                                            .get(0)
                                            .srcOffsets(1)
                                            .set(srcW, srcH, 1);
                                        int dstW = Math.max(1, srcW / 2);
                                        int dstH = Math.max(1, srcH / 2);
                                        blit
                                            .get(0)
                                            .dstSubresource()
                                            .aspectMask(
                                                VK_IMAGE_ASPECT_COLOR_BIT
                                            )
                                            .mipLevel(level)
                                            .baseArrayLayer(0)
                                            .layerCount(1);
                                        blit.get(0).dstOffsets(0).set(0, 0, 0);
                                        blit
                                            .get(0)
                                            .dstOffsets(1)
                                            .set(dstW, dstH, 1);
                                        vkCmdBlitImage(
                                            cmd,
                                            atlasImage,
                                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                            atlasImage,
                                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                            blit,
                                            VK_FILTER_LINEAR
                                        );
                                        srcW = dstW;
                                        srcH = dstH;
                                    }
                                    // Transition all mips to shader read-only
                                    for (
                                        int level = 0;
                                        level < __mipLevels - 1;
                                        level++
                                    ) {
                                        VkImageMemoryBarrier.Buffer barrier =
                                            VkImageMemoryBarrier.calloc(1, st)
                                                .sType(
                                                    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                )
                                                .oldLayout(
                                                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                                                )
                                                .newLayout(
                                                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                                )
                                                .srcQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .dstQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .image(atlasImage);
                                        VkImageSubresourceRange range =
                                            VkImageSubresourceRange.calloc(st)
                                                .aspectMask(
                                                    VK_IMAGE_ASPECT_COLOR_BIT
                                                )
                                                .baseMipLevel(level)
                                                .levelCount(1)
                                                .baseArrayLayer(0)
                                                .layerCount(1);
                                        barrier.subresourceRange(range);
                                        barrier.srcAccessMask(
                                            VK_ACCESS_TRANSFER_READ_BIT
                                        );
                                        barrier.dstAccessMask(
                                            VK_ACCESS_SHADER_READ_BIT
                                        );
                                        vkCmdPipelineBarrier(
                                            cmd,
                                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                            0,
                                            null,
                                            null,
                                            barrier
                                        );
                                    }
                                    {
                                        VkImageMemoryBarrier.Buffer barrier =
                                            VkImageMemoryBarrier.calloc(1, st)
                                                .sType(
                                                    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                )
                                                .oldLayout(
                                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                                )
                                                .newLayout(
                                                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                                )
                                                .srcQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .dstQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .image(atlasImage);
                                        VkImageSubresourceRange range =
                                            VkImageSubresourceRange.calloc(st)
                                                .aspectMask(
                                                    VK_IMAGE_ASPECT_COLOR_BIT
                                                )
                                                .baseMipLevel(__mipLevels - 1)
                                                .levelCount(1)
                                                .baseArrayLayer(0)
                                                .layerCount(1);
                                        barrier.subresourceRange(range);
                                        barrier.srcAccessMask(
                                            VK_ACCESS_TRANSFER_WRITE_BIT
                                        );
                                        barrier.dstAccessMask(
                                            VK_ACCESS_SHADER_READ_BIT
                                        );
                                        vkCmdPipelineBarrier(
                                            cmd,
                                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                            0,
                                            null,
                                            null,
                                            barrier
                                        );
                                    }
                                    endOneTimeCommands(cmd);
                                    // Descriptor pool and set
                                    LongBuffer pPool = st.mallocLong(1);
                                    VkDescriptorPoolSize.Buffer poolSizes =
                                        VkDescriptorPoolSize.calloc(1, st);
                                    poolSizes
                                        .get(0)
                                        .type(
                                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                        )
                                        .descriptorCount(1);
                                    VkDescriptorPoolCreateInfo dpci =
                                        VkDescriptorPoolCreateInfo.calloc(st)
                                            .sType(
                                                VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
                                            )
                                            .pPoolSizes(poolSizes)
                                            .maxSets(1);
                                    int errPool = vkCreateDescriptorPool(
                                        device,
                                        dpci,
                                        null,
                                        pPool
                                    );
                                    if (errPool == VK_SUCCESS) {
                                        descriptorPool = pPool.get(0);
                                        LongBuffer pSet = st.mallocLong(1);
                                        VkDescriptorSetAllocateInfo dsai =
                                            VkDescriptorSetAllocateInfo.calloc(
                                                st
                                            )
                                                .sType(
                                                    VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
                                                )
                                                .descriptorPool(descriptorPool)
                                                .pSetLayouts(
                                                    st.longs(
                                                        descriptorSetLayout
                                                    )
                                                );
                                        int errSet = vkAllocateDescriptorSets(
                                            device,
                                            dsai,
                                            pSet
                                        );
                                        if (errSet == VK_SUCCESS) {
                                            descriptorSet = pSet.get(0);
                                            VkDescriptorImageInfo.Buffer dii =
                                                VkDescriptorImageInfo.calloc(
                                                    1,
                                                    st
                                                );
                                            dii
                                                .get(0)
                                                .sampler(atlasSampler)
                                                .imageView(atlasImageView)
                                                .imageLayout(
                                                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                                );
                                            VkWriteDescriptorSet.Buffer writes =
                                                VkWriteDescriptorSet.calloc(
                                                    1,
                                                    st
                                                );
                                            writes
                                                .get(0)
                                                .sType(
                                                    VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
                                                )
                                                .dstSet(descriptorSet)
                                                .dstBinding(0)
                                                .descriptorType(
                                                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                                )
                                                .pImageInfo(dii)
                                                .descriptorCount(1);
                                            vkUpdateDescriptorSets(
                                                device,
                                                writes,
                                                null
                                            );
                                        }
                                    }
                                    // allow GC of pixels
                                    this.atlasPixels = null;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println(
                        "OffscreenWorldRenderer: atlas resource build failed: " +
                        t
                    );
                }
            }
        }

        // Ensure atlas resources are prepared every frame (not only on resize)
        if (!this.atlasPrepared) {
            try {
                net.vulkanmod.server.ServerTextureAtlas at =
                    net.vulkanmod.server.ServerTextureAtlas.getInstance();
                java.nio.ByteBuffer px = at.getAtlasPixelsRGBA();
                if (px != null && px.remaining() > 0) {
                    this.atlasPixels = px;
                    this.atlasW = at.getAtlasWidth();
                    this.atlasH = at.getAtlasHeight();
                    this.atlasPrepared = true;
                }
            } catch (Throwable t) {
                System.err.println(
                    "OffscreenWorldRenderer: atlas preload failed: " + t
                );
                this.atlasPrepared = false;
            }
        }
        if (
            this.atlasPrepared &&
            this.atlasImage == 0L &&
            this.atlasPixels != null
        ) {
            try (
                org.lwjgl.system.MemoryStack st =
                    org.lwjgl.system.MemoryStack.stackPush()
            ) {
                int __maxDim = Math.max(atlasW, atlasH);
                int __mipLevels =
                    1 +
                    Integer.numberOfTrailingZeros(
                        Integer.highestOneBit(__maxDim)
                    );
                boolean okImg = false;
                {
                    LongBuffer pImg = st.mallocLong(1);
                    VkImageCreateInfo ici = VkImageCreateInfo.calloc(st)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(COLOR_FORMAT)
                        .extent(VkExtent3D(st, atlasW, atlasH, 1))
                        .mipLevels(__mipLevels)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(
                            VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                            VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                            VK_IMAGE_USAGE_SAMPLED_BIT
                        )
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                    int errImg = vkCreateImage(device, ici, null, pImg);
                    if (errImg == VK_SUCCESS) {
                        long img = pImg.get(0);
                        VkMemoryRequirements req = VkMemoryRequirements.calloc(
                            st
                        );
                        vkGetImageMemoryRequirements(device, img, req);
                        int memTypeIndex = findMemoryType(
                            req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                        );
                        if (memTypeIndex >= 0) {
                            VkMemoryAllocateInfo mai =
                                VkMemoryAllocateInfo.calloc(st)
                                    .sType(
                                        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
                                    )
                                    .allocationSize(req.size())
                                    .memoryTypeIndex(memTypeIndex);
                            LongBuffer pMem = st.mallocLong(1);
                            int errMem = vkAllocateMemory(
                                device,
                                mai,
                                null,
                                pMem
                            );
                            if (errMem == VK_SUCCESS) {
                                long mem = pMem.get(0);
                                vkBindImageMemory(device, img, mem, 0);
                                atlasImage = img;
                                atlasImageMemory = mem;
                                okImg = true;
                            }
                        }
                    }
                }
                if (okImg) {
                    LongBuffer pView = st.mallocLong(1);
                    VkImageViewCreateInfo ivci = VkImageViewCreateInfo.calloc(
                        st
                    )
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(atlasImage)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(COLOR_FORMAT);
                    VkImageSubresourceRange sub =
                        VkImageSubresourceRange.calloc(st)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(__mipLevels)
                            .baseArrayLayer(0)
                            .layerCount(1);
                    ivci.subresourceRange(sub);
                    int errIv = vkCreateImageView(device, ivci, null, pView);
                    if (errIv == VK_SUCCESS) {
                        atlasImageView = pView.get(0);
                        LongBuffer pSampler = st.mallocLong(1);
                        VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(st)
                            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                            .magFilter(VK_FILTER_LINEAR)
                            .minFilter(VK_FILTER_LINEAR)
                            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                            .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                            .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                            .maxLod((float) (__mipLevels - 1))
                            .minLod(0.0f)
                            .mipLodBias(0.0f)
                            .unnormalizedCoordinates(false);
                        // Enable anisotropy if supported; clamp to device limit
                        {
                            org.lwjgl.vulkan.VkPhysicalDeviceFeatures avail =
                                org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc(
                                    st
                                );
                            vkGetPhysicalDeviceFeatures(physicalDevice, avail);
                            org.lwjgl.vulkan.VkPhysicalDeviceProperties props =
                                org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(
                                    st
                                );
                            vkGetPhysicalDeviceProperties(
                                physicalDevice,
                                props
                            );
                            if (avail.samplerAnisotropy()) {
                                sci
                                    .anisotropyEnable(true)
                                    .maxAnisotropy(
                                        props.limits().maxSamplerAnisotropy()
                                    );
                            } else {
                                sci.anisotropyEnable(false).maxAnisotropy(1.0f);
                            }
                        }
                        int errSp = vkCreateSampler(
                            device,
                            sci,
                            null,
                            pSampler
                        );
                        if (errSp == VK_SUCCESS) {
                            atlasSampler = pSampler.get(0);
                            BufferAlloc staging = createBuffer(
                                atlasPixels.remaining(),
                                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                            );
                            if (staging != null) {
                                PointerBuffer pData = st.mallocPointer(1);
                                vkMapMemory(
                                    device,
                                    staging.memory,
                                    0,
                                    VK_WHOLE_SIZE,
                                    0,
                                    pData
                                );
                                long ptr = pData.get(0);
                                org.lwjgl.system.MemoryUtil.memCopy(
                                    org.lwjgl.system.MemoryUtil.memAddress(
                                        atlasPixels
                                    ),
                                    ptr,
                                    atlasPixels.remaining()
                                );
                                vkUnmapMemory(device, staging.memory);
                                org.lwjgl.vulkan.VkCommandBuffer cmd =
                                    beginOneTimeCommands();
                                transitionImageLayout(
                                    st,
                                    cmd,
                                    atlasImage,
                                    COLOR_FORMAT,
                                    VK_IMAGE_LAYOUT_UNDEFINED,
                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                    VK_IMAGE_ASPECT_COLOR_BIT
                                );
                                VkBufferImageCopy.Buffer r =
                                    VkBufferImageCopy.calloc(1, st);
                                r
                                    .get(0)
                                    .bufferOffset(0)
                                    .bufferRowLength(0)
                                    .bufferImageHeight(0)
                                    .imageSubresource(
                                        isrl(
                                            st,
                                            VK_IMAGE_ASPECT_COLOR_BIT,
                                            0,
                                            0
                                        )
                                    )
                                    .imageOffset()
                                    .set(0, 0, 0);
                                r.get(0).imageExtent().set(atlasW, atlasH, 1);
                                vkCmdCopyBufferToImage(
                                    cmd,
                                    staging.buffer,
                                    atlasImage,
                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                    r
                                );
                                // Generate mipmaps via blit chain
                                int srcW = atlasW;
                                int srcH = atlasH;
                                for (
                                    int level = 1;
                                    level < __mipLevels;
                                    level++
                                ) {
                                    // Previous level: TRANSFER_DST -> TRANSFER_SRC
                                    {
                                        VkImageMemoryBarrier.Buffer barrier =
                                            VkImageMemoryBarrier.calloc(1, st)
                                                .sType(
                                                    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                )
                                                .oldLayout(
                                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                                )
                                                .newLayout(
                                                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                                                )
                                                .srcQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .dstQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .image(atlasImage);
                                        VkImageSubresourceRange range =
                                            VkImageSubresourceRange.calloc(st)
                                                .aspectMask(
                                                    VK_IMAGE_ASPECT_COLOR_BIT
                                                )
                                                .baseMipLevel(level - 1)
                                                .levelCount(1)
                                                .baseArrayLayer(0)
                                                .layerCount(1);
                                        barrier.subresourceRange(range);
                                        barrier.srcAccessMask(
                                            VK_ACCESS_TRANSFER_WRITE_BIT
                                        );
                                        barrier.dstAccessMask(
                                            VK_ACCESS_TRANSFER_READ_BIT
                                        );
                                        vkCmdPipelineBarrier(
                                            cmd,
                                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            0,
                                            null,
                                            null,
                                            barrier
                                        );
                                    }
                                    // Next level: UNDEFINED -> TRANSFER_DST
                                    {
                                        VkImageMemoryBarrier.Buffer barrier =
                                            VkImageMemoryBarrier.calloc(1, st)
                                                .sType(
                                                    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                                )
                                                .oldLayout(
                                                    VK_IMAGE_LAYOUT_UNDEFINED
                                                )
                                                .newLayout(
                                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                                )
                                                .srcQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .dstQueueFamilyIndex(
                                                    VK_QUEUE_FAMILY_IGNORED
                                                )
                                                .image(atlasImage);
                                        VkImageSubresourceRange range =
                                            VkImageSubresourceRange.calloc(st)
                                                .aspectMask(
                                                    VK_IMAGE_ASPECT_COLOR_BIT
                                                )
                                                .baseMipLevel(level)
                                                .levelCount(1)
                                                .baseArrayLayer(0)
                                                .layerCount(1);
                                        barrier.subresourceRange(range);
                                        barrier.srcAccessMask(0);
                                        barrier.dstAccessMask(
                                            VK_ACCESS_TRANSFER_WRITE_BIT
                                        );
                                        vkCmdPipelineBarrier(
                                            cmd,
                                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            0,
                                            null,
                                            null,
                                            barrier
                                        );
                                    }
                                    org.lwjgl.vulkan.VkImageBlit.Buffer blit =
                                        org.lwjgl.vulkan.VkImageBlit.calloc(
                                            1,
                                            st
                                        );
                                    blit
                                        .get(0)
                                        .srcSubresource()
                                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                        .mipLevel(level - 1)
                                        .baseArrayLayer(0)
                                        .layerCount(1);
                                    blit.get(0).srcOffsets(0).set(0, 0, 0);
                                    blit
                                        .get(0)
                                        .srcOffsets(1)
                                        .set(srcW, srcH, 1);
                                    int dstW = Math.max(1, srcW / 2);
                                    int dstH = Math.max(1, srcH / 2);
                                    blit
                                        .get(0)
                                        .dstSubresource()
                                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                        .mipLevel(level)
                                        .baseArrayLayer(0)
                                        .layerCount(1);
                                    blit.get(0).dstOffsets(0).set(0, 0, 0);
                                    blit
                                        .get(0)
                                        .dstOffsets(1)
                                        .set(dstW, dstH, 1);
                                    vkCmdBlitImage(
                                        cmd,
                                        atlasImage,
                                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                        atlasImage,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                        blit,
                                        VK_FILTER_LINEAR
                                    );
                                    srcW = dstW;
                                    srcH = dstH;
                                }
                                // Transition all mips to shader read-only
                                for (
                                    int level = 0;
                                    level < __mipLevels - 1;
                                    level++
                                ) {
                                    VkImageMemoryBarrier.Buffer barrier =
                                        VkImageMemoryBarrier.calloc(1, st)
                                            .sType(
                                                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                            )
                                            .oldLayout(
                                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                                            )
                                            .newLayout(
                                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                            )
                                            .srcQueueFamilyIndex(
                                                VK_QUEUE_FAMILY_IGNORED
                                            )
                                            .dstQueueFamilyIndex(
                                                VK_QUEUE_FAMILY_IGNORED
                                            )
                                            .image(atlasImage);
                                    VkImageSubresourceRange range =
                                        VkImageSubresourceRange.calloc(st)
                                            .aspectMask(
                                                VK_IMAGE_ASPECT_COLOR_BIT
                                            )
                                            .baseMipLevel(level)
                                            .levelCount(1)
                                            .baseArrayLayer(0)
                                            .layerCount(1);
                                    barrier.subresourceRange(range);
                                    barrier.srcAccessMask(
                                        VK_ACCESS_TRANSFER_READ_BIT
                                    );
                                    barrier.dstAccessMask(
                                        VK_ACCESS_SHADER_READ_BIT
                                    );
                                    vkCmdPipelineBarrier(
                                        cmd,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                        0,
                                        null,
                                        null,
                                        barrier
                                    );
                                }
                                {
                                    VkImageMemoryBarrier.Buffer barrier =
                                        VkImageMemoryBarrier.calloc(1, st)
                                            .sType(
                                                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                                            )
                                            .oldLayout(
                                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                            )
                                            .newLayout(
                                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                            )
                                            .srcQueueFamilyIndex(
                                                VK_QUEUE_FAMILY_IGNORED
                                            )
                                            .dstQueueFamilyIndex(
                                                VK_QUEUE_FAMILY_IGNORED
                                            )
                                            .image(atlasImage);
                                    VkImageSubresourceRange range =
                                        VkImageSubresourceRange.calloc(st)
                                            .aspectMask(
                                                VK_IMAGE_ASPECT_COLOR_BIT
                                            )
                                            .baseMipLevel(__mipLevels - 1)
                                            .levelCount(1)
                                            .baseArrayLayer(0)
                                            .layerCount(1);
                                    barrier.subresourceRange(range);
                                    barrier.srcAccessMask(
                                        VK_ACCESS_TRANSFER_WRITE_BIT
                                    );
                                    barrier.dstAccessMask(
                                        VK_ACCESS_SHADER_READ_BIT
                                    );
                                    vkCmdPipelineBarrier(
                                        cmd,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                        0,
                                        null,
                                        null,
                                        barrier
                                    );
                                }
                                endOneTimeCommands(cmd);
                                if (descriptorPool == 0L) {
                                    LongBuffer pPool = st.mallocLong(1);
                                    VkDescriptorPoolSize.Buffer poolSizes =
                                        VkDescriptorPoolSize.calloc(1, st);
                                    poolSizes
                                        .get(0)
                                        .type(
                                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                        )
                                        .descriptorCount(1);
                                    VkDescriptorPoolCreateInfo dpci =
                                        VkDescriptorPoolCreateInfo.calloc(st)
                                            .sType(
                                                VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
                                            )
                                            .pPoolSizes(poolSizes)
                                            .maxSets(1);
                                    int errPool = vkCreateDescriptorPool(
                                        device,
                                        dpci,
                                        null,
                                        pPool
                                    );
                                    if (errPool == VK_SUCCESS) {
                                        descriptorPool = pPool.get(0);
                                    }
                                }
                                if (
                                    descriptorSet == 0L &&
                                    descriptorPool != 0L &&
                                    descriptorSetLayout != 0L
                                ) {
                                    LongBuffer pSet = st.mallocLong(1);
                                    VkDescriptorSetAllocateInfo dsai =
                                        VkDescriptorSetAllocateInfo.calloc(st)
                                            .sType(
                                                VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
                                            )
                                            .descriptorPool(descriptorPool)
                                            .pSetLayouts(
                                                st.longs(descriptorSetLayout)
                                            );
                                    int errSet = vkAllocateDescriptorSets(
                                        device,
                                        dsai,
                                        pSet
                                    );
                                    if (errSet == VK_SUCCESS) {
                                        descriptorSet = pSet.get(0);
                                        VkDescriptorImageInfo.Buffer dii =
                                            VkDescriptorImageInfo.calloc(1, st);
                                        dii
                                            .get(0)
                                            .sampler(atlasSampler)
                                            .imageView(atlasImageView)
                                            .imageLayout(
                                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                            );
                                        VkWriteDescriptorSet.Buffer writes =
                                            VkWriteDescriptorSet.calloc(1, st);
                                        writes
                                            .get(0)
                                            .sType(
                                                VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
                                            )
                                            .dstSet(descriptorSet)
                                            .dstBinding(0)
                                            .descriptorType(
                                                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                            )
                                            .pImageInfo(dii)
                                            .descriptorCount(1);
                                        vkUpdateDescriptorSets(
                                            device,
                                            writes,
                                            null
                                        );
                                    }
                                }
                                this.atlasPixels = null;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println(
                    "OffscreenWorldRenderer: atlas resource build failed (late): " +
                    t
                );
            }
        }

        // Build layered meshes: SOLID + CUTOUT
        RegionMesh solidMesh = null;
        RegionMesh cutoutMesh = null;
        int regionSizeChunks = Math.max(2, Math.min(renderDistance, 16));
        try {
            if (world != null) {
                int cs = 16;
                int sideBlocks = regionSizeChunks * cs;
                int regionChunkX =
                    (int) Math.floor(x / cs) - (regionSizeChunks / 2);
                int regionChunkZ =
                    (int) Math.floor(z / cs) - (regionSizeChunks / 2);
                int minX = regionChunkX * cs;
                int minZ = regionChunkZ * cs;
                int minY = world.getMinBuildHeight();
                int maxY = world.getMaxBuildHeight();

                // Build snapshot and meshes on the server thread; do not stall main thread with world calls off-thread
                java.util.concurrent.CompletableFuture<
                    MeshBuilder.LayeredRegionMesh
                > buildFuture = new java.util.concurrent.CompletableFuture<>();
                final net.minecraft.server.level.ServerLevel worldFinal = world;
                net.minecraft.server.MinecraftServer msLocal =
                    worldFinal.getServer();
                msLocal.execute(() -> {
                    try {
                        // Ensure chunks are loaded
                        for (
                            int cz = regionChunkZ;
                            cz < regionChunkZ + regionSizeChunks;
                            cz++
                        ) {
                            for (
                                int cx = regionChunkX;
                                cx < regionChunkX + regionSizeChunks;
                                cx++
                            ) {
                                try {
                                    worldFinal.getChunk(cx, cz);
                                } catch (Throwable ignore) {}
                            }
                        }
                        WorldSnapshotAccessor snap =
                            WorldSnapshotAccessor.capture(
                                worldFinal,
                                minX,
                                minY,
                                minZ,
                                minX + sideBlocks,
                                maxY,
                                minZ + sideBlocks
                            );

                        MeshBuilder.Config cfg = new MeshBuilder.Config();
                        cfg.regionSizeChunks = regionSizeChunks;
                        cfg.minY = minY;
                        cfg.maxY = maxY;
                        MeshBuilder builder = new MeshBuilder(cfg);
                        long version = 1L;
                        buildFuture.complete(
                            builder.buildRegionLayered(
                                snap,
                                regionChunkX,
                                regionChunkZ,
                                version
                            )
                        );
                    } catch (Throwable t) {
                        buildFuture.completeExceptionally(t);
                    }
                });
                try {
                    MeshBuilder.LayeredRegionMesh layered = buildFuture.get();
                    if (layered != null) {
                        solidMesh = layered.solid;
                        cutoutMesh = layered.cutout;
                    }
                } catch (Throwable t) {
                    System.err.println(
                        "OffscreenWorldRenderer: server-thread layered snapshot/mesh failed: " +
                        t
                    );
                    solidMesh = null;
                    cutoutMesh = null;
                }
            }
        } catch (Throwable t) {
            System.err.println(
                "OffscreenWorldRenderer: buildRegionLayered failed: " + t
            );
            solidMesh = null;
            cutoutMesh = null;
        }

        // Upload/update GPU buffers per-layer
        RegionCacheEntry cacheEntry = null;
        try {
            // Key by solid mesh identity (region coords/size are identical for both)
            RegionMesh refMesh = solidMesh != null ? solidMesh : cutoutMesh;
            if (refMesh != null && !refMesh.isEmpty()) {
                RegionKey key = new RegionKey(
                    refMesh.getRegionChunkX(),
                    refMesh.getRegionChunkZ(),
                    refMesh.getRegionSizeChunks()
                );
                RegionCacheEntry existing = regionCache.get(key);

                boolean needsUpload =
                    existing == null ||
                    existing.version != refMesh.getVersion();

                if (needsUpload) {
                    // Free previous
                    if (existing != null) {
                        if (existing.solidVertexBuffer != 0L) vkDestroyBuffer(
                            device,
                            existing.solidVertexBuffer,
                            null
                        );
                        if (existing.solidVertexMemory != 0L) vkFreeMemory(
                            device,
                            existing.solidVertexMemory,
                            null
                        );
                        if (existing.solidIndexBuffer != 0L) vkDestroyBuffer(
                            device,
                            existing.solidIndexBuffer,
                            null
                        );
                        if (existing.solidIndexMemory != 0L) vkFreeMemory(
                            device,
                            existing.solidIndexMemory,
                            null
                        );

                        if (existing.cutoutVertexBuffer != 0L) vkDestroyBuffer(
                            device,
                            existing.cutoutVertexBuffer,
                            null
                        );
                        if (existing.cutoutVertexMemory != 0L) vkFreeMemory(
                            device,
                            existing.cutoutVertexMemory,
                            null
                        );
                        if (existing.cutoutIndexBuffer != 0L) vkDestroyBuffer(
                            device,
                            existing.cutoutIndexBuffer,
                            null
                        );
                        if (existing.cutoutIndexMemory != 0L) vkFreeMemory(
                            device,
                            existing.cutoutIndexMemory,
                            null
                        );
                    }

                    RegionCacheEntry e = new RegionCacheEntry();

                    // Upload SOLID
                    if (solidMesh != null && !solidMesh.isEmpty()) {
                        int vtxBytesS = solidMesh.getVertexBufferSizeBytes();
                        int idxBytesS = solidMesh.getIndexBufferSizeBytes();
                        BufferAlloc vboS = createBuffer(
                            vtxBytesS,
                            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                        );
                        BufferAlloc iboS = createBuffer(
                            idxBytesS,
                            VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                        );
                        if (vboS != null && iboS != null) {
                            FloatBuffer vfb =
                                solidMesh.getInterleavedVertices();
                            IntBuffer ifb = solidMesh.getIndices();
                            ByteBuffer vSrc = MemoryUtil.memByteBuffer(
                                MemoryUtil.memAddress(vfb),
                                vtxBytesS
                            );
                            ByteBuffer iSrc = MemoryUtil.memByteBuffer(
                                MemoryUtil.memAddress(ifb),
                                idxBytesS
                            );
                            if (!uploadBuffer(vboS.buffer, vSrc)) return null;
                            if (!uploadBuffer(iboS.buffer, iSrc)) return null;

                            e.solidVertexBuffer = vboS.buffer;
                            e.solidVertexMemory = vboS.memory;
                            e.solidIndexBuffer = iboS.buffer;
                            e.solidIndexMemory = iboS.memory;
                            e.solidIndexCount = solidMesh.getIndexCount();
                        }
                    } else {
                        e.solidVertexBuffer = 0L;
                        e.solidIndexBuffer = 0L;
                        e.solidIndexCount = 0;
                    }

                    // Upload CUTOUT
                    if (cutoutMesh != null && !cutoutMesh.isEmpty()) {
                        int vtxBytesC = cutoutMesh.getVertexBufferSizeBytes();
                        int idxBytesC = cutoutMesh.getIndexBufferSizeBytes();
                        BufferAlloc vboC = createBuffer(
                            vtxBytesC,
                            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                        );
                        BufferAlloc iboC = createBuffer(
                            idxBytesC,
                            VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                        );
                        if (vboC != null && iboC != null) {
                            FloatBuffer vfb =
                                cutoutMesh.getInterleavedVertices();
                            IntBuffer ifb = cutoutMesh.getIndices();
                            ByteBuffer vSrc = MemoryUtil.memByteBuffer(
                                MemoryUtil.memAddress(vfb),
                                vtxBytesC
                            );
                            ByteBuffer iSrc = MemoryUtil.memByteBuffer(
                                MemoryUtil.memAddress(ifb),
                                idxBytesC
                            );
                            if (!uploadBuffer(vboC.buffer, vSrc)) return null;
                            if (!uploadBuffer(iboC.buffer, iSrc)) return null;

                            e.cutoutVertexBuffer = vboC.buffer;
                            e.cutoutVertexMemory = vboC.memory;
                            e.cutoutIndexBuffer = iboC.buffer;
                            e.cutoutIndexMemory = iboC.memory;
                            e.cutoutIndexCount = cutoutMesh.getIndexCount();
                        }
                    } else {
                        e.cutoutVertexBuffer = 0L;
                        e.cutoutIndexBuffer = 0L;
                        e.cutoutIndexCount = 0;
                    }

                    e.version = refMesh.getVersion();
                    e.lastUsedNanos = System.nanoTime();
                    regionCache.put(key, e);
                    cacheEntry = e;
                } else {
                    existing.lastUsedNanos = System.nanoTime();
                    cacheEntry = existing;
                }
            }
        } catch (Throwable t) {
            System.err.println(
                "OffscreenWorldRenderer: region GPU upload/cache (layered) failed: " +
                t
            );
            cacheEntry = null;
        }

        // If we have no uploaded region buffers in either layer, we will render a cleared frame (no draw)
        if (
            cacheEntry == null ||
            (cacheEntry.solidIndexCount <= 0 &&
                cacheEntry.cutoutIndexCount <= 0)
        ) {
            System.out.println(
                "OffscreenWorldRenderer: no region mesh/buffers available — returning cleared frame (no draw)"
            );
        }
        try (MemoryStack stack = stackPush()) {
            // Allocate a command buffer
            VkCommandBufferAllocateInfo cbAlloc =
                VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCB = stack.mallocPointer(1);
            int err = vkAllocateCommandBuffers(device, cbAlloc, pCB);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkAllocateCommandBuffers failed: " +
                    toVk(err)
                );
                return null;
            }
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer =
                new org.lwjgl.vulkan.VkCommandBuffer(pCB.get(0), device);

            // Begin command buffer
            VkCommandBufferBeginInfo beginInfo =
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkBeginCommandBuffer failed: " +
                    toVk(err)
                );
                return null;
            }

            // Transition color and depth images for rendering
            transitionImageLayout(
                stack,
                commandBuffer,
                colorImage,
                COLOR_FORMAT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_ASPECT_COLOR_BIT
            );
            transitionImageLayout(
                stack,
                commandBuffer,
                depthImage,
                DEPTH_FORMAT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_IMAGE_ASPECT_DEPTH_BIT
            );

            // Begin render pass
            VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
            clearValues
                .get(0)
                .color()
                .float32(0, 0.2f)
                .float32(1, 0.3f)
                .float32(2, 0.6f)
                .float32(3, 1.0f);
            clearValues.get(1).depthStencil().depth(1.0f).stencil(0);

            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(framebuffer);
            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);
            rpBegin.renderArea(renderArea);
            rpBegin.pClearValues(clearValues);

            vkCmdBeginRenderPass(
                commandBuffer,
                rpBegin,
                VK_SUBPASS_CONTENTS_INLINE
            );

            // Set viewport and scissor
            VkViewport.Buffer viewports = VkViewport.calloc(1, stack);
            viewports
                .get(0)
                .x(0)
                .y(0)
                .width(width)
                .height(height)
                .minDepth(0.0f)
                .maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewports);

            VkRect2D.Buffer scissors = VkRect2D.calloc(1, stack);
            scissors.get(0).offset().set(0, 0);
            scissors.get(0).extent().set(width, height);
            vkCmdSetScissor(commandBuffer, 0, scissors);

            // If we have a mesh/cache entry and atlas descriptor ready, bind buffers and draw indexed
            if (cacheEntry != null && descriptorSet != 0L) {
                // Push MVP (shared)
                float[] mvp = computeMVP(
                    x,
                    y,
                    z,
                    pitch,
                    yaw,
                    width,
                    height,
                    70.0f,
                    0.1f,
                    512.0f
                );
                ByteBuffer pc = stack.malloc(64);
                if (TRANSPOSE_MVP_FOR_SHADER) {
                    float[] mt = new float[16];
                    for (int r = 0; r < 4; r++) {
                        for (int c = 0; c < 4; c++) {
                            mt[c * 4 + r] = mvp[r * 4 + c];
                        }
                    }
                    for (int i = 0; i < 16; i++) pc.putFloat(mt[i]);
                } else {
                    for (int i = 0; i < 16; i++) pc.putFloat(mvp[i]);
                }
                pc.flip();

                // Bind atlas descriptor set
                vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSet),
                    null
                );

                // Draw SOLID
                if (cacheEntry.solidIndexCount > 0 && pipeline != 0L) {
                    vkCmdBindPipeline(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline
                    );
                    vkCmdPushConstants(
                        commandBuffer,
                        pipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT,
                        0,
                        pc
                    );
                    LongBuffer pVBs = stack.mallocLong(1);
                    pVBs.put(0, cacheEntry.solidVertexBuffer);
                    LongBuffer pOffs = stack.mallocLong(1);
                    pOffs.put(0, 0L);
                    vkCmdBindVertexBuffers(commandBuffer, 0, pVBs, pOffs);
                    vkCmdBindIndexBuffer(
                        commandBuffer,
                        cacheEntry.solidIndexBuffer,
                        0L,
                        VK_INDEX_TYPE_UINT32
                    );
                    System.out.println(
                        "OffscreenWorldRenderer: drawIndexed SOLID count=" +
                        cacheEntry.solidIndexCount
                    );
                    vkCmdDrawIndexed(
                        commandBuffer,
                        cacheEntry.solidIndexCount,
                        1,
                        0,
                        0,
                        0
                    );
                }

                // Draw CUTOUT
                if (cacheEntry.cutoutIndexCount > 0 && pipelineCutout != 0L) {
                    vkCmdBindPipeline(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineCutout
                    );
                    vkCmdPushConstants(
                        commandBuffer,
                        pipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT,
                        0,
                        pc
                    );
                    LongBuffer pVBc = stack.mallocLong(1);
                    pVBc.put(0, cacheEntry.cutoutVertexBuffer);
                    LongBuffer pOffc = stack.mallocLong(1);
                    pOffc.put(0, 0L);
                    vkCmdBindVertexBuffers(commandBuffer, 0, pVBc, pOffc);
                    vkCmdBindIndexBuffer(
                        commandBuffer,
                        cacheEntry.cutoutIndexBuffer,
                        0L,
                        VK_INDEX_TYPE_UINT32
                    );
                    System.out.println(
                        "OffscreenWorldRenderer: drawIndexed CUTOUT count=" +
                        cacheEntry.cutoutIndexCount
                    );
                    vkCmdDrawIndexed(
                        commandBuffer,
                        cacheEntry.cutoutIndexCount,
                        1,
                        0,
                        0,
                        0
                    );
                }
            }

            // End render pass
            vkCmdEndRenderPass(commandBuffer);

            // Transition for copy
            transitionImageLayout(
                stack,
                commandBuffer,
                colorImage,
                COLOR_FORMAT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_ASPECT_COLOR_BIT
            );
            transitionImageLayout(
                stack,
                commandBuffer,
                readbackImage,
                COLOR_FORMAT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_ASPECT_COLOR_BIT
            );

            // Copy image to linear readback image
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(
                1,
                stack
            );
            region
                .get(0)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource(isrl(stack, VK_IMAGE_ASPECT_COLOR_BIT, 0, 0))
                .imageOffset()
                .set(0, 0, 0);
            region.get(0).imageExtent().set(width, height, 1);

            vkCmdCopyImage(
                commandBuffer,
                colorImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readbackImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VkImageCopy1(region, stack)
            );

            transitionImageLayout(
                stack,
                commandBuffer,
                readbackImage,
                COLOR_FORMAT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_ASPECT_COLOR_BIT
            );

            // End and submit
            err = vkEndCommandBuffer(commandBuffer);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkEndCommandBuffer failed: " +
                    toVk(err)
                );
                return null;
            }

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));

            LongBuffer pFence = stack.mallocLong(1);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType(
                VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
            );
            err = vkCreateFence(device, fci, null, pFence);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateFence failed: " + toVk(err)
                );
                return null;
            }
            long fence = pFence.get(0);

            err = vkQueueSubmit(graphicsQueue, submitInfo, fence);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkQueueSubmit failed: " + toVk(err)
                );
                vkDestroyFence(device, fence, null);
                return null;
            }
            // Wait for completion
            vkWaitForFences(device, pFence.rewind(), true, 10_000_000_000L); // 10s timeout

            // Read back pixels
            BufferedImage img = readBackToImage();
            if (img == null) {
                System.err.println(
                    "OffscreenWorldRenderer: readBackToImage returned null"
                );
            }
            // Cleanup per-frame
            vkDestroyFence(device, fence, null);

            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Destroy resources.
     */
    public synchronized void shutdown() {
        if (!initialized.get()) {
            return;
        }
        try {
            if (device != null && device.address() != 0L) {
                vkDeviceWaitIdle(device);
            }

            destroyPipeline();
            // Cleanup region cache GPU buffers
            for (RegionCacheEntry e : regionCache.values()) {
                try {
                    if (e.solidVertexBuffer != 0L) vkDestroyBuffer(
                        device,
                        e.solidVertexBuffer,
                        null
                    );
                    if (e.solidVertexMemory != 0L) vkFreeMemory(
                        device,
                        e.solidVertexMemory,
                        null
                    );
                    if (e.solidIndexBuffer != 0L) vkDestroyBuffer(
                        device,
                        e.solidIndexBuffer,
                        null
                    );
                    if (e.solidIndexMemory != 0L) vkFreeMemory(
                        device,
                        e.solidIndexMemory,
                        null
                    );

                    if (e.cutoutVertexBuffer != 0L) vkDestroyBuffer(
                        device,
                        e.cutoutVertexBuffer,
                        null
                    );
                    if (e.cutoutVertexMemory != 0L) vkFreeMemory(
                        device,
                        e.cutoutVertexMemory,
                        null
                    );
                    if (e.cutoutIndexBuffer != 0L) vkDestroyBuffer(
                        device,
                        e.cutoutIndexBuffer,
                        null
                    );
                    if (e.cutoutIndexMemory != 0L) vkFreeMemory(
                        device,
                        e.cutoutIndexMemory,
                        null
                    );
                } catch (Throwable ignored) {}
            }
            regionCache.clear();

            if (framebuffer != 0L) {
                vkDestroyFramebuffer(device, framebuffer, null);
                framebuffer = 0L;
            }
            if (renderPass != 0L) {
                vkDestroyRenderPass(device, renderPass, null);
                renderPass = 0L;
            }

            // Offscreen images
            if (colorImageView != 0L) {
                vkDestroyImageView(device, colorImageView, null);
                colorImageView = 0L;
            }
            if (colorImage != 0L) {
                vkDestroyImage(device, colorImage, null);
                colorImage = 0L;
            }
            if (colorImageMemory != 0L) {
                vkFreeMemory(device, colorImageMemory, null);
                colorImageMemory = 0L;
            }

            if (depthImageView != 0L) {
                vkDestroyImageView(device, depthImageView, null);
                depthImageView = 0L;
            }
            if (depthImage != 0L) {
                vkDestroyImage(device, depthImage, null);
                depthImage = 0L;
            }
            if (depthImageMemory != 0L) {
                vkFreeMemory(device, depthImageMemory, null);
                depthImageMemory = 0L;
            }

            if (readbackImage != 0L) {
                vkDestroyImage(device, readbackImage, null);
                readbackImage = 0L;
            }
            if (readbackImageMemory != 0L) {
                vkFreeMemory(device, readbackImageMemory, null);
                readbackImageMemory = 0L;
            }

            if (commandPool != 0L) {
                vkDestroyCommandPool(device, commandPool, null);
                commandPool = 0L;
            }

            if (device != null && device.address() != 0L) {
                vkDestroyDevice(device, null);
                device = null;
            }

            if (instance != null && instance.address() != 0L) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        } catch (Throwable ignored) {} finally {
            available.set(false);
            initialized.set(false);
            memProps = null;
        }
    }

    public boolean isAvailable() {
        return initialized.get() && available.get();
    }

    // Internal helpers

    private boolean createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            // Color attachment
            VkAttachmentDescription.Buffer attachments =
                VkAttachmentDescription.calloc(2, stack);
            attachments
                .get(0)
                .format(COLOR_FORMAT)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            // Depth attachment
            attachments
                .get(1)
                .format(DEPTH_FORMAT)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorRef =
                VkAttachmentReference.calloc(1, stack);
            colorRef
                .get(0)
                .attachment(0)
                .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthRef = VkAttachmentReference.calloc(
                stack
            );
            depthRef
                .attachment(1)
                .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(
                1,
                stack
            );
            subpass
                .get(0)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(depthRef);

            VkRenderPassCreateInfo rpci = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachments)
                .pSubpasses(subpass);

            LongBuffer pRP = stack.mallocLong(1);
            int err = vkCreateRenderPass(device, rpci, null, pRP);
            if (err != VK_SUCCESS) {
                return false;
            }
            renderPass = pRP.get(0);
            return true;
        }
    }

    private boolean createOffscreenTargets(int w, int h) {
        this.width = w;
        this.height = h;
        try (MemoryStack stack = stackPush()) {
            // Color image (optimal, device-local, color attachment + transfer src)
            if (
                !createImage(
                    w,
                    h,
                    COLOR_FORMAT,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    (img, mem) -> {
                        colorImage = img;
                        colorImageMemory = mem;
                    }
                )
            ) {
                return false;
            }
            // Color view
            LongBuffer pView = stack.mallocLong(1);
            VkImageViewCreateInfo ivci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(colorImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(COLOR_FORMAT);
            ivci
                .components()
                .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                .a(VK_COMPONENT_SWIZZLE_IDENTITY);
            VkImageSubresourceRange sub = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
            ivci.subresourceRange(sub);
            int err = vkCreateImageView(device, ivci, null, pView);
            if (err != VK_SUCCESS) {
                return false;
            }
            colorImageView = pView.get(0);

            // Depth image (optimal, device-local, depth attachment)
            if (
                !createImage(
                    w,
                    h,
                    DEPTH_FORMAT,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    (img, mem) -> {
                        depthImage = img;
                        depthImageMemory = mem;
                    }
                )
            ) {
                return false;
            }
            // Depth view
            pView.rewind();
            VkImageViewCreateInfo divci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(depthImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(DEPTH_FORMAT);
            VkImageSubresourceRange dsub = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
            divci.subresourceRange(dsub);
            err = vkCreateImageView(device, divci, null, pView);
            if (err != VK_SUCCESS) {
                return false;
            }
            depthImageView = pView.get(0);

            // Framebuffer
            LongBuffer pFB = stack.mallocLong(1);
            LongBuffer attachments = stack.mallocLong(2);
            attachments.put(0, colorImageView);
            attachments.put(1, depthImageView);

            VkExtent2D extent = VkExtent2D.calloc(stack).set(w, h);

            org.lwjgl.vulkan.VkFramebufferCreateInfo fbci =
                org.lwjgl.vulkan.VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(extent.width())
                    .height(extent.height())
                    .layers(1);

            err = vkCreateFramebuffer(device, fbci, null, pFB);
            if (err != VK_SUCCESS) {
                return false;
            }
            framebuffer = pFB.get(0);

            return true;
        }
    }

    private boolean createPipeline() {
        try (MemoryStack stack = stackPush()) {
            // Mesh shaders: vertex consumes interleaved attributes, transforms by MVP push constant
            String vertGLSL =
                "#version 450\n" +
                "layout(location=0) in vec3 inPos;\n" +
                "layout(location=1) in vec3 inNormal;\n" +
                "layout(location=2) in vec4 inColor;\n" +
                "layout(location=3) in vec2 inUV;\n" +
                "layout(location=0) out vec4 vColor;\n" +
                "layout(location=1) out vec2 vUV;\n" +
                "layout(push_constant) uniform Push { mat4 mvp; } pc;\n" +
                "void main(){\n" +
                "  gl_Position = pc.mvp * vec4(inPos, 1.0);\n" +
                "  vColor = inColor;\n" +
                "  vUV = inUV;\n" +
                "}\n";

            // SOLID fragment shader (no discard)
            String fragGLSLSolid =
                "#version 450\n" +
                "layout(set=0, binding=0) uniform sampler2D uAtlas;\n" +
                "layout(location=0) in vec4 vColor;\n" +
                "layout(location=1) in vec2 vUV;\n" +
                "layout(location=0) out vec4 outColor;\n" +
                "void main(){\n" +
                "  vec4 texel = texture(uAtlas, vUV);\n" +
                "  outColor = vColor * texel;\n" +
                "}\n";

            // CUTOUT fragment shader (alpha discard)
            String fragGLSLCutout =
                "#version 450\n" +
                "layout(set=0, binding=0) uniform sampler2D uAtlas;\n" +
                "layout(location=0) in vec4 vColor;\n" +
                "layout(location=1) in vec2 vUV;\n" +
                "layout(location=0) out vec4 outColor;\n" +
                "void main(){\n" +
                "  vec4 texel = texture(uAtlas, vUV);\n" +
                "  if (texel.a < 0.5) discard;\n" +
                "  outColor = vColor * texel;\n" +
                "}\n";

            ByteBuffer vertSpv = compileGLSL(vertGLSL, true);
            ByteBuffer fragSpvSolid = compileGLSL(fragGLSLSolid, false);
            ByteBuffer fragSpvCutout = compileGLSL(fragGLSLCutout, false);
            if (
                vertSpv == null || fragSpvSolid == null || fragSpvCutout == null
            ) {
                System.err.println(
                    "OffscreenWorldRenderer: shader compile failed."
                );
                return false;
            }

            // Shader modules
            LongBuffer pShader = stack.mallocLong(1);
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(
                stack
            )
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(vertSpv);
            int err = vkCreateShaderModule(device, smci, null, pShader);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateShaderModule (vert) failed: " +
                    toVk(err)
                );
                return false;
            }
            vertModule = pShader.get(0);

            smci.pCode(fragSpvSolid);
            pShader.rewind();
            err = vkCreateShaderModule(device, smci, null, pShader);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateShaderModule (frag solid) failed: " +
                    toVk(err)
                );
                return false;
            }
            fragModule = pShader.get(0);

            smci.pCode(fragSpvCutout);
            pShader.rewind();
            err = vkCreateShaderModule(device, smci, null, pShader);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateShaderModule (frag cutout) failed: " +
                    toVk(err)
                );
                return false;
            }
            fragModuleCutout = pShader.get(0);

            // Descriptor set layout for combined image sampler (atlas)
            LongBuffer pSetLayout = stack.mallocLong(1);
            org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.Buffer slb =
                org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.calloc(1, stack);
            slb
                .get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo dlci =
                org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(slb);
            err = vkCreateDescriptorSetLayout(device, dlci, null, pSetLayout);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateDescriptorSetLayout failed: " +
                    toVk(err)
                );
                return false;
            }
            descriptorSetLayout = pSetLayout.get(0);

            // Pipeline layout with set layout + push constants for MVP
            LongBuffer pPL = stack.mallocLong(1);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(
                1,
                stack
            );
            pcr
                .get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                .offset(0)
                .size(64);

            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(
                stack
            )
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pcr);
            err = vkCreatePipelineLayout(device, plci, null, pPL);
            if (err != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreatePipelineLayout failed: " +
                    toVk(err)
                );
                return false;
            }
            pipelineLayout = pPL.get(0);

            // Vertex input for interleaved attributes: pos3 (0), normal3 (1), color4 (2), uv2 (3)
            VkVertexInputBindingDescription.Buffer bindings =
                VkVertexInputBindingDescription.calloc(1, stack);
            bindings
                .get(0)
                .binding(0)
                .stride(12 * Float.BYTES)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attrs =
                VkVertexInputAttributeDescription.calloc(4, stack);
            attrs
                .get(0)
                .binding(0)
                .location(0)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(0);
            attrs
                .get(1)
                .binding(0)
                .location(1)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(3 * Float.BYTES);
            attrs
                .get(2)
                .binding(0)
                .location(2)
                .format(VK_FORMAT_R32G32B32A32_SFLOAT)
                .offset(6 * Float.BYTES);
            attrs
                .get(3)
                .binding(0)
                .location(3)
                .format(VK_FORMAT_R32G32_SFLOAT)
                .offset(10 * Float.BYTES);

            // Fixed-function state
            VkPipelineVertexInputStateCreateInfo vi =
                VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
                    )
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attrs);

            VkPipelineInputAssemblyStateCreateInfo ia =
                VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
                    )
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp
                .get(0)
                .x(0)
                .y(0)
                .width(width)
                .height(height)
                .minDepth(0.0f)
                .maxDepth(1.0f);

            VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
            sc.get(0).offset().set(0, 0);
            sc.get(0).extent().set(width, height);

            VkPipelineViewportStateCreateInfo vpState =
                VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
                    )
                    .pViewports(vp)
                    .pScissors(sc);

            VkPipelineRasterizationStateCreateInfo rs =
                VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
                    )
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1.0f)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false);

            VkPipelineMultisampleStateCreateInfo ms =
                VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
                    )
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo ds =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO
                    )
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer cbAttach =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
            cbAttach
                .get(0)
                .colorWriteMask(
                    VK_COLOR_COMPONENT_R_BIT |
                    VK_COLOR_COMPONENT_G_BIT |
                    VK_COLOR_COMPONENT_B_BIT |
                    VK_COLOR_COMPONENT_A_BIT
                )
                .blendEnable(false);

            VkPipelineColorBlendStateCreateInfo cb =
                VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(
                        VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
                    )
                    .pAttachments(cbAttach);

            // Enable dynamic viewport and scissor; actual values set with vkCmdSetViewport/Scissor
            java.nio.IntBuffer dynamics = stack.ints(
                VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR
            );
            VkPipelineDynamicStateCreateInfo dyn =
                VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamics);

            // Common pipeline create info holder
            VkGraphicsPipelineCreateInfo.Buffer gpc =
                VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gpc
                .get(0)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pVertexInputState(vi)
                .pInputAssemblyState(ia)
                .pViewportState(vpState)
                .pRasterizationState(rs)
                .pMultisampleState(ms)
                .pDepthStencilState(ds)
                .pColorBlendState(cb)
                .pDynamicState(dyn)
                .layout(pipelineLayout)
                .renderPass(renderPass)
                .subpass(0);

            // Build SOLID pipeline
            VkPipelineShaderStageCreateInfo.Buffer stagesSolid =
                VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stagesSolid
                .get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(stack.ASCII("main"));
            stagesSolid
                .get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(stack.ASCII("main"));
            gpc.get(0).pStages(stagesSolid);

            LongBuffer pPipe = stack.mallocLong(1);
            int err2 = vkCreateGraphicsPipelines(
                device,
                VK_NULL_HANDLE,
                gpc,
                null,
                pPipe
            );
            if (err2 != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateGraphicsPipelines (solid) failed: " +
                    toVk(err2)
                );
                return false;
            }
            pipeline = pPipe.get(0);

            // Build CUTOUT pipeline (alpha discard shader)
            VkPipelineShaderStageCreateInfo.Buffer stagesCutout =
                VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stagesCutout
                .get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(stack.ASCII("main"));
            stagesCutout
                .get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModuleCutout)
                .pName(stack.ASCII("main"));
            gpc.get(0).pStages(stagesCutout);
            pPipe.rewind();
            int err3 = vkCreateGraphicsPipelines(
                device,
                VK_NULL_HANDLE,
                gpc,
                null,
                pPipe
            );
            if (err3 != VK_SUCCESS) {
                System.err.println(
                    "OffscreenWorldRenderer: vkCreateGraphicsPipelines (cutout) failed: " +
                    toVk(err3)
                );
                return false;
            }
            pipelineCutout = pPipe.get(0);

            return true;
        }
    }

    private void destroyPipeline() {
        if (device == null) return;
        if (pipeline != 0L) {
            vkDestroyPipeline(device, pipeline, null);
            pipeline = 0L;
        }
        if (pipelineCutout != 0L) {
            vkDestroyPipeline(device, pipelineCutout, null);
            pipelineCutout = 0L;
        }
        if (pipelineLayout != 0L) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (vertModule != 0L) {
            vkDestroyShaderModule(device, vertModule, null);
            vertModule = 0L;
        }
        if (fragModule != 0L) {
            vkDestroyShaderModule(device, fragModule, null);
            fragModule = 0L;
        }
        if (fragModuleCutout != 0L) {
            vkDestroyShaderModule(device, fragModuleCutout, null);
            fragModuleCutout = 0L;
        }
    }

    private boolean createReadbackImage() {
        // Linear tiled, host-visible, transfer destination
        return createImage(
            width,
            height,
            COLOR_FORMAT,
            VK_IMAGE_TILING_LINEAR,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            (img, mem) -> {
                readbackImage = img;
                readbackImageMemory = mem;
            }
        );
    }

    private boolean createImage(
        int w,
        int h,
        int format,
        int tiling,
        int usage,
        int memoryProperties,
        ImageAllocConsumer out
    ) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .extent(VkExtent3D(stack, w, h, 1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(tiling)
                .usage(usage)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImg = stack.mallocLong(1);
            int err = vkCreateImage(device, ici, null, pImg);
            if (err != VK_SUCCESS) {
                return false;
            }
            long image = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, req);
            int memTypeIndex = findMemoryType(
                req.memoryTypeBits(),
                memoryProperties
            );
            if (memTypeIndex < 0) {
                vkDestroyImage(device, image, null);
                return false;
            }

            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memTypeIndex);
            LongBuffer pMem = stack.mallocLong(1);
            err = vkAllocateMemory(device, mai, null, pMem);
            if (err != VK_SUCCESS) {
                vkDestroyImage(device, image, null);
                return false;
            }
            long memory = pMem.get(0);
            vkBindImageMemory(device, image, memory, 0);

            out.accept(image, memory);
            return true;
        }
    }

    private int findMemoryType(int typeFilter, int properties) {
        if (memProps == null) return -1;
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if (
                ((typeFilter & (1 << i)) != 0) &&
                (memProps.memoryTypes(i).propertyFlags() & properties) ==
                properties
            ) {
                return i;
            }
        }
        return -1;
    }

    private void transitionImageLayout(
        MemoryStack stack,
        org.lwjgl.vulkan.VkCommandBuffer cmdBuffer,
        long image,
        int format,
        int oldLayout,
        int newLayout,
        int aspectMask
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(
            1,
            stack
        )
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image);

        VkImageSubresourceRange subRange = VkImageSubresourceRange.calloc(stack)
            .aspectMask(aspectMask)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(1);
        barrier.subresourceRange(subRange);

        int srcStage;
        int dstStage;
        int srcAccessMask = 0;
        int dstAccessMask = 0;

        if (
            oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        ) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            srcAccessMask = 0;
            dstAccessMask =
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        } else if (
            oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL &&
            newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        ) {
            srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        } else if (
            oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        ) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            srcAccessMask = 0;
            dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        } else if (
            oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL &&
            newLayout == VK_IMAGE_LAYOUT_GENERAL
        ) {
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        } else if (
            oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        ) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
            srcAccessMask = 0;
            dstAccessMask =
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        } else {
            // Fallback generic
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        }

        barrier.srcAccessMask(srcAccessMask);
        barrier.dstAccessMask(dstAccessMask);

        vkCmdPipelineBarrier(
            cmdBuffer,
            srcStage,
            dstStage,
            0,
            null,
            null,
            barrier
        );
    }

    private BufferedImage readBackToImage() {
        try (MemoryStack stack = stackPush()) {
            // Query layout for the linear image
            VkImageSubresource sub = VkImageSubresource.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .arrayLayer(0);
            VkSubresourceLayout layout = VkSubresourceLayout.calloc(stack);
            vkGetImageSubresourceLayout(device, readbackImage, sub, layout);

            long offset = layout.offset();
            long rowPitch = layout.rowPitch();

            // Map memory
            PointerBuffer pp = stack.mallocPointer(1);
            int err = vkMapMemory(
                device,
                readbackImageMemory,
                0,
                VK_WHOLE_SIZE,
                0,
                pp
            );
            if (err != VK_SUCCESS) {
                return null;
            }
            long ptr = pp.get(0);

            // Create BufferedImage and copy row by row considering pitch
            BufferedImage img = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_RGB
            );

            long base = ptr + offset;
            for (int y = 0; y < height; y++) {
                long rowPtr = base + y * rowPitch;
                for (int x = 0; x < width; x++) {
                    int r = MemoryUtil.memGetByte(rowPtr + x * 4) & 0xFF;
                    int g = MemoryUtil.memGetByte(rowPtr + x * 4 + 1) & 0xFF;
                    int b = MemoryUtil.memGetByte(rowPtr + x * 4 + 2) & 0xFF;
                    // int a = MemoryUtil.memGetByte(rowPtr + x * 4 + 3) & 0xFF; // unused
                    int rgb = (r << 16) | (g << 8) | b;
                    img.setRGB(x, y, rgb);
                }
            }

            vkUnmapMemory(device, readbackImageMemory);
            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    // Utility methods

    private boolean markFailed(String reason) {
        System.err.println("OffscreenWorldRenderer init failed: " + reason);
        available.set(false);
        initialized.set(false);
        return false;
    }

    private static String toVk(int err) {
        return "VKResult(" + err + ")";
    }

    private static org.lwjgl.vulkan.VkExtent3D VkExtent3D(
        MemoryStack stack,
        int w,
        int h,
        int d
    ) {
        org.lwjgl.vulkan.VkExtent3D e = org.lwjgl.vulkan.VkExtent3D.calloc(
            stack
        );
        e.width(w).height(h).depth(d);
        return e;
    }

    private static org.lwjgl.vulkan.VkImageSubresourceLayers isrl(
        MemoryStack stack,
        int aspect,
        int mip,
        int layer
    ) {
        return org.lwjgl.vulkan.VkImageSubresourceLayers.calloc(stack)
            .aspectMask(aspect)
            .mipLevel(mip)
            .baseArrayLayer(layer)
            .layerCount(1);
    }

    // Build a VkImageCopy buffer from a single BufferImageCopy definition
    private static org.lwjgl.vulkan.VkImageCopy.Buffer VkImageCopy1(
        VkBufferImageCopy.Buffer bic,
        MemoryStack stack
    ) {
        org.lwjgl.vulkan.VkImageCopy.Buffer icb =
            org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
        org.lwjgl.vulkan.VkImageCopy ic = icb.get(0);
        // Regions: since we're copying entire image at mip=0, layer=0, extent from bic
        ic
            .srcSubresource()
            .aspectMask(bic.get(0).imageSubresource().aspectMask())
            .mipLevel(bic.get(0).imageSubresource().mipLevel())
            .baseArrayLayer(bic.get(0).imageSubresource().baseArrayLayer())
            .layerCount(1);
        ic.srcOffset().set(bic.get(0).imageOffset());
        ic
            .dstSubresource()
            .aspectMask(bic.get(0).imageSubresource().aspectMask())
            .mipLevel(bic.get(0).imageSubresource().mipLevel())
            .baseArrayLayer(bic.get(0).imageSubresource().baseArrayLayer())
            .layerCount(1);
        ic.dstOffset().set(bic.get(0).imageOffset());
        ic.extent().set(bic.get(0).imageExtent());
        return icb;
    }

    // GLSL -> SPIR-V using shaderc at runtime (lwjgl-shaderc is included by the project)
    private ByteBuffer compileGLSL(String glsl, boolean vertex) {
        // Use LWJGL Shaderc directly to avoid reflection issues
        try {
            long compiler =
                org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
            if (compiler == 0L) {
                System.err.println(
                    "OffscreenWorldRenderer: shaderc compiler init returned 0"
                );
                return null;
            }
            long options =
                org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
            if (options == 0L) {
                org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(
                    compiler
                );
                System.err.println(
                    "OffscreenWorldRenderer: shaderc compile options init returned 0"
                );
                return null;
            }

            int kind = vertex
                ? org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
                : org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;

            String entryPoint = "main";
            long result =
                org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(
                    compiler,
                    glsl,
                    kind,
                    "offscreen.glsl",
                    entryPoint,
                    options
                );

            int status =
                org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(
                    result
                );
            if (
                status !=
                org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success
            ) {
                String msg =
                    org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(
                        result
                    );
                System.err.println(
                    "OffscreenWorldRenderer: shaderc compile error (" +
                    (vertex ? "vert" : "frag") +
                    "): " +
                    msg
                );
                org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
                org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(
                    options
                );
                org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(
                    compiler
                );
                return null;
            }

            ByteBuffer spv =
                org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(result);
            // Copy to a fresh buffer we own since the result buffer is owned by shaderc
            ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(spv),
                MemoryUtil.memAddress(copy),
                spv.remaining()
            );

            org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(
                options
            );
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);

            copy.rewind();
            return copy;
        } catch (Throwable t) {
            System.err.println(
                "OffscreenWorldRenderer: shaderc not available or failed - " +
                t.getMessage()
            );
            return null;
        }
    }

    // Buffer allocation helper
    private static final class BufferAlloc {

        final long buffer;
        final long memory;

        BufferAlloc(long b, long m) {
            this.buffer = b;
            this.memory = m;
        }
    }

    private BufferAlloc createBuffer(long size, int usage, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            int err = vkCreateBuffer(device, bci, null, pBuf);
            if (err != VK_SUCCESS) return null;
            long buf = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buf, req);
            int memTypeIndex = findMemoryType(req.memoryTypeBits(), properties);
            if (memTypeIndex < 0) {
                vkDestroyBuffer(device, buf, null);
                return null;
            }

            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memTypeIndex);
            LongBuffer pMem = stack.mallocLong(1);
            err = vkAllocateMemory(device, mai, null, pMem);
            if (err != VK_SUCCESS) {
                vkDestroyBuffer(device, buf, null);
                return null;
            }
            long mem = pMem.get(0);
            vkBindBufferMemory(device, buf, mem, 0);
            return new BufferAlloc(buf, mem);
        }
    }

    private boolean uploadBuffer(long dstBuffer, ByteBuffer data) {
        // Create staging
        BufferAlloc staging = createBuffer(
            data.remaining(),
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        if (staging == null) return false;

        // Map and write
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            int err = vkMapMemory(
                device,
                staging.memory,
                0,
                data.remaining(),
                0,
                pp
            );
            if (err != VK_SUCCESS) {
                vkDestroyBuffer(device, staging.buffer, null);
                vkFreeMemory(device, staging.memory, null);
                return false;
            }
            long dstPtr = pp.get(0);
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(data),
                dstPtr,
                data.remaining()
            );
            vkUnmapMemory(device, staging.memory);
        }

        // Record and submit copy
        org.lwjgl.vulkan.VkCommandBuffer cb = beginOneTimeCommands();
        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0).dstOffset(0).size(data.remaining());
            vkCmdCopyBuffer(cb, staging.buffer, dstBuffer, region);
        }
        endOneTimeCommands(cb);

        // Cleanup staging
        vkDestroyBuffer(device, staging.buffer, null);
        vkFreeMemory(device, staging.memory, null);
        return true;
    }

    private org.lwjgl.vulkan.VkCommandBuffer beginOneTimeCommands() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo cbAlloc =
                VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCB = stack.mallocPointer(1);
            int err = vkAllocateCommandBuffers(device, cbAlloc, pCB);
            if (err != VK_SUCCESS) return null;
            org.lwjgl.vulkan.VkCommandBuffer cb =
                new org.lwjgl.vulkan.VkCommandBuffer(pCB.get(0), device);
            VkCommandBufferBeginInfo beginInfo =
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cb, beginInfo);
            return cb;
        }
    }

    private void endOneTimeCommands(org.lwjgl.vulkan.VkCommandBuffer cb) {
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(cb);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cb));
            LongBuffer pFence = stack.mallocLong(1);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType(
                VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
            );
            if (vkCreateFence(device, fci, null, pFence) != VK_SUCCESS) return;
            long fence = pFence.get(0);
            vkQueueSubmit(graphicsQueue, submitInfo, fence);
            vkWaitForFences(device, pFence.rewind(), true, 10_000_000_000L);
            vkDestroyFence(device, fence, null);
            vkResetCommandPool(
                device,
                commandPool,
                VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT
            );
        }
    }

    // Camera math: build MVP with Vulkan clip space (flip Y in projection)
    private static float[] computeMVP(
        double x,
        double y,
        double z,
        float pitchDeg,
        float yawDeg,
        int width,
        int height,
        float fovDeg,
        float near,
        float far
    ) {
        float aspect = (float) width / (float) height;
        float[] proj = perspective(fovDeg, aspect, near, far);
        // Flip Y for Vulkan
        proj[5] *= -1.0f;

        // View matrix (look direction from yaw/pitch)
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);

        float fx = -sy * cp;
        float fy = sp;
        float fz = cy * cp;

        float[] eye = new float[] { (float) x, (float) y, (float) z };
        float[] center = new float[] { eye[0] + fx, eye[1] + fy, eye[2] + fz };
        float[] up = new float[] { 0f, 1f, 0f };

        float[] view = lookAt(eye, center, up);
        return multiply(proj, view);
    }

    private static float[] perspective(
        float fovDeg,
        float aspect,
        float zNear,
        float zFar
    ) {
        float f = 1.0f / (float) Math.tan(Math.toRadians(fovDeg) * 0.5f);
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (zFar + zNear) / (zNear - zFar);
        m[11] = -1.0f;
        m[14] = (2.0f * zFar * zNear) / (zNear - zFar);
        return m;
    }

    private static float[] lookAt(float[] eye, float[] center, float[] up) {
        float fx = center[0] - eye[0],
            fy = center[1] - eye[1],
            fz = center[2] - eye[2];
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;

        float sx = fy * up[2] - fz * up[1];
        float sy = fz * up[0] - fx * up[2];
        float sz = fx * up[1] - fy * up[0];
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl;
        sy /= sl;
        sz /= sl;

        float ux = sy * fz - sz * fy;
        float uy = sz * fx - sx * fz;
        float uz = sx * fy - sy * fx;

        float[] m = new float[16];
        m[0] = sx;
        m[4] = ux;
        m[8] = -fx;
        m[12] = 0f;
        m[1] = sy;
        m[5] = uy;
        m[9] = -fy;
        m[13] = 0f;
        m[2] = sz;
        m[6] = uz;
        m[10] = -fz;
        m[14] = 0f;
        m[3] = 0f;
        m[7] = 0f;
        m[11] = 0f;
        m[15] = 1f;

        float[] t = new float[16];
        t[0] = 1;
        t[5] = 1;
        t[10] = 1;
        t[15] = 1;
        t[12] = -eye[0];
        t[13] = -eye[1];
        t[14] = -eye[2];

        return multiply(m, t);
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        // Column-major mat mul: r = a * b
        for (int c = 0; c < 4; c++) {
            for (int rIdx = 0; rIdx < 4; rIdx++) {
                r[c * 4 + rIdx] =
                    a[0 * 4 + rIdx] * b[c * 4 + 0] +
                    a[1 * 4 + rIdx] * b[c * 4 + 1] +
                    a[2 * 4 + rIdx] * b[c * 4 + 2] +
                    a[3 * 4 + rIdx] * b[c * 4 + 3];
            }
        }
        return r;
    }

    // Functional interface to capture created image + memory
    @FunctionalInterface
    private interface ImageAllocConsumer {
        void accept(long image, long memory);
    }

    // Probe depth formats supported by the GPU. Returns 0 if none found.
    private int probeSupportedDepthFormat() {
        try (MemoryStack stack = stackPush()) {
            int[] candidates = new int[] {
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D24_UNORM_S8_UINT,
                VK_FORMAT_D16_UNORM,
            };
            for (int fmt : candidates) {
                org.lwjgl.vulkan.VkFormatProperties props =
                    org.lwjgl.vulkan.VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(physicalDevice, fmt, props);
                int features = props.optimalTilingFeatures();
                if (
                    (features &
                        VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) !=
                    0
                ) {
                    return fmt;
                }
            }
            return 0;
        }
    }
}
