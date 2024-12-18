package org.lwjgl.vulkangen

// Character sequence used for alignment
internal const val t = "    "

internal const val S = "\$"
internal const val QUOTES3 = "\"\"\""

internal const val HEADER = """/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 * MACHINE GENERATED FILE, DO NOT EDIT
 */
"""

internal val VERSION_HISTORY = mapOf(
    "1.1" to "10",
    "1.2" to "11",
    "1.3" to "12",
    "1.4" to "13"
)

internal val DISABLED_EXTENSIONS = setOf(
    //"VK_ANDROID_external_memory_android_hardware_buffer",
    //"VK_EXT_directfb_surface",
    "VK_FUCHSIA_buffer_collection",
    "VK_FUCHSIA_imagepipe_surface",
    "VK_FUCHSIA_external_memory",
    "VK_FUCHSIA_external_semaphore",
    "VK_GGP_frame_token",
    "VK_GGP_stream_descriptor_surface",
    //"VK_KHR_android_surface",
    //"VK_KHR_xcb_surface",
    "VK_MVK_ios_surface",
    "VK_NN_vi_surface",
    "VK_QNX_screen_surface",
    "VK_QNX_external_memory_screen_buffer",
)

internal val EXTENSION_TOKEN_REPLACEMENTS = mapOf(
    "av1" to "AV1",
    "gcn" to "GCN",
    "glsl" to "GLSL",
    "gpu" to "GPU",
    "pvrtc" to "PVRTC"
)

internal val IMPORTS = mapOf(
    //"android/native_window.h" to Import("core.android.*", "org.lwjgl.system.android.*"),
    "QuartzCore.h" to Import("core.macos.*", null),
    "Metal.h" to Import("core.macos.*", null),
    "IOSurface.h" to Import("core.macos.*", null),
    "vk_video/vulkan_video_codec_av1std.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_av1std_decode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_av1std_encode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h264std.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h264std_encode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h264std_decode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h265std.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h265std_decode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "vk_video/vulkan_video_codec_h265std_encode.h" to Import(null, "org.lwjgl.vulkan.video.*"),
    "wayland-client.h" to Import("core.linux.*", "org.lwjgl.system.linux.*"),
    "windows.h" to Import("core.windows.*", "org.lwjgl.system.windows.*"),
    "X11/Xlib.h" to Import("core.linux.*", "org.lwjgl.system.linux.*"),
    "X11/extensions/Xrandr.h" to Import("core.linux.*", "org.lwjgl.system.linux.*")
)

internal val SYSTEM_STRUCTS = mapOf<String, TypeStruct>(
    //
)

internal val SYSTEM_OPAQUE = setOf(
    "Display",
    "IDirectFB",
    "wl_display",
    "wl_surface",
    "xcb_connection_t"
)

internal val OPAQUE_PFN_TYPES = setOf(
    "PFN_vkVoidFunction",
    "PFN_vkGetInstanceProcAddrLUNARG"
)