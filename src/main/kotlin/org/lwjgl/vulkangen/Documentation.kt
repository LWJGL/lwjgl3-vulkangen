/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import org.asciidoctor.*
import org.asciidoctor.ast.*
import java.nio.file.*
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

internal fun convert(root: Path, structs: Map<String, TypeStruct>) {
    val (asciidoctor, attribsBuilder) = createAsciidoctor(root, structs)

    // Extension class documentation

    // We parse extensions.adoc to create a map of attributes to pass to asciidoctor.
    // The attributes are used in ifdef preprocessor directives in extensions.adoc
    // to enable extensions.
    val extensionIDs = """^include::\{appendices}/(VK_\w+)\.adoc\[]""".toRegex().let { regex ->
        (
            Files.lines(root.resolve("gen/meta/current_extension_appendices.adoc")).asSequence() +
            Files.lines(root.resolve("gen/meta/deprecated_extension_appendices.adoc")).asSequence() +
            Files.lines(root.resolve("gen/meta/provisional_extension_appendices.adoc")).asSequence()
        )
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
        .associateWithTo(EXTENSION_TEMPLATES) { it.template }

    val attribs = attribsBuilder
        .attribute("appendices", root.resolve("appendices").toString())
        .attribute("chapters", root.resolve("chapters").toString())
        .attribute("config", root.resolve("config").toString())
        .attribute("generated", root.resolve("gen").toString())
        .attribute("images", root.resolve("images").toString())
        .attribute("spirv", "https://htmlpreview.github.io/?https://github.com/KhronosGroup/SPIRV-Registry/blob/master/extensions")
        .attribute("anchor-prefix")
        .attribute("refprefix")
        .attribute("VK_VERSION_1_0")
        .attribute("VK_VERSION_1_1")
        .attribute("VK_VERSION_1_2")
        .attribute("VK_VERSION_1_3")
        .attributes(extensionIDs)
        // These two are necessary because something goes wrong when a Preprocessor is used
        .attribute("HAS_PROVISIONAL_EXTENSIONS")
        .attribute("HAS_DEPRECATED_EXTENSIONS")
        .build()

    val appendices = root.resolve("appendices")
    val extensions = asciidoctor.loadFile(
        appendices.resolve("extensions.adoc").toFile(),
        Options.builder()
            .backend("lwjgl")
            .docType("manpage")
            .safe(SafeMode.UNSAFE)
            .baseDir(appendices.toFile())
            .attributes(attribs)
            .option("structs", structs)
            .build()
    )

    for (i in 2..4) {
        buildExtensionDocumentation(extensions.blocks[i].blocks, extensionIDs)
    }

    // Enums, functions & structs

    val document = root.resolve("gen").resolve("refpage").let { man ->
        asciidoctor.loadFile(
            man.resolve("apispec.adoc").toFile(),
            Options.builder()
                .backend("lwjgl")
                .docType("manpage")
                .safe(SafeMode.UNSAFE)
                .baseDir(man.toFile())
                .attributes(attribs)
                .option("structs", structs)
                .build()
        )
    }

    document.blocks.asSequence().forEach {
        when (it.id) {
            "protos",
            "funcpointers" -> {
                for (node in it.blocks)
                    addFunction(node)
            }
            "structs"      -> {
                for (node in it.blocks)
                    addStruct(node)
            }
            "enums",
            "flags"        -> {
                for (node in it.blocks)
                    addEnum(node)
            }
        }
    }

    asciidoctor.shutdown()
}

private fun buildExtensionDocumentation(
    blocks: List<StructuralNode>,
    extensionIDs: Map<String, String>
) {
    var i = findFirstExtensionBlock(blocks, 0, extensionIDs)
    while (i < blocks.size) {
        val firstBlock = blocks[i++]
        val from = i

        // Up to start of next extension
        i = findFirstExtensionBlock(blocks, i, extensionIDs)

        // Concat blocks
        EXTENSION_DOC[firstBlock.id.substring(3)] =
            // Re-order sections for readability: description first, metadata last
            (
                // appendices/<extension>.txt: skip "Other Extension Metadata" and drop "Description" header
                blocks[from + 1].blocks.asSequence() +
                // appendices/<extension>.txt: all sections after "Description"
                blocks.listIterator(from + 2).asSequence().take(i - (from + 2)) +
                // meta/<extension>.txt
                firstBlock +
                // appendices/<extension>.txt: "Other Extension Metadata"
                blocks[from]
            )
                .filter { it !is Section || !(it.title.startsWith("New") || it.title == "Issues" || it.title.startsWith("Version")) }
                .map { nodeToJavaDoc(it) }
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

private fun addFunction(node: StructuralNode) {
    val function = node.title
        .let { if (it.startsWith("PFN_vk")) it else it.substring(2) }
        .substringBefore('(')
    //System.err.println(function)
    try {
        FUNCTION_DOC[function] = FunctionDoc(
            getShortDescription(node.blocks[0]),
            containerToJavaDoc(node.blocks[1]),
            containerToJavaDoc(node.blocks[3]),
            seeAlsoToJavaDoc(node.blocks[4]),
            nodeToParamJavaDoc(node.blocks[2])
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $function")
        printStructure(node)
        throw RuntimeException(e)
    }
}


private fun addStruct(node: StructuralNode) {
    val struct = node.title.substringBefore('(')
    //System.err.println(struct)
    try {
        STRUCT_DOC[struct] = StructDoc(
            getShortDescription(node.blocks[0]),
            containerToJavaDoc(node.blocks[3]),
            seeAlsoToJavaDoc(node.blocks[4]),
            nodeToParamJavaDoc(node.blocks[2])
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $struct")
        printStructure(node)
        throw RuntimeException(e)
    }
}

private fun addEnum(node: StructuralNode) {
    val enum = node.title.substringBefore('(')
    //System.err.println(enum)
    try {
        ENUM_DOC[enum] = EnumDoc(
            (node.blocks[0].blocks[0] as Block).content.toString().patch(),
            node.blocks.firstOrNull { it.title == "Description" }?.let { containerToJavaDoc(it) } ?: "",
            node.blocks.firstOrNull { it.title == "See Also" }?.let { seeAlsoToJavaDoc(it) } ?: ""
        )
    } catch (e: Exception) {
        System.err.println("Failed while parsing: $enum")
        printStructure(node)
        throw RuntimeException(e)
    }
}

private val CODE_BLOCK_TRIM_PATTERN = """^\s*(?:// Provided by [^\n]+)?\n|\n\s*$""".toRegex() // first and/or last empty lines...
private val CODE_BLOCK_COMMENT_PATTERN = """/\*\s*(.+)\s*\*/""".toRegex() // first and/or last empty lines...
private val CODE_BLOCK_HASH = "#".toRegex()
private val CODE_BLOCK_ESCAPE_PATTERN = "^".toRegex(RegexOption.MULTILINE) // line starts
private val CODE_BLOCK_TAB_PATTERN = "\t".toRegex() // tabs

fun codeBlock(code: String) = """<pre><code>
${code
    .replace(CODE_BLOCK_TRIM_PATTERN, "") // ...trim
    .replace(CODE_BLOCK_COMMENT_PATTERN, "// $1") // ...replace block comments with line comments
    .replace(CODE_BLOCK_HASH, """\\#""") // ...escape hashes
    .replace(CODE_BLOCK_ESCAPE_PATTERN, "\uFFFF") // ...escape
    .replace(CODE_BLOCK_TAB_PATTERN, "    ") // ...replace with 4 spaces for consistent formatting.
}</code></pre>"""

private val LINE_BREAK = """\n\s*""".toRegex()
private val LATEX_MATH = """latexmath:\[(.+?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val STRUCT_FIELD = """::\{@code """.toRegex()
private val FIX_INVALID_VUIDs = """\[\[VUID-\{refpage}.+?]]\s*""".toRegex()
private val UNESCAPE_UNICODE = """&#(\d+);""".toRegex()
private val PLUS = """\+ {2}""".toRegex()

private fun String.patch(): String = this.trim()
    .replace(LINE_BREAK, " ")
    .replace(LATEX_MATH) {
        // These will likely be replaced to reduce HTML load times.
        // Instead of trying to be clever and parse, we're lazy and
        // do a lookup  to prebaked HTML. There are not many LaTeX
        // equations anyway.
        getLatexCode(it.groups[1]!!.value)
    }
    .replace(STRUCT_FIELD, "{@code ::")
    .replace(FIX_INVALID_VUIDs, "")
    .replace(UNESCAPE_UNICODE) { String(Character.toChars(it.groups[1]!!.value.toInt())) }
    .replace(PLUS, "+ ")

private fun getShortDescription(name: StructuralNode) =
    name.blocks[0].content.toString()
        .let { it.substring(it.indexOf('-') + 2).patch() }
        .let { if (it.endsWith('.')) it else "$it." }

private fun containerToJavaDoc(node: StructuralNode, indent: String = ""): String =
    node.blocks.asSequence()
        .map { nodeToJavaDoc(it, indent) }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n$t$t$indent").let { block ->
        if (node.title == null || node.title.isEmpty() || block.isEmpty() || block.startsWith("<h5>"))
            block
        else
            "<h5>${node.title.patch()}</h5>\n$t$t${block}".let {
                if (node.style == "NOTE") {
                    """<div style="margin-left: 26px; border-left: 1px solid gray; padding-left: 14px;">$it
        $indent</div>"""
                } else
                    it
            }
    }

private fun nodeToJavaDoc(it: StructuralNode, indent: String = ""): String =
    if (it is Section) {
        containerToJavaDoc(it, indent)
    } else if (it is Block) {
        if (it.lines.isEmpty())
            containerToJavaDoc(it, indent)
        else {
            check(it.blocks.isEmpty())
            when (it.style) {
                "source"    -> codeBlock(it.content.toString())
                "latexmath" -> getLatexCode(it.source)
                else        -> it.content.toString().patch()
            }
        }
    } else if (it is org.asciidoctor.ast.List) {
        """<ul>
            $indent${it.items.asSequence()
            .map { it as ListItem }
            .map {
                if (it.blocks.isNotEmpty())
                    """<li>
                $indent${it.text.patch()}
                $indent${containerToJavaDoc(it, "$t$t$indent")}
            $indent</li>"""
                else
                    "<li>${it.text.patch()}</li>"
            }
            .joinToString("\n$t$t$t$indent")}
        $indent</ul>"""
    } else if (it is Table) {
        """${if (it.title == null) "" else "<h6>${it.title.patch()}</h6>\n$t$t"}<table class="lwjgl">
            ${sequenceOf(
            it.header to ("thead" to "th"),
            it.footer to ("tfoot" to "td"),
            it.body to ("tbody" to "td")
        )
            .filter { it.first.isNotEmpty() }
            .map { (groups, cells) ->
                val (group, cell) = cells
                "<$group>${groups.asSequence()
                    .map { row ->
                        "<tr>${row.cells.asSequence()
                            .map {
                                "<$cell>${if (it.style == "asciidoc")
                                    nodeToJavaDoc(it.innerDocument, indent) // TODO: untested
                                else
                                    it.text.patch()
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
            .map { entry ->
                if (entry.terms.size != 1) {
                    printStructure(it)
                    for (term in entry.terms) {
                        println(term.text)
                    }
                    throw IllegalStateException("${entry.terms}")
                }

                """${entry.terms[0].text.let { if (it.isNotEmpty()) "<dt>${it.patch()}</dt>\n$t$t$t$indent" else "" }}<dd>${if (entry.description.blocks.isEmpty())
                    entry.description.text.patch()
                else
                    containerToJavaDoc(entry.description, "$t$indent")
                }</dd>"""
            }
            .joinToString("\n\n$t$t$t$indent")}
        $indent</dl>"""
    } else if (it is Document) {
        containerToJavaDoc(it, indent)
    } else {
        throw IllegalStateException("${it.nodeName} - ${it.javaClass}")
    }

private val SEE_ALSO_LINKS_REGEX = """##?\w+(?:\(\))?""".toRegex()
private fun seeAlsoToJavaDoc(node: StructuralNode): String? {
    // Keep function, struct, callback links only
    val links = SEE_ALSO_LINKS_REGEX
        .findAll(node.blocks[0].content.toString())
        .map { it.value }
        .joinToString()

    return if (links.isEmpty())
        null
    else
        "<h5>${node.title.patch()}</h5>\n$t$t${links.patch()}"
}

private val PARAM_DOC_NODE_REGEX = Regex("""^\s*\{@code (\w+)}""")
private val MULTI_PARAM_DOC_REGEX = Regex("""^\s*\{@code (\w+)}(?:[,:]?(?:\s+and)?\s+\{@code \w+})+\s+""")
private val PARAM_REGEX = Regex("""\{@code (\w+)}""")
private val PARAM_DOC_REGEX = Regex("""^\s*(When\s+)?(?:##(\w+)::)?\{@code (\w+)}(?:\[\d+])?(\.\w+)?[,:]?\s+(?:is\s+)?(.+)""", RegexOption.DOT_MATCHES_ALL)

private val ESCAPE_REGEX = Regex(""""|\\#""")

private fun findParameterList(node: StructuralNode): Sequence<org.asciidoctor.ast.List> =
    if (node is org.asciidoctor.ast.List)
        sequenceOf(node)
    else if (node.blocks != null) {
        node.blocks.asSequence()
            .mapNotNull { findParameterList(it) }
            .flatMap { it }
    } else {
        emptySequence()
    }

private fun nodeToParamJavaDoc(members: StructuralNode) = findParameterList(members)
    .flatMap { list ->
        list.items.asSequence()
            .filterIsInstance<ListItem>()
            .filter { it.text != null && PARAM_DOC_NODE_REGEX.containsMatchIn(it.text) }
            .flatMap { item ->
                val multi = MULTI_PARAM_DOC_REGEX.find(item.text)
                if (multi != null) {
                    val first = multi.groups[1]!!.value
                    PARAM_REGEX.findAll(multi.value)
                        .map { it.groups[1]!!.value }
                        .mapIndexed { i, member ->
                            member to if (i == 0)
                                getItemDescription(item, item.text.patch())
                            else
                                "see {@code $first}"
                        }
                } else {
                    try {
                        val doc = item.text.patch()
                        val (when_, struct, param, field, description) = PARAM_DOC_REGEX.matchEntire(doc)!!.destructured
                        if (struct.isNotEmpty()) {
                            System.err.println("lwjgl: struct member cross reference: $struct::$param")
                        }
                        sequenceOf(param to getItemDescription(item, if (when_.isEmpty() && field.isEmpty()) description else doc))
                    } catch (e: Exception) {
                        println("FAILED AT: ${item.text}")
                        printStructure(item)
                        throw RuntimeException(e)
                    }
                }
            }
            .groupBy { (param) -> param }
            .map { (param, descriptions) ->
                param to if (descriptions.size == 1)
                    descriptions[0].second.let {
                        if (!it.startsWith("\"\""))
                            it.replace(ESCAPE_REGEX, """\\$0""")
                        else
                            it
                    }
                else
                    descriptions.asSequence()
                        .map { (_, description) ->
                            if (description.startsWith("\"\""))
                                description.substring(2, description.length - 2)
                            else
                                description
                        }
                        .joinToString("\n\n$t$t", prefix = "\"\"", postfix = "\"\"")
            }
    }
    .toMap(HashMap())

private fun getItemDescription(listItem: ListItem, description: String) =
    if (listItem.blocks.isNotEmpty())
        "\"\"${description}\n\n$t$t${containerToJavaDoc(listItem)}\"\""
    else
        description

private fun printStructure(node: StructuralNode, indent: String = "") {
    System.err.println("$indent${node.level}. ${node.nodeName} ${node.title} ${node.attributes} ${node.javaClass}")

    if (node is org.asciidoctor.ast.List) {
        node.items.forEach {
            printStructure(it, "$t$indent")
        }
    } else if (node is DescriptionList) {
        node.items.forEach { entry ->
            printStructure(entry.description, "$t$indent")
            entry.terms.forEach {
                printStructure(it, "$t$indent")
            }
        }
    } else if (node.blocks != null && node.blocks.isNotEmpty()) {
        node.blocks.asSequence().forEach {
            printStructure(it, "$t$indent")
        }
    }
}
