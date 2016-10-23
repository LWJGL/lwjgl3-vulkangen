/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import org.asciidoctor.Asciidoctor
import org.asciidoctor.OptionsBuilder
import org.asciidoctor.SafeMode
import org.asciidoctor.ast.*
import org.asciidoctor.converter.StringConverter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.function.Function
import java.util.function.Function.identity
import java.util.stream.Collectors

internal val QUOTES3 = "\"\"\""
internal val S = "\$"

internal class FunctionDoc(
	val shortDescription: String,
	val cSpecification: String,
	val description: String,
	val parameters: Map<String, String>
)

internal class StructDoc(
	val shortDescription: String,
	val description: String,
	val members: Map<String, String>
)

internal val FUNCTION_DOC = HashMap<String, FunctionDoc>(256)
internal val STRUCT_DOC = HashMap<String, StructDoc>(128)
internal val EXTENSION_DOC = HashMap<String, String>(32)

internal fun convert(root: Path, structs: Map<String, TypeStruct>) {
	val asciidoctor = Asciidoctor.Factory.create()

	// Register a converter that defeats conversions performed by asciidoctor inside lists.
	// This way all texts are uniform, simplifying the final conversion.
	asciidoctor
		.javaConverterRegistry()
		.register(PlainConverter::class.java, "plain")

	// Extension class documentation

	val appendices = root.resolve("doc/specs/vulkan/appendices")
	// We parse extensions.txt to create a map of attributes to pass to asciidoctor.
	// The attributes are used in ifdef preprocessor directives in extensions.txt
	// to enable extensions.
	fun parseExtensionsIDs(path: Path, regex: Regex) = Files
		.lines(path)
		.map { regex.find(it) }
		.filter { it != null }
		.map { it!!.groups[1]!!.value }
		.collect(Collectors.toMap<String, String, Any>(identity(), Function { "" }))

	val extensionIDs = parseExtensionsIDs(
		appendices.resolve("extensions.txt"),
		"""^include::(VK_\w+)\.txt\[]""".toRegex()
	)
	extensionIDs.putAll(parseExtensionsIDs(
		appendices.resolve("VK_KHR_surface/wsi.txt"),
		"""^include::\.\./(VK_\w+)/\w+\.txt\[]""".toRegex()
	))

	val extensions = asciidoctor.loadFile(
		appendices.resolve("extensions.txt").toFile(),
		OptionsBuilder.options()
			.backend("plain")
			.docType("manpage")
			.safe(SafeMode.UNSAFE)
			.baseDir(appendices.toFile())
			.attributes(extensionIDs)
			.asMap()
	)
	fixNestedLists(extensions)

	findNodes(extensions) {
		it.nodeName == "section" && extensionIDs.containsKey(it.id)
	}.forEach {
		val buffer = StringBuilder()
		var state = 0
		for (i in it.blocks.indices) {
			if (state == 0 && it.blocks[i] is Block)
				state = 1

			if (state == 1) {
				if (it.blocks[i] is Section)
					break

				if (buffer.isNotEmpty())
					buffer.append("\n\n\t\t")
				buffer.append(nodeToJavaDoc(it.blocks[i], structs))
			}
		}

		EXTENSION_DOC[it.id.substring(3)] = buffer.toString()
	}

	// Enums, functions & structs

	val document = root.resolve("doc/specs/vulkan/man").let { man ->
		asciidoctor.load(
			Files.lines(man.resolve("apispec.txt"))
				.map {
					// Enable all extensions
					val match = "// not including (\\w+)".toRegex().matchEntire(it)
					if (match == null)
						it
					else
						"include::${match.groups[1]!!.value}.txt[]"
				}
				.collect(Collectors.joining("\n")),
			OptionsBuilder.options()
				.backend("plain")
				.docType("manpage")
				.safe(SafeMode.UNSAFE)
				.baseDir(man.toFile())
				.attributes(extensionIDs)
				.asMap()
		)
	}
	fixNestedLists(document)

	document.blocks.asSequence().forEach {
		when (it.id) {
			"protos"  -> {
				for (node in it.blocks)
					addFunction(node, structs)
			}
			"structs" -> {
				for (node in it.blocks)
					addStruct(node, structs)
			}
			"enums",
			"flags"   -> {
				for (node in it.blocks)
					addEnum(node, structs)
			}
		}
	}

	asciidoctor.shutdown()
}

private fun addFunction(node: StructuralNode, structs: Map<String, TypeStruct>) {
	val function = node.title.substring(2).substringBefore('(')
	//System.err.println(function)
	try {
		FUNCTION_DOC[function] = FunctionDoc(
			getShortDescription(node.blocks[0], structs),
			containerToJavaDoc(node.blocks[1], structs),
			containerToJavaDoc(node.blocks[3], structs),
			nodeToParamJavaDoc(node.blocks[2], structs)
		)
	} catch(e: Exception) {
		System.err.println("Failed while parsing: $function")
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
			nodeToParamJavaDoc(node.blocks[2], structs)
		)
	} catch(e: Exception) {
		System.err.println("Failed while parsing: $struct")
		throw RuntimeException(e)
	}
}

private val SECTION_XREFS = mapOf(
	"clears-values" to "the “Clear Values” section",
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
	"descriptorsets-updates-consecutive" to "consecutive binding updates",
	"devsandqueues-priority" to "the “Queue Priority” section",
	"devsandqueues-queueprops" to "the “Queue Family Properties” section",
	"dispatch" to "the “Dispatching Commands” chapter",
	"framebuffer-dsb" to "the “Dual-Source Blending” section",
	"fxvertex-attrib" to "the “Vertex Attributes” section",
	"fxvertex-input" to "the “Vertex Input Description” section",
	"geometry" to "the “Geometry Shading” chapter",
	"memory-device-hostaccess" to "the “Host Access to Device Memory Objects” section",
	"primsrast" to "the “Rasterization” chapter",
	"queries-pipestats" to "the “Pipeline Statistics Queries” section",
	"renderpass-compatibility" to "the “Render Pass Compatibility” section",
	"resources-association" to "the “Resource Memory Association” section",
	"resources-image-views" to "the “Image Views” section",
	"samplers-maxAnisotropy" to "samplers-maxAnisotropy",
	"samplers-mipLodBias" to "samplers-mipLodBias",
	"shaders-vertex" to "the “Vertex Shaders” section",
	"synchronization-pipeline-stage-flags" to "the “Pipeline Stage Flags” section",
	"synchronization-memory-barriers" to "the “Memory Barriers” section",
	"tessellation" to "the “Tessellation” chapter"
)

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
				"xref"        -> node.getAttr("refid").let {
					if (node.text != null)
						"<<$it,${node.text}>>"
					else {
						val title = SECTION_XREFS[it]// ?: throw IllegalStateException("Missing section reference: $it")
						if (title == null)
							System.err.println("Missing section reference: $it")
						"<<$it,$title>>"
					}
				}
				"ref"         -> ""
				"emphasis"    -> "_${node.text}_"
				"latexmath"   -> "<code>${LATEX_REGISTRY[node.text] ?: throw IllegalStateException("Missing LaTeX equation:\n${node.text}")}</code>"
				"line"        -> node.text
				"link"        -> "${node.target}[${node.text}]"
				"subscript"   -> "~${node.text}~"
				"superscript" -> "^${node.text}^"
				"double"      -> "``${node.text}''"
				"single"      -> "`${node.text}'"
				else          -> {
					System.err.println("type: ${node.type}")
					System.err.println("text: ${node.text}")
					System.err.println("target: ${node.target}")
					System.err.println("reftext: ${node.reftext}")
					System.err.println("id: ${node.id}")
					System.err.println("attributes: ${node.attributes}")
					throw IllegalStateException()
				}
			}
		}
		throw IllegalStateException("${node.nodeName} ${node.javaClass}")
	}
}

private val CODE_BLOCK_TRIM_PATTERN = """^\s*\n|\n\s*$""".toRegex() // first and/or last empty lines...
private val CODE_BLOCK_ESCAPE_PATTERN = "^[ \t\n]".toRegex(RegexOption.MULTILINE) // leading space/tab in line, empty line
private val CODE_BLOCK_TAB_PATTERN = "\t".toRegex() // tabs

fun codeBlock(code: String) = """<pre><code>${code
	.replace(CODE_BLOCK_TRIM_PATTERN, "") // ...trim
	.replace(CODE_BLOCK_ESCAPE_PATTERN, "\uFFFF$0") // ...escape
	.replace(CODE_BLOCK_TAB_PATTERN, "    ") // ...replace with 4 spaces for consistent formatting.
}</code></pre>"""

private val LATEX_MATH = """\\begin\{equation}\s*(.+?)\s*\\end\{equation}""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val LATEX_REGISTRY = mapOf(
	"""m = \sqrt{ \left({\partial z_f \over \partial x_f}\right)^2  +  \left({\partial z_f \over  \partial y_f}\right)^2}""" to
		codeBlock("      m = sqrt((&part;z<sub>f</sub> / &part;x<sub>f</sub>)<sup>2</sup> + (&part;z<sub>f</sub> / &part;y<sub>f</sub>)<sup>2</sup>)"),

	"""m = \max\left( \left| {\partial z_f \over \partial x_f} \right|, \left| {\partial z_f \over \partial y_f} \right| \right).""" to
		codeBlock("      m = max(abs(&part;z<sub>f</sub> / &part;x<sub>f</sub>), abs(&part;z<sub>f</sub> / &part;y<sub>f</sub>))"),

	"""o = \begin{cases}     m \times depthBiasSlopeFactor +          r \times depthBiasConstantFactor  & depthBiasClamp = 0\ or\ NaN \\     \min(m \times depthBiasSlopeFactor +          r \times depthBiasConstantFactor,          depthBiasClamp)                   & depthBiasClamp > 0  \\     \max(m \times depthBiasSlopeFactor +          r \times depthBiasConstantFactor,          depthBiasClamp)                   & depthBiasClamp < 0  \\ \end{cases}""" to
		codeBlock("""
        m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor                     depthBiasClamp = 0 or NaN
o = min(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &gt; 0
    max(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &lt; 0"""),

	"""$\lceil{\mathit{rasterizationSamples} \over 32}\rceil$"""
		to
		"ceil(rasterizationSamples / 32)",

	"""${S}codeSize \over 4$""" to
		"codeSize / 4"
)

private val LINE_BREAK = """\n\s*""".toRegex()

private val SIMPLE_NUMBER = """(?<=^|\s)`(\d+)`|code:(\d+)(?=\s|$)""".toRegex()
private val KEYWORD = """(?<=^|\s)(must|should|may|can|cannot):(?=\s|$)""".toRegex()
private val EMPHASIS = """(?<=^|\W)_([^_]+)_(?=[\W]|$)""".toRegex()
private val SUPERSCRIPT = """\^([^^]+)\^""".toRegex()
private val SUBSCRIPT = """~([^~]+)~""".toRegex()
private val MATHJAX = """\$([^$]+)\$""".toRegex()
private val DOUBLE = """``((?:(?!').)+)''""".toRegex()
private val EQUATION = """\[eq]#((?:[^#]|(?<=&)#(?=x?[0-9a-fA-F]{1,4};))+)#""".toRegex()
private val STRUCT_OR_HANDLE = """s(?:name|link):(\w+)""".toRegex()
private val STRUCT_FIELD = """::pname:(\w+)""".toRegex()
private val CODE1 = """`([^`]+)`""".toRegex()
private val FUNCTION = """(?:fname|flink):vk(\w+)""".toRegex()
private val FUNCTION_TYPE = """(?:tlink):PFN_vk(\w+)""".toRegex()
private val ENUM = """(?:ename|dlink|code):VK_(\w+)""".toRegex()
private val CODE2 = """(?:pname|basetype|ename|elink|code):(\w+(?:[.]\w+)*)""".toRegex()
private val LINK = """(http.*?/)\[([^\]]+)]""".toRegex()
private val SPEC_LINK = """<<([^,]+),([^>]+)>>""".toRegex()
private val EXTENSION = """[+](\w+)[+]""".toRegex()

private fun String.replaceMarkup(structs: Map<String, TypeStruct>): String = this.trim()
	.replace(LINE_BREAK, " ")
	.replace(LATEX_MATH) {
		// These will likely be replaced to reduce HTML load times.
		// Instead of trying to be clever and parse, we're lazy and
		// do a lookup  to prebaked HTML. There are not many LaTeX
		// equations anyway.
		val equation = it.groups[1]!!.value
		LATEX_REGISTRY[equation] ?: throw IllegalStateException("Missing LaTeX equation:\n$equation")
	}
	.replace(SIMPLE_NUMBER, "$1$2")
	.replace(KEYWORD, "<b>$1</b>")
	.replace(EMPHASIS, "<em>$1</em>")
	.replace(SUPERSCRIPT, "<sup>$1</sup>")
	.replace(SUBSCRIPT, "<sub>$1</sub>")
	.replace(MATHJAX, "<code>$1</code>")
	.replace(DOUBLE, "“$1”")
	.replace(EQUATION) { "<code>${it.groups[1]!!.value.replace(CODE2, "$1")}</code>" } // TODO: more?
	.replace(STRUCT_OR_HANDLE) {
		val type = it.groups[1]!!.value
		if (structs.containsKey(type))
			"##$type" // struct
		else
			"{@code $type}" // handle
	}
	.replace(STRUCT_FIELD, "{@code ::$1}")
	.replace(CODE1, "{@code $1}")
	.replace(FUNCTION, "#$1()")
	.replace(FUNCTION_TYPE) {
		val type = it.groups[1]!!.value
		if (type == "VoidFunction")
			"{@code PFN_vkVoidFunction}"
		else
			"##Vk$type"
	}
	.replace(ENUM, "#$1")
	.replace(CODE2, "{@code $1}")
	.replace(LINK, """<a href="$1">$2</a>""")
	.replace(SPEC_LINK, """<a href="https://www.khronos.org/registry/vulkan/specs/1.0-extensions/xhtml/vkspec.html\\#$1">$2</a>""")
	.replace(EXTENSION, "{@code $1}")

private fun getShortDescription(name: StructuralNode, structs: Map<String, TypeStruct>) =
	(name.blocks[0] as Block).lines[0]
		.let { it.substring(it.indexOf('-') + 2).replaceMarkup(structs) }
		.let { if (it.endsWith('.')) it else "$it." }

private fun containerToJavaDoc(node: StructuralNode, structs: Map<String, TypeStruct>, indent: String = ""): String =
	node.blocks.asSequence()
		.map { nodeToJavaDoc(it, structs, indent) }
		.joinToString("\n\n\t\t$indent").let {
		if (node.title == null || node.title.isEmpty() || it.isEmpty() || it.startsWith("<h5>"))
			it
		else
			"<h5>${node.title}</h5>\n\t\t$it".let {
				if (node.style == "NOTE") {
					"""<div style="margin-left: 26px; border-left: 1px solid gray; padding-left: 14px;">$it
		$indent</div>"""
				} else
					it
			}
	}

private fun nodeToJavaDoc(it: StructuralNode, structs: Map<String, TypeStruct>, indent: String = ""): String =
	if (it is Block) {
		if (it.lines.isEmpty())
			containerToJavaDoc(it, structs, indent)
		else {
			if (it.blocks.isNotEmpty())
				throw IllegalStateException()
			if (it.style == "source")
				codeBlock(it.source)
			else
				it.lines.joinToString(" ").replaceMarkup(structs)
		}
	} else if (it is org.asciidoctor.ast.List) {
		"""<ul>
			$indent${it.items.asSequence()
			.map { it as ListItem }
			.map {
				if (it.blocks.isNotEmpty())
					"""<li>
				$indent${it.text.replaceMarkup(structs)}
				$indent${containerToJavaDoc(it, structs, "\t\t$indent")}
			$indent</li>"""
				else
					"<li>${it.text.replaceMarkup(structs)}</li>"
			}
			.joinToString("\n\t\t\t$indent")}
		$indent</ul>"""
	} else if (it is Table) {
		"""${if (it.title == null) "" else "<h6>${it.title}</h6>\n\t\t"}<table class="lwjgl">
			${sequenceOf(
			it.header to ("thead" to "th"),
			it.footer to ("tfoot" to "td"),
			it.body to ("tbody" to "td")
		)
			.filter { it.first.isNotEmpty() }
			.map { section ->
				val (group, cell) = section.second
				"<$group>${section.first.asSequence()
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
						"\n\t\t\t\t$indent",
						prefix = if (section.first.size == 1) "" else "\n\t\t\t\t$indent",
						postfix = if (section.first.size == 1) "" else "\n\t\t\t$indent")
				}</$group>"
			}
			.joinToString("\n\t\t\t$indent")}
		$indent</table>"""
	} else if (it is DescriptionList) {
		"""<dl>
			${it.items.asSequence()
			.map {
				if (it.terms.size != 1)
					throw IllegalStateException("${it.terms}")

				"""${it.terms[0].text.let { if (it.isNotEmpty()) "<dt>${it.replaceMarkup(structs)}</dt>\n\t\t\t$indent" else "" }}<dd>${if (it.description.blocks.isEmpty())
					it.description.text.replaceMarkup(structs)
				else
					containerToJavaDoc(it.description, structs, "\t$indent")
				}</dd>"""
			}
			.joinToString("\n\n\t\t\t$indent")}
		$indent</dl>"""
	} else {
		throw IllegalStateException("${it.nodeName} - ${it.javaClass}")
	}

private val MULTI_PARAM_DOC_REGEX = Regex("""^\s*pname:(\w+)(?:[,:]?(?:\s+and)?\s+pname:(?:\w+))+\s+""")
private val PARAM_REGEX = Regex("""pname:(\w+)""")
private val PARAM_DOC_REGEX = Regex("""^\s*(When\s+)?pname:(\w+)(?:\[\d+])?(\.\w+)?[,:]?\s+(?:is\s+)?(.+)""", RegexOption.DOT_MATCHES_ALL)

private val ESCAPE_REGEX = Regex(""""|\\#""")

private fun nodeToParamJavaDoc(members: StructuralNode, structs: Map<String, TypeStruct>): Map<String, String> {
	if (members.blocks.isEmpty())
		return emptyMap()

	return members.blocks[0].let {
		if (it is org.asciidoctor.ast.List) it.items.asSequence()
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
						val (When, param, field, description) = PARAM_DOC_REGEX.matchEntire(it.text)!!.destructured
						sequenceOf(param to getItemDescription(it, if (When.isEmpty() && field.isEmpty()) description else it.text, structs))
					} catch(e: Exception) {
						println("FAILED AT: ${it.text}")
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
						.joinToString("\n\n\t\t", prefix = "\"\"", postfix = "\"\"")
			}
			.toMap()
		else
			emptyMap()
	}
}

private fun getItemDescription(listItem: ListItem, description: String, structs: Map<String, TypeStruct>) =
	if (listItem.blocks.isNotEmpty())
		"\"\"${description.replaceMarkup(structs)}\n${containerToJavaDoc(listItem, structs)}\"\""
	else
		description.replaceMarkup(structs)

private fun findNodes(node: StructuralNode, predicate: (StructuralNode) -> Boolean): Sequence<StructuralNode> =
	(
		if (predicate(node))
			sequenceOf(node)
		else
			emptySequence()
	) +
	(
		if (node.blocks == null)
			emptySequence()
		else
			node.blocks.asSequence()
				.flatMap { findNodes(it, predicate) }
	)

private fun printStructure(node: StructuralNode, indent: String = "") {
	System.err.println("$indent${node.level}. ${node.nodeName} ${node.title} ${node.attributes} ${node.javaClass}")

	if (node is org.asciidoctor.ast.List) {
		node.items.forEach {
			printStructure(it, "\t$indent")
		}
	} else if (node is DescriptionList) {
		node.items.forEach {
			printStructure(it.description, "\t$indent")
			it.terms.forEach {
				printStructure(it, "\t$indent")
			}
		}
	}

	if (node.blocks != null) {
		node.blocks.asSequence().forEach {
			printStructure(it, "\t$indent")
		}
	}
}


private val org.asciidoctor.ast.List.markerLength: Int get() = (this.items[0] as ListItem).marker.length

/*
This fixes https://github.com/asciidoctor/asciidoctorj/issues/466.
The fix is partial (we can change blocks/items, cannot change parents),
but good enough in our case.
 */
private fun fixNestedLists(node: StructuralNode) {
	// depth-first
	if (node.blocks != null)
		node.blocks.asSequence().forEach(::fixNestedLists)

	if (node is org.asciidoctor.ast.List) {
		// last-to-first
		node.items
			.reversed()
			.forEach(::fixNestedLists)
	} else if (node is ListItem && node.marker != null) {
		val bugged = node.blocks
			.asSequence()
			.filterIsInstance<org.asciidoctor.ast.List>()
			.filter { list -> list.markerLength < node.marker.length }
			.toMutableList()

		if (bugged.isNotEmpty()) {
			node.blocks.removeAll(bugged)

			// last-to-first
			bugged.reverse()
			bugged.forEach {
				val markerLength = it.markerLength
				val items = it.items.toList()
				it.items.clear()

				var anchor = node
				var parent = anchor.parent as org.asciidoctor.ast.List

				// Find correct parent list
				while (markerLength < parent.markerLength) {
					// If we're at the correct level minus one,
					// grab all children after the current anchor,
					// because they belong to this list
					if (markerLength + 1 == parent.markerLength) {
						val grabAfter = parent.items.indexOf(anchor) + 1

						while (grabAfter < parent.items.size)
							it.items.add(parent.items.removeAt(grabAfter))

						if (it.items.isNotEmpty())
							items.last().blocks.add(it)
					}

					// Go up
					anchor = (parent.parent as ListItem)
					parent = anchor.parent as org.asciidoctor.ast.List
				}

				// Inject to the correct parent
				parent.items.addAll(parent.items.indexOf(anchor) + 1, items)
			}
		}
	} else if (node is Table) {
		sequenceOf(
			node.header,
			node.body,
			node.footer
		).forEach {
			it.forEach {
				it.cells.forEach {
					if (it.style == "asciidoc")
						fixNestedLists(it.innerDocument)
				}
			}
		}
	} else if (node is DescriptionList) {
		node.items.forEach {
			fixNestedLists(it.description)
			it.terms.forEach(::fixNestedLists)
		}
	}
}