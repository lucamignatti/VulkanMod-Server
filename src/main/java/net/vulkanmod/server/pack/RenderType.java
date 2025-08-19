package net.vulkanmod.server.pack;

/**
 * Server-side render type classification derived from resource-pack models/blockstates.
 *
 * This enum is intentionally minimal and independent of client classes.
 * It is used by the server mesher to route geometry into the appropriate pipeline:
 * - SOLID: Opaque geometry rendered in the solid pass.
 * - CUTOUT: Alpha-tested geometry (e.g., leaves, panes, fences) rendered in the cutout pass.
 * - TRANSLUCENT: Alpha-blended geometry (e.g., water, ice, honey) rendered in the translucent pass.
 * - BILLBOARD_CROSS: Crossed-quad billboards without biome tint (e.g., torch, dead_bush).
 * - BILLBOARD_CROSS_TINTED: Crossed-quad billboards with biome tint (e.g., short_grass, tall_grass, fern).
 * - WATER: Special-case water handling (still/flow textures and slightly lowered top face).
 */
public enum RenderType {
    SOLID,
    CUTOUT,
    TRANSLUCENT,
    BILLBOARD_CROSS,
    BILLBOARD_CROSS_TINTED,
    WATER
}
