/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import org.asciidoctor.*
import org.asciidoctor.ast.*
import org.asciidoctor.converter.*
import java.nio.charset.*
import java.nio.file.*
import java.util.*
import kotlin.streams.*

internal class FunctionDoc(
    val shortDescription: String,
    val cSpecification: String,
    val description: String,
    val seeAlso: String?,
    val parameters: Map<String, String>
)

internal class StructDoc(
    val shortDescription: String,
    val description: String,
    val seeAlso: String?,
    val members: Map<String, String>
)

internal class EnumDoc(
    val shortDescription: String,
    val description: String,
    val seeAlso: String
)

internal val FUNCTION_DOC = HashMap<String, FunctionDoc>(256)
internal val STRUCT_DOC = HashMap<String, StructDoc>(256)
internal val ENUM_DOC = HashMap<String, EnumDoc>(256)

internal val EXTENSION_TEMPLATES = HashMap<String, String>(64)
internal val EXTENSION_DOC = HashMap<String, String>(64)

private val ATTRIBS = mapOf(
    "sym1" to "✓",
    "sym2" to "†",
    "reg" to "®",
    "trade" to "™",
    "times" to "×",
    "cdot" to "⋅",
    "plus" to "+",
    "geq" to "≥",
    "leq" to "≤",
    "neq" to "≠",
    "leftarrow" to "←",
    "uparrow" to "↑",
    "rightarrow" to "→",
    "downarrow" to "↓",
    "elem" to "∈",
    "lnot" to "¬",
    "land" to "∧",
    "lor" to "∨",
    "oplus" to "⊕",
    "lceil" to "⌈",
    "rceil" to "⌉",
    "lfloor" to "⌊",
    "rfloor" to "⌋",
    "vert" to "|",
    "partial" to "∂",
    "onehalf" to "½",
    "onequarter" to "¼",
    "ldots" to "…",
    "forall" to "∀",
    "sqrt" to "√",
    "inf" to "∞",
    "plusmn" to "±",
    "alpha" to "α",
    "beta" to "β",
    "gamma" to "γ",
    "DeltaUpper" to "Δ",
    "delta" to "δ",
    "epsilon" to "ε",
    "eta" to "η",
    "theta" to "θ",
    "lambda" to "λ",
    "pi" to "π",
    "rho" to "ρ",
    "sigma" to "σ",
    "tau" to "τ",
    "phi" to "ϕ",
    "wbro" to ""
)

internal fun convert(root: Path, structs: Map<String, TypeStruct>) {
    val asciidoctor = Asciidoctor.Factory.create()

    // Register a converter that defeats conversions performed by asciidoctor inside lists.
    // This way all texts are uniform, simplifying the final conversion.
    asciidoctor
        .javaConverterRegistry()
        .register(PlainConverter::class.java, "plain")

    // Extension class documentation

    val appendices = root.resolve("appendices")
    // We parse extensions.txt to create a map of attributes to pass to asciidoctor.
    // The attributes are used in ifdef preprocessor directives in extensions.txt
    // to enable extensions.
    val extensionIDs = """^include::../(VK_\w+)\.txt\[]""".toRegex().let { regex ->
        (Files.lines(appendices.resolve("meta/current_extension_appendices.txt")).asSequence() +
            Files.lines(appendices.resolve("meta/deprecated_extension_appendices.txt")).asSequence())
            .mapNotNull {
                val result = regex.find(it)
                if (result == null) {
                    null
                } else {
                    val (extension) = result.destructured
                    if (DISABLED_EXTENSIONS.contains(extension)) null else extension
                }
            }
            .associateBy { it }
    }
    extensionIDs.keys
        .map { it.substring(3) }
        .associateTo(EXTENSION_TEMPLATES) {
            it to it.template
        }

    // TODO: As of 1.0.42 the attribs.txt include doesn't work
    val attribs = AttributesBuilder.attributes()
        .ignoreUndefinedAttributes(false)
        .attribute("VK_VERSION_1_0")
        .attribute("VK_VERSION_1_1")
        .attributes(extensionIDs)
        .apply {
            ATTRIBS.forEach { (key, value) ->
                attribute(key, value)
            }
        }
        .asMap()

    val extensions = asciidoctor.loadFile(
        appendices.resolve("extensions.txt").toFile(),
        OptionsBuilder.options()
            .backend("plain")
            .docType("manpage")
            .safe(SafeMode.UNSAFE)
            .baseDir(appendices.toFile())
            .attributes(attribs)
            .asMap()
    )

    buildExtensionDocumentation(extensions.blocks[1].blocks, extensionIDs, structs)
    buildExtensionDocumentation(extensions.blocks[2].blocks, extensionIDs, structs)

    // Enums, functions & structs

    val document = root.resolve("man").let { man ->
        asciidoctor.load(
            String(Files.readAllBytes(man.resolve("apispec.txt")), StandardCharsets.UTF_8),
            OptionsBuilder.options()
                .backend("plain")
                .docType("manpage")
                .safe(SafeMode.UNSAFE)
                .baseDir(man.toFile())
                .attributes(attribs)
                .asMap()
        )
    }

    document.blocks.asSequence().forEach {
        when (it.id) {
            "protos",
            "funcpointers" -> {
                for (node in it.blocks)
                    addFunction(node, structs)
            }
            "structs"      -> {
                for (node in it.blocks)
                    addStruct(node, structs)
            }
            "enums",
            "flags"        -> {
                for (node in it.blocks)
                    addEnum(node, structs)
            }
        }
    }

    asciidoctor.shutdown()
}

private fun buildExtensionDocumentation(
    blocks: List<StructuralNode>,
    extensionIDs: Map<String, String>,
    structs: Map<String, TypeStruct>
) {
    var i = 0
    while (i < blocks.size) {
        // Find an extension
        i = findFirstExtensionBlock(blocks, i, extensionIDs)
        if (i == blocks.size) {
            break
        }

        val firstBlock = blocks[i++]
        val from = i

        // Up to start of next extension
        i = findFirstExtensionBlock(blocks, i, extensionIDs)

        // Concat blocks
        EXTENSION_DOC[firstBlock.id.substring(3)] =
            // move metadata last
            (firstBlock.blocks.asSequence().drop(1) +
                blocks.listIterator(from).asSequence().take(i - from) +
                firstBlock.blocks.asSequence().take(1)
                )
                .filter { it !is Section || !(it.title.startsWith("New") || it.title == "Issues" || it.title.startsWith("Version")) }
                .map { nodeToJavaDoc(it, structs) }
                .joinToString("\n\n$t$t")
    }
}
private fun findFirstExtensionBlock(blocks: List<StructuralNode>, startIndex: Int, extensionIDs: Map<String, String>): Int {
    var i = startIndex
    while (i < blocks.size) {
        val block = blocks[i]
        if (block.nodeName == "section" && extensionIDs.containsKey(block.id)) {
            break
        }
        i++
    }
    return i
}

private fun addFunction(node: StructuralNode, structs: Map<String, TypeStruct>) {
    val function = node.title
        .let { if (it.startsWith("PFN_vk")) it else it.substring(2) }
        .substringBefore('(')
    //System.err.println(function)
    try {
        FUNCTION_DOC[function] = FunctionDoc(
            getShortDescription(node.blocks[0], structs),
            containerToJavaDoc(node.blocks[1], structs),
            containerToJavaDoc(node.blocks[3], structs),
            seeAlsoToJavaDoc(node.blocks[4], structs),
            nodeToParamJavaDoc(node.blocks[2], structs)
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $function")
        printStructure(node)
        throw RuntimeException(e)
    }
}


private fun addStruct(node: StructuralNode, structs: Map<String, TypeStruct>) {
    val struct = node.title.substringBefore('(')
    //System.err.println(struct)
    try {
        STRUCT_DOC[struct] = StructDoc(
            getShortDescription(node.blocks[0], structs),
            containerToJavaDoc(node.blocks[3], structs),
            seeAlsoToJavaDoc(node.blocks[4], structs),
            nodeToParamJavaDoc(node.blocks[2], structs)
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $struct")
        printStructure(node)
        throw RuntimeException(e)
    }
}

private fun addEnum(node: StructuralNode, structs: Map<String, TypeStruct>) {
    val enum = node.title.substringBefore('(')
    //System.err.println(enum)
    try {
        ENUM_DOC[enum] = EnumDoc(
            (node.blocks[0].blocks[0] as Block).source.replaceMarkup(structs),
            containerToJavaDoc(node.blocks[2], structs),
            containerToJavaDoc(node.blocks[3], structs)
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $enum")
        printStructure(node)
        throw RuntimeException(e)
    }
}

private val SECTION_XREFS = mapOf(
    "clears" to "the “Clear Commands” section",
    "clears-values" to "the “Clear Values” section",
    "copies" to "the “Copy Commands” section",
    "debug-report-object-types" to "the “{@code VkDebugReportObjectTypeEXT} and Vulkan Handle Relationship” table",
    "descriptorsets-combinedimagesampler" to "the “Combined Image Sampler” section",
    "descriptorsets-compatibility" to "the “Pipeline Layout Compatibility” section",
    "descriptorsets-inputattachment" to "the “Input Attachment” section",
    "descriptorsets-sampledimage" to "the “Sampled Image” section",
    "descriptorsets-sampler" to "the “Sampler” section",
    "descriptorsets-sets" to "the “Descriptor Sets” section",
    "descriptorsets-storagebuffer" to "the “Storage Buffer” section",
    "descriptorsets-storagebufferdynamic" to "the “Dynamic Storage Buffer” section",
    "descriptorsets-storageimage" to "the “Storage Image” section",
    "descriptorsets-storagetexelbuffer" to "the “Storage Texel Buffer” section",
    "descriptorsets-uniformbuffer" to "the “Uniform Buffer” section",
    "descriptorsets-uniformbufferdynamic" to "the “Dynamic Uniform Buffer” section",
    "descriptorsets-uniformtexelbuffer" to "the “Uniform Texel Buffer” section",
    "descriptorsets-updates" to "the “Descriptor Set Updates” section",
    "descriptorsets-updates-consecutive" to "the “consecutive binding updates” section",
    "devsandqueues-priority" to "the “Queue Priority” section",
    "dispatch" to "the “Dispatching Commands” chapter",
    "drawing-mesh-shading" to "the “Programmable Mesh Shading” section",
    "external-memory-handle-types-compatibility" to "External memory handle types compatibility",
    "features-formats-compatible-planes" to "the “Compatible formats of planes of multi-planar formats” section",
    "features-formats-requiring-sampler-ycbcr-conversion" to "the “Formats requiring sampler Y'C<sub>B</sub>C<sub>R</sub> conversion for #IMAGE_ASPECT_COLOR_BIT image views” table",
    "framebuffer-dsb" to "the “Dual-Source Blending” section",
    "fundamentals-fp10" to "the “Unsigned 10-Bit Floating-Point Numbers” section",
    "fundamentals-fp11" to "the “Unsigned 11-Bit Floating-Point Numbers” section",
    "fxvertex-attrib" to "the “Vertex Attributes” section",
    "fxvertex-input" to "the “Vertex Input Description” section",
    "geometry" to "the “Geometry Shading” chapter",
    "img-tessellation-topology-ul" to "Domain parameterization for tessellation primitive modes (upper-left origin)",
    "img-tessellation-topology-ll" to "Domain parameterization for tessellation primitive modes (lower-left origin)",
    "memory" to "the “Memory Allocation” chapter",
    "memory-device-hostaccess" to "the “Host Access to Device Memory Objects” section",
    "primsrast" to "the “Rasterization” chapter",
    "queries-pipestats" to "the “Pipeline Statistics Queries” section",
    "resources-association" to "the “Resource Memory Association” section",
    "resources-image-views" to "the “Image Views” section",
    "samplers-maxAnisotropy" to "samplers-maxAnisotropy",
    "samplers-mipLodBias" to "samplers-mipLodBias",
    "shaders-vertex" to "the “Vertex Shaders” section",
    "synchronization-queue-transfers" to "Queue Family Ownership Transfer",
    "tessellation" to "the “Tessellation” chapter",
    "textures-chroma-reconstruction" to "Chroma Reconstruction"
)

private val SECTION_XREFS_USED = HashSet<String>()

private fun getSectionXREF(section: String): String {
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

fun printUnusedSectionXREFs() {
    SECTION_XREFS.keys.asSequence()
        .filter { !SECTION_XREFS_USED.contains(it) }
        .forEach {
            System.err.println("lwjgl: Unused section XREF:\n$it")
        }
}

internal class PlainConverter(backend: String, opts: Map<String, Any>) : StringConverter(backend, opts) {
    override fun convert(node: ContentNode, transform: String?, opts: MutableMap<Any, Any>): String {
        if (node is PhraseNode) {
            return when (node.type) {
                "monospaced"  -> "`${node.text}`"
                "unquoted"    -> {
                    if (node.hasRole("eq"))
                        "[eq]#${node.text}#"
                    else
                        throw IllegalStateException(node.roles.joinToString(", "))
                }
                "xref"        -> node.getAttribute("refid").let { "<<$it,${if (node.text != null) node.text else getSectionXREF(it as String)}>>" }
                "ref"         -> ""
                "emphasis"    -> "_${node.text}_"
                "strong"      -> "*${node.text}*"
                "latexmath"   -> getLatexCode(node.text)
                "line"        -> node.text
                "link"        -> "${node.target}[${node.text}]"
                "subscript"   -> "~${node.text}~"
                "superscript" -> "^${node.text}^"
                "double"      -> "“${node.text}”"
                "single"      -> "`${node.text}'"
                "icon"        -> ""
                else          -> {
                    System.err.println("lwjgl: type: ${node.type}")
                    System.err.println("lwjgl: text: ${node.text}")
                    System.err.println("lwjgl: target: ${node.target}")
                    System.err.println("lwjgl: reftext: ${node.reftext}")
                    System.err.println("lwjgl: id: ${node.id}")
                    System.err.println("lwjgl: attributes: ${node.attributes}")
                    throw IllegalStateException()
                }
            }
        }
        throw IllegalStateException("${node.nodeName} ${node.javaClass}")
    }
}

private val CODE_BLOCK_TRIM_PATTERN = """^\s*\n|\n\s*$""".toRegex() // first and/or last empty lines...
private val CODE_BLOCK_COMMENT_PATTERN = """/\*\s*(.+)\s*\*/""".toRegex() // first and/or last empty lines...
private val CODE_BLOCK_HASH = "#".toRegex()
private val CODE_BLOCK_ESCAPE_PATTERN = "^".toRegex(RegexOption.MULTILINE) // line starts
private val CODE_BLOCK_TAB_PATTERN = "\t".toRegex() // tabs

private val HTML_ESCAPE_PATTERN = """[<>]|&(?!(?:amp|gt|lt);)""".toRegex()

private val String.htmlEscaped: String
    get() = this.replace(HTML_ESCAPE_PATTERN) {
        when (it.value) {
            "<"  -> "&lt;"
            ">"  -> "&gt;"
            "&"  -> "&amp;"
            else -> throw IllegalStateException()
        }
    }

fun codeBlock(code: String, escape: Boolean = false) = """<pre><code>
${(if (escape) code.htmlEscaped else code)
    .replace(CODE_BLOCK_TRIM_PATTERN, "") // ...trim
    .replace(CODE_BLOCK_COMMENT_PATTERN, "// $1") // ...replace block comments with line comments
    .replace(CODE_BLOCK_HASH, """\\#""") // ...escape hashes
    .replace(CODE_BLOCK_ESCAPE_PATTERN, "\uFFFF") // ...escape
    .replace(CODE_BLOCK_TAB_PATTERN, "    ") // ...replace with 4 spaces for consistent formatting.
}</code></pre>"""

private val LATEX_MATH = """latexmath:\[(.+?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val LATEX_REGISTRY = mapOf(
    """0 \leq L \leq 1""" to "0 &le; L &le; 1",
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
    max(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &lt; 0""")

    /*"""\begin{aligned}
E & =
  \begin{cases}
    1.055 \times L^{1 \over 2.4} - 0.055 & \text{for}\  0.0031308 \leq L \leq 1 \\
    12.92 \times L                       & \text{for}\  0 \leq L < 0.0031308
  \end{cases}
\end{aligned}""" to
        codeBlock("""
E =  1.055 &times; L<sup>1/2.4</sup> - 0.055 for 0.0031308 &le; L &le; 1
    12.92  &times; L for 0 &le; L &lt 0.0031308"""),

    """\begin{aligned}
E & =
  \begin{cases}
    1.055 \times L^{1 \over 2.4} - 0.055 & \text{for}\  0.0030186 \leq L \leq 1 \\
    12.92 \times L                       & \text{for}\  0 \leq L < 0.0030186
  \end{cases}
\end{aligned}""" to
        codeBlock("""
E =  1.055 &times; L<sup>1/2.4</sup> - 0.055 for 0.0030186 &le; L &le; 1
    12.92  &times; L for 0 &le; L &lt 0.0030186"""),

    """\begin{aligned}
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
    a &times; ln(L - b) + c for 1 &lt L""")*/,
    """\lfloor i_G \times 0.5 \rfloor = i_B = i_R""" to "<code>floor(i<sub>G</sub> &times; 0.5) = i<sub>B</sub> = i<sub>R</sub></code>",
    """\lfloor j_G \times 0.5 \rfloor = j_B = j_R""" to "<code>floor(j<sub>G</sub> &times; 0.5) = j<sub>B</sub> = j<sub>R</sub></code>",
    "\\lceil{\\frac{width}{maxFragmentDensityTexelSize_{width}}}\\rceil" to "{@code ceil(width / maxFragmentDensityTexelSize.width)}",
    "\\lceil{\\frac{height}{maxFragmentDensityTexelSize_{height}}}\\rceil" to "{@code ceil(height / maxFragmentDensityTexelSize.height)}",
    "\\lceil{\\frac{maxFramebufferWidth}{minFragmentDensityTexelSize_{width}}}\\rceil" to "{@code ceil(maxFramebufferWidth / minFragmentDensityTexelSize.width)}",
    "\\lceil{\\frac{maxFramebufferHeight}{minFragmentDensityTexelSize_{height}}}\\rceil" to "{@code ceil(maxFramebufferHeight / minFragmentDensityTexelSize.height)}",
    "\\pm\\infty" to "&plusmn;&infin;"
)

private val LATEX_REGISTRY_USED = HashSet<String>()

private fun getLatexCode(source: String): String {
    //val code = LATEX_REGISTRY[source] ?: throw IllegalStateException("Missing LaTeX equation:\n$source")
    val code = LATEX_REGISTRY[source] ?: LATEX_REGISTRY[source.replace("\\s+".toRegex(), " ")]
    if (code == null) {
        System.err.println(source)
        return source
    }
    LATEX_REGISTRY_USED.add(source)
    return code
}

fun printUnusedLatexEquations() {
    LATEX_REGISTRY.keys.asSequence()
        .filter { !LATEX_REGISTRY_USED.contains(it) }
        .forEach {
            System.err.println("lwjgl: Unused LateX equation:\n$it")
        }
}

private val LINE_BREAK = """\n\s*""".toRegex()

private val SIMPLE_NUMBER = """(?<=^|\s)`(\d+)`|code:(\d+)(?=\s|$)""".toRegex()
private val KEYWORD = """(?<=^|\s)(must|should|may|can|cannot):(?=\s|$)""".toRegex()
private val UNDEFINED = """(?<=^|\s)undefined:""".toRegex()
private val STRONG = """(?<=^|\W)\*+([^*<]+)\*+(?=[\W]|$)""".toRegex()
private val EMPHASIS = """(?<=^|\W)_([^_]+)_(?=[\W]|$)""".toRegex()
private val SUPERSCRIPT = """\^([^^]+)\^""".toRegex()
private val SUBSCRIPT = """~([^~]+)~""".toRegex()
private val EQUATION = """\[eq]#((?:[^#]|(?<=&)#(?=x?[0-9a-fA-F]{1,4};))+)#""".toRegex()
private val EQUATION_ATTRIB = """\{(\w+)}""".toRegex()
private val STRUCT_OR_HANDLE = """s(?:name|link):(\w+)""".toRegex()
private val STRUCT_FIELD = """::pname:(\w+)""".toRegex()
private val CODE1 = """`([^`]+?)`""".toRegex()
private val FUNCTION = """(?:flink):vk(\w+)""".toRegex()
private val FUNCTION_TYPE = """(?:tlink):PFN_vk(\w+)""".toRegex()
private val ENUM = """(?:ename|dlink|code):VK_(\w+)""".toRegex()
private val CODE2 = """(?:fname|pname|ptext|basetype|ename|elink|tlink|code):(\w+(?:[.]\w+)*)""".toRegex()
private val CODE3 = """(?:etext|ftext):([\w*]+)""".toRegex()
private val LINK = """(https?://.+?)\[([^]]+)]""".toRegex()
private val SPEC_LINK = """<<([^,]+?)(?:,([^>]+))?>>""".toRegex()
private val SPEC_LINK_RELATIVE = """(?:link:)?\{html_spec_relative}#([^\[]+?)\[([^]]*)]""".toRegex()
private val EXTENSION = """[+](\w+)[+]""".toRegex()
private val FIXUP = """\\->""".toRegex()

private fun String.replaceMarkup(structs: Map<String, TypeStruct>): String = this.trim()
    .replace(LINE_BREAK, " ")
    .replace(LATEX_MATH) {
        // These will likely be replaced to reduce HTML load times.
        // Instead of trying to be clever and parse, we're lazy and
        // do a lookup  to prebaked HTML. There are not many LaTeX
        // equations anyway.
        getLatexCode(it.groups[1]!!.value)
    }
    .replace(SIMPLE_NUMBER, "$1$2")
    .replace(EQUATION) { "<code>${it.groups[1]!!.value
        .replace(CODE2, "$1")
        .replace(EQUATION_ATTRIB) { result ->
            val attrib = result.groups[1]!!.value
            if (ATTRIBS.containsKey(attrib)) {
                ATTRIBS[attrib]!!
            } else
                it.value
        } // TODO: more?
        .htmlEscaped
    }</code>" }
    .replace(KEYWORD, "<b>$1</b>")
    .replace(UNDEFINED, "undefined") // TODO: highlight or anything else?
    .replace(STRONG, "<b>$1</b>")
    .replace(EMPHASIS, "<em>$1</em>")
    .replace(SUPERSCRIPT, "<sup>$1</sup>")
    .replace(SUBSCRIPT, "<sub>$1</sub>")
    .replace(STRUCT_OR_HANDLE) {
        val type = it.groups[1]!!.value
        if (structs.containsKey(type))
            "##$type" // struct
        else
            "{@code $type}" // handle
    }
    .replace(STRUCT_FIELD, "{@code ::$1}")
    .replace(CODE1) { result ->
        result.groups[1]!!.value.let {
            if (it.startsWith("etext:")) {
                it
            } else {
                val m = SPEC_LINK.find(it) ?: SPEC_LINK_RELATIVE.find(it)
                if (m != null) {
                    val extension = m.groups[1]!!.value
                    if (!extension.startsWith("VK_")) {
                        throw IllegalStateException()
                    }
                    "{@link ${extension.substring(3).template} $extension}"
                } else {
                    "{@code $it}"
                }
            }
        }
    }
    .replace(FUNCTION, "#$1()")
    .replace(FUNCTION_TYPE) {
        val type = it.groups[1]!!.value
        if (type == "VoidFunction")
            "{@code PFN_vkVoidFunction}"
        else
            "##Vk$type"
    }
    .replace(ENUM) { result ->
        val name = result.groups[1]!!.value
        if (name.any { it in 'a'..'z' } && EXTENSION_TEMPLATES.containsKey(name)) {
            "##${EXTENSION_TEMPLATES[name]}" // link to extension class
        } else {
            "#$name"
        }
    }
    .replace(CODE2, "{@code $1}")
    .replace(CODE3, "{@code $1}")
    .replace(LINK, """<a target="_blank" href="$1">$2</a>""")
    .replace(SPEC_LINK) {
        val section = it.groups[1]!!
        """<a target="_blank" href="https://www.khronos.org/registry/vulkan/specs/1.0-extensions/html/vkspec.html\#${section.value}">${it.groups[2]?.value ?: section.value}</a>"""
    }
    .replace(SPEC_LINK_RELATIVE) { result ->
        val (section, text) = result.destructured
        """<a target="_blank" href="https://www.khronos.org/registry/vulkan/specs/1.0-extensions/html/vkspec.html\#$section">${text.let {
            if (it.isEmpty() || it.startsWith("{html_spec_relative}#")) {
                getSectionXREF(section)
            } else
                it
        }}</a>"""
    }
    .replace(EXTENSION, "{@code $1}")
    .replace(FIXUP, "-&gt;")

private fun getShortDescription(name: StructuralNode, structs: Map<String, TypeStruct>) =
    (name.blocks[0] as Block).lines[0]
        .let { it.substring(it.indexOf('-') + 2).replaceMarkup(structs) }
        .let { if (it.endsWith('.')) it else "$it." }

private fun containerToJavaDoc(node: StructuralNode, structs: Map<String, TypeStruct>, indent: String = ""): String =
    node.blocks.asSequence()
        .map { nodeToJavaDoc(it, structs, indent) }
        .joinToString("\n\n$t$t$indent").let {
        if (node.title == null || node.title.isEmpty() || it.isEmpty() || it.startsWith("<h5>"))
            it
        else
            "<h5>${node.title.replaceMarkup(structs)}</h5>\n$t$t$it".let {
                if (node.style == "NOTE") {
                    """<div style="margin-left: 26px; border-left: 1px solid gray; padding-left: 14px;">$it
        $indent</div>"""
                } else
                    it
            }
    }

private fun nodeToJavaDoc(it: StructuralNode, structs: Map<String, TypeStruct>, indent: String = ""): String =
    if (it is Section) {
        containerToJavaDoc(it, structs, indent)
    } else if (it is Block) {
        if (it.lines.isEmpty())
            containerToJavaDoc(it, structs, indent)
        else {
            if (it.blocks.isNotEmpty())
                throw IllegalStateException()
            when {
                it.style == "source"    -> codeBlock(it.source, escape = true)
                it.style == "latexmath" -> getLatexCode(it.source)
                else                    -> it.lines.joinToString(" ").replaceMarkup(structs)
            }
        }
    } else if (it is org.asciidoctor.ast.List) {
        """<ul>
            $indent${it.items.asSequence()
            .map { it as ListItem }
            .map {
                if (it.blocks.isNotEmpty())
                    """<li>
                $indent${it.text.replaceMarkup(structs)}
                $indent${containerToJavaDoc(it, structs, "$t$t$indent")}
            $indent</li>"""
                else
                    "<li>${it.text.replaceMarkup(structs)}</li>"
            }
            .joinToString("\n$t$t$t$indent")}
        $indent</ul>"""
    } else if (it is Table) {
        """${if (it.title == null) "" else "<h6>${it.title.replaceMarkup(structs)}</h6>\n$t$t"}<table class="lwjgl">
            ${sequenceOf(
            it.header to ("thead" to "th"),
            it.footer to ("tfoot" to "td"),
            it.body to ("tbody" to "td")
        )
            .filter { it.first.isNotEmpty() }
            .map { (groups, cells) ->
                val (group, cell) = cells
                "<$group>${groups.asSequence()
                    .map {
                        "<tr>${it.cells.asSequence()
                            .map {
                                "<$cell>${if (it.style == "asciidoc")
                                    nodeToJavaDoc(it.innerDocument, structs, indent) // TODO: untested
                                else
                                    it.text.replaceMarkup(structs)
                                }</$cell>"
                            }
                            .joinToString("")
                        }</tr>"
                    }
                    .joinToString(
                        "\n$t$t$t$t$indent",
                        prefix = if (groups.size == 1) "" else "\n$t$t$t$t$indent",
                        postfix = if (groups.size == 1) "" else "\n$t$t$t$indent")
                }</$group>"
            }
            .joinToString("\n$t$t$t$indent")}
        $indent</table>"""
    } else if (it is DescriptionList) {
        """<dl>
            ${it.items.asSequence()
            .map {
                if (it.terms.size != 1)
                    throw IllegalStateException("${it.terms}")

                """${it.terms[0].text.let { if (it.isNotEmpty()) "<dt>${it.replaceMarkup(structs)}</dt>\n$t$t$t$indent" else "" }}<dd>${if (it.description.blocks.isEmpty())
                    it.description.text.replaceMarkup(structs)
                else
                    containerToJavaDoc(it.description, structs, "$t$indent")
                }</dd>"""
            }
            .joinToString("\n\n$t$t$t$indent")}
        $indent</dl>"""
    } else if (it is Document) {
        containerToJavaDoc(it, structs, indent)
    } else {
        throw IllegalStateException("${it.nodeName} - ${it.javaClass}")
    }

// TODO: add enum links
private val SEE_ALSO_LINKS_REGEX = """([fst])link:(\w+)""".toRegex()

private fun seeAlsoToJavaDoc(node: StructuralNode, structs: Map<String, TypeStruct>): String? {
    // Keep function, struct, callback links only
    val links = SEE_ALSO_LINKS_REGEX
        .findAll((node.blocks[0] as Block).source)
        .mapNotNull {
            val (type, link) = it.destructured
            if (type != "s" || structs.containsKey(link))
                it.value
            else
                null
        }
        .joinToString()

    return if (links.isEmpty())
        null
    else
        "<h5>${node.title.replaceMarkup(structs)}</h5>\n$t$t${links.replaceMarkup(structs)}"
}

private val MULTI_PARAM_DOC_REGEX = Regex("""^\s*pname:(\w+)(?:[,:]?(?:\s+and)?\s+pname:(?:\w+))+\s+""")
private val PARAM_REGEX = Regex("""pname:(\w+)""")
private val PARAM_DOC_REGEX = Regex("""^\s*(When\s+)?(?:(?:sname|slink):(\w+)::)?pname:(\w+)(?:\[\d+])?(\.\w+)?[,:]?\s+(?:is\s+)?(.+)""", RegexOption.DOT_MATCHES_ALL)

private val ESCAPE_REGEX = Regex(""""|\\#""")

private fun nodeToParamJavaDoc(members: StructuralNode, structs: Map<String, TypeStruct>): Map<String, String> {
    members.blocks.forEach {
        if (it !is org.asciidoctor.ast.List) {
            return@forEach
        }

        return it.items.asSequence()
            .filterIsInstance<ListItem>()
            .filter { it.text != null }
            .flatMap {
                val multi = MULTI_PARAM_DOC_REGEX.find(it.text)
                if (multi != null) {
                    val first = multi.groups[1]!!.value
                    PARAM_REGEX.findAll(multi.value)
                        .map { it.groups[1]!!.value }
                        .mapIndexed { i, member ->
                            member to if (i == 0)
                                getItemDescription(it, it.text.replaceMarkup(structs), structs)
                            else
                                "see {@code $first}"
                        }
                } else {
                    try {
                        val (When, struct, param, field, description) = PARAM_DOC_REGEX.matchEntire(it.text)!!.destructured
                        if (struct.isNotEmpty()) {
                            System.err.println("lwjgl: struct member cross reference: $struct::$param")
                        }
                        sequenceOf(param to getItemDescription(it, if (When.isEmpty() && field.isEmpty()) description else it.text, structs))
                    } catch (e: Exception) {
                        println("FAILED AT: ${it.text}")
                        printStructure(it)
                        throw RuntimeException(e)
                    }
                }
            }
            .groupBy { it.first }
            .map {
                it.key to if (it.value.size == 1)
                    it.value[0].second.let {
                        if (!it.startsWith("\"\""))
                            it.replace(ESCAPE_REGEX, """\\$0""")
                        else
                            it
                    }
                else
                    it.value.asSequence()
                        .map {
                            if (it.second.startsWith("\"\""))
                                it.second.substring(2, it.second.length - 2)
                            else
                                it.second
                        }
                        .joinToString("\n\n$t$t", prefix = "\"\"", postfix = "\"\"")
            }
            .toMap()
    }
    return emptyMap()
}

private fun getItemDescription(listItem: ListItem, description: String, structs: Map<String, TypeStruct>) =
    if (listItem.blocks.isNotEmpty())
        "\"\"${description.replaceMarkup(structs)}\n\n$t$t${containerToJavaDoc(listItem, structs)}\"\""
    else
        description.replaceMarkup(structs)

private fun printStructure(node: StructuralNode, indent: String = "") {
    System.err.println("$indent${node.level}. ${node.nodeName} ${node.title} ${node.attributes} ${node.javaClass}")

    if (node is org.asciidoctor.ast.List) {
        node.items.forEach {
            printStructure(it, "$t$indent")
        }
    } else if (node is DescriptionList) {
        node.items.forEach {
            printStructure(it.description, "$t$indent")
            it.terms.forEach {
                printStructure(it, "$t$indent")
            }
        }
    }

    if (node.blocks != null) {
        node.blocks.asSequence().forEach {
            printStructure(it, "$t$indent")
        }
    }
}
