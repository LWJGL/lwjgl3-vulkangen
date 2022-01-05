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
    "1.2" to "11"
)

internal val DISABLED_EXTENSIONS = setOf(
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_EXT_directfb_surface",
    "VK_FUCHSIA_buffer_collection",
    "VK_FUCHSIA_imagepipe_surface",
    "VK_FUCHSIA_external_memory",
    "VK_FUCHSIA_external_semaphore",
    "VK_GGP_frame_token",
    "VK_GGP_stream_descriptor_surface",
    "VK_KHR_android_surface",
    "VK_KHR_xcb_surface",
    "VK_MVK_ios_surface",
    "VK_NN_vi_surface",
    "VK_QNX_screen_surface",
)

internal val EXTENSION_TOKEN_REPLACEMENTS = mapOf(
    "gcn" to "GCN",
    "glsl" to "GLSL",
    "gpu" to "GPU",
    "pvrtc" to "PVRTC"
)

internal val IMPORTS = mapOf(
    //"android/native_window.h" to Import("core.android.*", "org.lwjgl.system.android.*"),
    "QuartzCore.framework" to Import("core.macos.*", null),
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
    "wl_display",
    "wl_surface"
)

internal val MACROS = setOf(
    "MAKE_API_VERSION",
    "API_VERSION_VARIANT",
    "API_VERSION_MAJOR",
    "API_VERSION_MINOR",
    "API_VERSION_PATCH",
    "MAKE_VERSION",
    "VERSION_MAJOR",
    "VERSION_MINOR",
    "VERSION_PATCH"
)

internal fun configAPIConstantImports(enumClassMap: MutableMap<String, String>) {
    enumClassMap["VK_MAX_PHYSICAL_DEVICE_NAME_SIZE"] = "VK10"
    enumClassMap["VK_UUID_SIZE"] = "VK10"
    enumClassMap["VK_LUID_SIZE"] = "VK10"
    enumClassMap["VK_LUID_SIZE_KHR"] = "KHRExternalMemoryCapabilities"
    enumClassMap["VK_MAX_EXTENSION_NAME_SIZE"] = "VK10"
    enumClassMap["VK_MAX_DESCRIPTION_SIZE"] = "VK10"
    enumClassMap["VK_MAX_MEMORY_TYPES"] = "VK10"
    enumClassMap["VK_MAX_MEMORY_HEAPS"] = "VK10"
    enumClassMap["VK_LOD_CLAMP_NONE"] = "VK10"
    enumClassMap["VK_REMAINING_MIP_LEVELS"] = "VK10"
    enumClassMap["VK_REMAINING_ARRAY_LAYERS"] = "VK10"
    enumClassMap["VK_WHOLE_SIZE"] = "VK10"
    enumClassMap["VK_ATTACHMENT_UNUSED"] = "VK10"
    enumClassMap["VK_TRUE"] = "VK10"
    enumClassMap["VK_FALSE"] = "VK10"
    enumClassMap["VK_QUEUE_FAMILY_IGNORED"] = "VK10"
    enumClassMap["VK_QUEUE_FAMILY_EXTERNAL"] = "VK10"
    enumClassMap["VK_QUEUE_FAMILY_EXTERNAL_KHR"] = "KHRExternalMemory"
    enumClassMap["VK_QUEUE_FAMILY_FOREIGN_EXT"] = "EXTQueueFamilyForeign"
    enumClassMap["VK_SUBPASS_EXTERNAL"] = "VK10"
    enumClassMap["VK_MAX_DEVICE_GROUP_SIZE"] = "VK10"
    enumClassMap["VK_MAX_DEVICE_GROUP_SIZE_KHR"] = "KHRDeviceGroupCreation"
    enumClassMap["VK_MAX_DRIVER_NAME_SIZE"] = "VK10"
    enumClassMap["VK_MAX_DRIVER_NAME_SIZE_KHR"] = "KHRDriverProperties"
    enumClassMap["VK_MAX_DRIVER_INFO_SIZE"] = "VK10"
    enumClassMap["VK_MAX_DRIVER_INFO_SIZE_KHR"] = "KHRDriverProperties"
    enumClassMap["VK_SHADER_UNUSED_KHR"] = "KHRRayTracingPipeline"
    enumClassMap["VK_SHADER_UNUSED_NV"] = "NVRayTracing"
    enumClassMap["VK_MAX_GLOBAL_PRIORITY_SIZE_EXT"] = "EXTGlobalPriorityQuery"
}

private val UNNAMED_XREFS = setOf(
    "vkAllocationFunction_return_rules"
)
internal fun hasUnnamedXREF(section: String) = UNNAMED_XREFS.contains(section)

private val SECTION_XREFS = mapOf(
    "acceleration-structure-inactive-prims" to "Inactive Primitives and Instances",
    "clears" to "Clear Commands",
    "clears-values" to "Clear Values",
    "copies" to "Copy Commands",
    "copies-buffers-images-rotation-addressing" to "Buffer and Image Addressing with Rotation",
    "debug-report-object-types" to "{@code VkDebugReportObjectTypeEXT} and Vulkan Handle Relationship",
    "descriptorsets-combinedimagesampler" to "Combined Image Sampler",
    "descriptorsets-compatibility" to "Pipeline Layout Compatibility",
    "descriptorsets-inputattachment" to "Input Attachment",
    "descriptorsets-sampledimage" to "Sampled Image",
    "descriptorsets-sampler" to "Sampler",
    "descriptorsets-sets" to "Descriptor Sets",
    "descriptorsets-storagebuffer" to "Storage Buffer",
    "descriptorsets-storagebufferdynamic" to "Dynamic Storage Buffer",
    "descriptorsets-storageimage" to "Storage Image",
    "descriptorsets-storagetexelbuffer" to "Storage Texel Buffer",
    "descriptorsets-uniformbuffer" to "Uniform Buffer",
    "descriptorsets-uniformbufferdynamic" to "Dynamic Uniform Buffer",
    "descriptorsets-uniformtexelbuffer" to "Uniform Texel Buffer",
    "descriptorsets-updates" to "Descriptor Set Updates",
    "descriptorsets-updates-consecutive" to "consecutive binding updates",
    "device-generated-commands" to "Device-Generated Commands",
    "devsandqueues-priority" to "Queue Priority",
    "dispatch" to "Dispatching Commands",
    "drawing-mesh-shading" to "Programmable Mesh Shading",
    "drawing-triangle-fans" to "Triangle Fans",
    "extendingvulkan-coreversions-versionnumbers" to "Version Numbers",
    "extendingvulkan-extensions" to "Extensions",
    "extendingvulkan-layers" to "Layers",
    "extendingvulkan-layers-devicelayerdeprecation" to "Device Layer Deprecation",
    "external-memory-handle-types-compatibility" to "External memory handle types compatibility",
    "formats-compatible-planes" to "Compatible formats of planes of multi-planar formats",
    "formats-numericformat" to "Interpretation of Numeric Format",
    "fragops-stencil" to "Stencil Test",
    "framebuffer-blendfactors" to "Blend Factors",
    "framebuffer-blending" to "Blending",
    "framebuffer-dsb" to "Dual-Source Blending",
    "fundamentals-fp10" to "Unsigned 10-Bit Floating-Point Numbers",
    "fundamentals-fp11" to "Unsigned 11-Bit Floating-Point Numbers",
    "fxvertex-attrib" to "Vertex Attributes",
    "fxvertex-input" to "Vertex Input Description",
    "geometry" to "Geometry Shading",
    "img-tessellation-topology-ul" to "Domain parameterization for tessellation primitive modes (upper-left origin)",
    "img-tessellation-topology-ll" to "Domain parameterization for tessellation primitive modes (lower-left origin)",
    "memory-device-hostaccess" to "Host Access to Device Memory Objects",
    "primsrast" to "Rasterization",
    "primsrast-fragment-shading-rate-attachment" to "Attachment Fragment Shading Rate",
    "primsrast-fragment-shading-rate-pipeline" to "Pipeline Fragment Shading Rate",
    "primsrast-fragment-shading-rate-primitive" to "Primitive Fragment Shading Rate",
    "primsrast-polygonmode" to "Polygon Mode",
    "queries-pipestats" to "Pipeline Statistics Queries",
    "resources-association" to "Resource Memory Association",
    "resources-image-views" to "Image Views",
    "samplers-maxAnisotropy" to "samplers-maxAnisotropy",
    "samplers-mipLodBias" to "samplers-mipLodBias",
    "shaders-vertex" to "Vertex Shaders",
    "synchronization-events" to "Events",
    "synchronization-queue-transfers" to "Queue Family Ownership Transfer",
    "tessellation" to "Tessellation",
    "textures-chroma-reconstruction" to "Chroma Reconstruction"
)
private val SECTION_XREFS_USED = HashSet<String>()
internal fun getSectionXREF(section: String): String {
    if (section.startsWith("VK_")) {
        return section
    }

    val text = SECTION_XREFS[section]
    if (text == null) {
        System.err.println("lwjgl: Missing section reference: $section")
        return section
    }
    SECTION_XREFS_USED.add(section)
    return text
}
internal fun printUnusedSectionXREFs() {
    SECTION_XREFS.keys.asSequence()
        .filter { !SECTION_XREFS_USED.contains(it) }
        .forEach {
            System.err.println("lwjgl: Unused section XREF:\n$it")
        }
}

private val LATEX_REGISTRY = mapOf(
    """                                              \begin{aligned}
                                                p_0(A_s,A_d) & = A_sA_d \\
                                                p_1(A_s,A_d) & = A_s(1-A_d) \\
                                                p_2(A_s,A_d) & = A_d(1-A_s) \\
                                              \end{aligned}""" to codeBlock("""
p<sub>0</sub>(A<sub>s</sub>, A<sub>d</sub>) = A<sub>s</sub>A<sub>d</sub> \\
p<sub>1</sub>(A<sub>s</sub>, A<sub>d</sub>) = A<sub>s</sub>(1 &minus; A<sub>d</sub>) \\
p<sub>2</sub>(A<sub>s</sub>, A<sub>d</sub>) = A<sub>d</sub>(1 &minus; A<sub>s</sub>) \\"""),
    """                                              \begin{aligned}
                                                p_0(A_s,A_d) & = min(A_s,A_d) \\
                                                p_1(A_s,A_d) & = max(A_s-A_d,0) \\
                                                p_2(A_s,A_d) & = max(A_d-A_s,0) \\
                                              \end{aligned}""" to codeBlock("""
p<sub>0</sub>(A<sub>s</sub>, A<sub>d</sub>) = min(A<sub>s</sub>, A<sub>d</sub>) \\
p<sub>1</sub>(A<sub>s</sub>, A<sub>d</sub>) = max(A<sub>s</sub> &minus; A<sub>d</sub>, 0) \\
p<sub>2</sub>(A<sub>s</sub>, A<sub>d</sub>) = max(A<sub>d</sub> &minus; A<sub>s</sub>, 0) \\"""),
    """                                              \begin{aligned}
                                                p_0(A_s,A_d) & = max(A_s+A_d-1,0) \\
                                                p_1(A_s,A_d) & = min(A_s,1-A_d) \\
                                                p_2(A_s,A_d) & = min(A_d,1-A_s) \\
                                              \end{aligned}""" to codeBlock("""
p<sub>0</sub>(A<sub>s</sub>, A<sub>d</sub>) = max(A<sub>s</sub> + A<sub>d</sub> &minus; 1, 0) \\
p<sub>1</sub>(A<sub>s</sub>, A<sub>d</sub>) = min(A<sub>s</sub>, 1 &minus; A<sub>d</sub>) \\
p<sub>2</sub>(A<sub>s</sub>, A<sub>d</sub>) = min(A<sub>d</sub>, 1 &minus; A<sub>s</sub>) \\"""),
    """\lceil{\mathit{rasterizationSamples} \over 32}\rceil""" to "{@code ceil(rasterizationSamples / 32)}",
    """\textrm{codeSize} \over 4""" to "{@code codeSize / 4}",
    """\frac{k}{2^m - 1}""" to "<code>k / (2<sup>m</sup> - 1)</code>",

    """\begin{aligned}
E & =
  \begin{cases}
    1.055 \times L^{1 \over 2.4} - 0.055 & \text{for}\  0.0030186 \leq L \leq 1 \\
    12.92 \times L                       & \text{for}\  0 \leq L < 0.0030186
  \end{cases}
\end{aligned}""" to
        codeBlock("""
E =  1.055 &times; L<sup>1/2.4</sup> - 0.055 for 0.0030186 &le; L &le; 1
    12.92  &times; L for 0 &le; L &lt; 0.0030186"""),

    """\left\lfloor i_G \times 0.5 \right\rfloor = i_B = i_R""" to "<code>floor(i<sub>G</sub> &times; 0.5) = i<sub>B</sub> = i<sub>R</sub></code>",
    """\left\lfloor j_G \times 0.5
\right\rfloor = j_B = j_R""" to "<code>floor(j<sub>G</sub> &times; 0.5) = j<sub>B</sub> = j<sub>R</sub></code>",
    "\\left\\lceil{\\frac{width}{maxFragmentDensityTexelSize_{width}}}\\right\\rceil" to "<code>ceil(width / maxFragmentDensityTexelSize<sub>width</sub>)</code>",
    "\\left\\lceil{\\frac{height}{maxFragmentDensityTexelSize_{height}}}\\right\\rceil" to "<code>ceil(height / maxFragmentDensityTexelSize<sub>height</sub>)</code>",
    "\\left\\lceil{\\frac{maxFramebufferWidth}{minFragmentDensityTexelSize_{width}}}\\right\\rceil" to "<code>ceil(maxFramebufferWidth / minFragmentDensityTexelSize<sub>width</sub>)</code>",
    "\\left\\lceil{\\frac{maxFramebufferHeight}{minFragmentDensityTexelSize_{height}}}\\right\\rceil" to "<code>ceil(maxFramebufferHeight / minFragmentDensityTexelSize<sub>height</sub>)</code>" +
        "",
    "\\pm\\infty" to "&plusmn;&infin;",
    """s = { WorkGroupSize.x \times WorkGroupSize.y \times WorkgroupSize.z \leq SubgroupSize \times maxComputeWorkgroupSubgroups }""" to "<code>s = { WorkGroupSize.x &times; WorkGroupSize.y &times; WorkgroupSize.z &le; SubgroupSize &times; maxComputeWorkgroupSubgroups }</code>",
    "2 \\times \\mathtt{VK\\_UUID\\_SIZE}" to "2 &times; {@code VK_UUID_SIZE}",
    """S =
\left(
    \begin{matrix}
        sx & a  & b  & pvx \\
        0  & sy & c  & pvy \\
        0  & 0  & sz & pvz
    \end{matrix}
\right)""" to """S =
<table>
    <tr><td>sx</td><td>a</td><td>b</td><td>pvx</td></tr>
    <tr><td>0</td><td>sy</td><td>c</td><td>pvy</td></tr>
    <tr><td>0</td><td>0</td><td>sz</td><td>pvz</td></tr>
</table>""",

    """T =
\left(
    \begin{matrix}
        1 & 0 & 0 & tx \\
        0 & 1 & 0 & ty \\
        0 & 0 & 1 & tz
    \end{matrix}
\right)""" to """T =
<table>
    <tr><td>1</td><td>0</td><td>0</td><td>tx</td></tr>
    <tr><td>0</td><td>1</td><td>0</td><td>ty</td></tr>
    <tr><td>0</td><td>0</td><td>1</td><td>tz</td></tr>
</table>""",
    """\left\lceil{\frac{renderArea_{x}+renderArea_{width}}{maxFragmentDensityTexelSize_{width}}}\right\rceil""" to
        """<code>ceil((renderArea<sub>x</sub>+renderArea<sub>width</sub>) / maxFragmentDensityTexelSize<sub>width</sub>)</code>""",
    """\left\lceil{\frac{pDeviceRenderAreas_{x}+pDeviceRenderAreas_{width}}{maxFragmentDensityTexelSize_{width}}}\right\rceil""" to
        """<code>ceil((pDeviceRenderAreas<sub>x</sub>+pDeviceRenderAreas<sub>width</sub>) / maxFragmentDensityTexelSize<sub>width</sub>)</code>""",
    """\left\lceil{\frac{renderArea_{y}+renderArea_{height}}{maxFragmentDensityTexelSize_{height}}}\right\rceil""" to
        """<code>ceil((renderArea<sub>y</sub>+renderArea<sub>height</sub>) / maxFragmentDensityTexelSize<sub>height</sub>)</code>""",
    """\left\lceil{\frac{pDeviceRenderAreas_{y}+pDeviceRenderAreas_{height}}{maxFragmentDensityTexelSize_{height}}}\right\rceil""" to
        """<code>ceil((pDeviceRenderAreas<sub>y</sub>+pDeviceRenderAreas<sub>height</sub>) / maxFragmentDensityTexelSize<sub>height</sub>)</code>""",
    """\left\lceil{\frac{renderArea_{x}+renderArea_{width}}{shadingRateAttachmentTexelSize_{width}}}\right\rceil""" to
        """<code>ceil((renderArea<sub>x</sub>+renderArea<sub>width</sub>) / shadingRateAttachmentTexelSize<sub>width</sub>)</code>""",
    """\left\lceil{\frac{pDeviceRenderAreas_{x}+pDeviceRenderAreas_{width}}{shadingRateAttachmentTexelSize_{width}}}\right\rceil""" to
        """<code>ceil((pDeviceRenderAreas<sub>x</sub>+pDeviceRenderAreas<sub>width</sub>) / shadingRateAttachmentTexelSize<sub>width</sub>)</code>""",
    """\left\lceil{\frac{renderArea_{y}+renderArea_{height}}{shadingRateAttachmentTexelSize_{height}}}\right\rceil""" to
        """<code>ceil((renderArea<sub>y</sub>+renderArea<sub>height</sub>) / shadingRateAttachmentTexelSize<sub>height</sub>)</code>""",
    """\left\lceil{\frac{pDeviceRenderAreas_{y}+pDeviceRenderAreas_{height}}{shadingRateAttachmentTexelSize_{height}}}\right\rceil""" to
        """<code>ceil((pDeviceRenderAreas<sub>y</sub>+pDeviceRenderAreas<sub>height</sub>) / shadingRateAttachmentTexelSize<sub>height</sub>)</code>"""
)
private val LATEX_REGISTRY_USED = HashSet<String>()
internal fun getLatexCode(source: String): String {
    //val code = LATEX_REGISTRY[source] ?: throw IllegalStateException("Missing LaTeX equation:\n$source")
    val code = LATEX_REGISTRY[source] ?: LATEX_REGISTRY[source.replace("\\s+".toRegex(), " ")]
    if (code == null) {
        System.err.println("lwjgl: Missing LateX equation:\n$source")
        return source
    }
    LATEX_REGISTRY_USED.add(source)
    return code
}
internal fun printUnusedLatexEquations() {
    LATEX_REGISTRY.keys.asSequence()
        .filter { !LATEX_REGISTRY_USED.contains(it) }
        .forEach {
            System.err.println("lwjgl: Unused LateX equation:\n$it")
        }
}
