package org.lwjgl.vulkangen

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
    "formats-requiring-sampler-ycbcr-conversion" to "Formats requiring sampler Y'C<sub>B</sub>C<sub>R</sub> conversion for #IMAGE_ASPECT_COLOR_BIT image views",
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
    "memory" to "Memory Allocation",
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
    /*
    "a = 0.948" to codeBlock("a = 0.948"),
    "b = 0.052" to codeBlock("b = 0.052"),
    "c = 0.077" to codeBlock("c = 0.077"),
    """\alpha = 1.099 \text{ and } \beta = 0.018 \text{ for 10-bits and less per sample system (the values given in Rec. 709)}""" to
        codeBlock("""α = 1.099 and β = 0.018 for 10-bits and less per sample system (the values given in Rec.709)"""),
    """\alpha = 1.0993 \text{ and } \beta = 0.0181 \text{ for 12-bits per sample system}""" to
        codeBlock("""α = 1.0993 and β = 0.0181 for 12-bits per sample system"""),
    """m_1 = 2610 / 4096 \times \frac{1}{4} = 0.1593017578125""" to
        codeBlock("""m<sub>1</sub> = 2610 / 4096 &times; 1/4 = 0.1593017578125"""),
    """m_2 = 2523 / 4096 \times 128 = 78.84375""" to
        codeBlock("""m<sub>2</sub> = 2523 / 4096 &times; 128 = 78.84375"""),
    """c_1 = 3424 / 4096 = 0.8359375 = c3 - c2 + 1""" to
        codeBlock("""c<sub>1</sub> = 3424 / 4096 = 0.8359375 = c3 - c2 + 1"""),
    """c_2 = 2413 / 4096 \times 32 = 18.8515625""" to
        codeBlock("""c<sub>2</sub> = 2413 / 4096 &times; 32 = 18.8515625"""),
    """c_3 = 2392 / 4096 \times 32 = 18.6875""" to
        codeBlock("""c<sub>3</sub> = 2392 / 4096 &times; 32 = 18.6875"""),
    """L \text{ - is the signal normalized by the reference white level}""" to
        codeBlock("""L - is the signal normalized by the reference white level"""),
    """r \text{ - is the reference white level and has a signal value of 0.5}""" to
        codeBlock("""r - is the reference white level and has a signal value of 0.5"""),
    """a = 0.17883277 \text{ and } b = 0.28466892 \text{, and } c = 0.55991073""" to
        codeBlock("""a = 0.17883277 and b = 0.28466892, and c = 0.55991073"""),
    """E = L^\frac{1}{2.19921875}""" to
        codeBlock("""E = L^<sup>1 / 2.19921875</sup>"""),
    """E = L^\frac{1}{2.6}""" to
        codeBlock("""E = L^<sup>1 / 2.6</sup>"""),*/
    """\lceil{\mathit{rasterizationSamples} \over 32}\rceil""" to "{@code ceil(rasterizationSamples / 32)}",
    """\textrm{codeSize} \over 4""" to "{@code codeSize / 4}",
    """\frac{k}{2^m - 1}""" to "<code>k / (2<sup>m</sup> - 1)</code>",

    """m = \sqrt{ \left({{\partial z_f} \over {\partial x_f}}\right)^2
        +  \left({{\partial z_f} \over {\partial y_f}}\right)^2}""" to
        codeBlock("      m = sqrt((&part;z<sub>f</sub> / &part;x<sub>f</sub>)<sup>2</sup> + (&part;z<sub>f</sub> / &part;y<sub>f</sub>)<sup>2</sup>)"),

    """m = \max\left( \left| { {\partial z_f} \over {\partial x_f} } \right|,
               \left| { {\partial z_f} \over {\partial y_f} } \right|
       \right).""" to
        codeBlock("      m = max(abs(&part;z<sub>f</sub> / &part;x<sub>f</sub>), abs(&part;z<sub>f</sub> / &part;y<sub>f</sub>))"),

    """\begin{aligned}
o &= \mathrm{dbclamp}( m \times \mathtt{depthBiasSlopeFactor} + r \times \mathtt{depthBiasConstantFactor} ) \\
\text{where} &\quad \mathrm{dbclamp}(x) =
\begin{cases}
    x                                 & \mathtt{depthBiasClamp} = 0 \ \text{or}\ \texttt{NaN} \\
    \min(x, \mathtt{depthBiasClamp})  & \mathtt{depthBiasClamp} > 0 \\
    \max(x, \mathtt{depthBiasClamp})  & \mathtt{depthBiasClamp} < 0 \\
\end{cases}
\end{aligned}""" to
        codeBlock("""
        m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor                     depthBiasClamp = 0 or NaN
o = min(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &gt; 0
    max(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &lt; 0"""),

    /*"""\begin{aligned}
E & =
  \begin{cases}
    1.055 \times L^{1 \over 2.4} - 0.055 & \text{for}\  0.0031308 \leq L \leq 1 \\
    12.92 \times L                       & \text{for}\  0 \leq L < 0.0031308
  \end{cases}
\end{aligned}""" to
        codeBlock("""
E =  1.055 &times; L<sup>1/2.4</sup> - 0.055 for 0.0031308 &le; L &le; 1
    12.92  &times; L for 0 &le; L &lt 0.0031308"""),*/

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

    /*"""\begin{aligned}
E & =
  \begin{cases}
    1.055 \times L^{1 \over 2.4} - 0.055 & \text{for}\  0.0031308 \leq L \leq 7.5913 \\
    12.92 \times L                       & \text{for}\  0 \leq L < 0.0031308 \\
    -f(-L)                               & \text{for}\  L < 0
  \end{cases}
\end{aligned}""" to
        codeBlock("""
     1.055 &times;  L<sup>1/2.4</sup> - 0.055 for 0.0031308 &le; L &le; 7.5913
E = 12.92  &times;  L for 0 &le; L &lt 0.0031308
    -f(-L) for L &lt; 0"""),

    """\begin{aligned}
E & =
  \begin{cases}
    (a \times L + b)^{2.4} & \text{for}\  0.039 \leq L \leq 1 \\
    b \times L                    & \text{for}\  0 \leq L < 0.039
  \end{cases}
\end{aligned}""" to codeBlock("""
E = (a &times; L + b)<sup>2.4</sup> for 0.039 &le; L &le; 1
    b &times; L for 0 &le; L &lt; 0.039"""),

    """\begin{aligned}
E & =
  \begin{cases}
    \alpha \times L^{0.45} - (1 - \alpha) & \text{for}\  \beta \leq L \leq 1 \\
    4.5 \times L                            & \text{for}\  0 \leq L < \beta
  \end{cases}
\end{aligned}""" to codeBlock("""
E = α &times; L^<sup>0.45</sup> - (1 - α) for β &le; L &le; 1
    4.5 &times; L for 0 &le; L &lt; β"""),

    """\[
E = (\frac{c_1 + c_2 \times L^{m_1}}{1 + c_3 \times L^{m_1}})^{m_2}
\]""" to codeBlock("""
E = ((c_<sub>1</sub> + c_<sub>2</sub> &times; L^<sup>m_<sub>1</sub></sup>) / (1 + c_<sub>3</sub> &times; L^<sup>m_<sub>1</sub></sup>))^<sup>m_<sub>2</sub></sup>"""),

    """\begin{aligned}
E & =
  \begin{cases}
    r \sqrt{L}                & \text{for}\  0 \leq L \leq 1 \\
    a \times \ln(L - b) + c    & \text{for}\  1 < L
  \end{cases}
\end{aligned}""" to codeBlock("""
E = r &times; sqrt(L) for 0 &le; L &le; 1
    a &times; ln(L - b) + c for 1 &lt L""")*/
    """\left\lfloor i_G \times 0.5 \right\rfloor = i_B = i_R""" to "<code>floor(i<sub>G</sub> &times; 0.5) = i<sub>B</sub> = i<sub>R</sub></code>",
    """\left\lfloor j_G \times 0.5
\right\rfloor = j_B = j_R""" to "<code>floor(j<sub>G</sub> &times; 0.5) = j<sub>B</sub> = j<sub>R</sub></code>",
    "\\left\\lceil{\\frac{width}{maxFragmentDensityTexelSize_{width}}}\\right\\rceil" to "{@code ceil(width / maxFragmentDensityTexelSize.width)}",
    "\\left\\lceil{\\frac{height}{maxFragmentDensityTexelSize_{height}}}\\right\\rceil" to "{@code ceil(height / maxFragmentDensityTexelSize.height)}",
    "\\left\\lceil{\\frac{maxFramebufferWidth}{minFragmentDensityTexelSize_{width}}}\\right\\rceil" to "{@code ceil(maxFramebufferWidth / minFragmentDensityTexelSize.width)}",
    "\\left\\lceil{\\frac{maxFramebufferHeight}{minFragmentDensityTexelSize_{height}}}\\right\\rceil" to "{@code ceil(maxFramebufferHeight / minFragmentDensityTexelSize.height)}",
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
</table>"""
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
