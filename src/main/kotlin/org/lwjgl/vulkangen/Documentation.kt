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
import java.util.stream.Collectors

internal val QUOTES3 = "\"\"\""
internal val S = "\$"

internal fun convert(root: Path, structs: Map<String, TypeStruct>) {
	val man = root.resolve("doc/specs/vulkan/man")

	val asciidoctor = Asciidoctor.Factory.create()

	// Register a converter that defeats conversions performed by asciidoctor inside lists.
	// This way all texts are uniform, simplifying the final conversion.
	asciidoctor
		.javaConverterRegistry()
		.register(PlainConverter::class.java, "plain")

	val document = asciidoctor.load(
		Files.lines(man.resolve("apispec_work.txt"))
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
			.asMap()
	)

	document.blocks.asSequence().forEach {
		when (it.id) {
			"protos"  -> {
				for (node in it.blocks)
					addFunction(node, structs)
			}
		}
	}

	asciidoctor.shutdown()
}

private val SECTION_XREFS = mapOf(
	"clears-values" to "“Clear Values”",
	"descriptorsets-compatibility" to "“Pipeline Layout Compatibility”",
	"fxvertex-input" to "“Vertex Input Description”",
	"renderpass-compatibility" to "“Render Pass Compatibility”",
	"synchronization-pipeline-stage-flags" to "“Pipeline Stage Flags”",
	"synchronization-memory-barriers" to "“Memory Barriers”"
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
						val title = SECTION_XREFS[it] ?: throw IllegalStateException("Missing section reference: $it")
						"<<$it,the $title section>>"
					}
				}
				"emphasis"    -> "_${node.text}_"
				"line"        -> node.text
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
    max(m &times; depthBiasSlopeFactor + r &times; depthBiasConstantFactor, depthBiasClamp)    depthBiasClamp &lt; 0""")
)

private val LINE_BREAK = """\n\s*""".toRegex()

private val SIMPLE_NUMBER = """(?<=^|\s)`(\d+)`|code:(\d+)(?=\s|$)""".toRegex()
private val KEYWORD = """(?<=^|\s)(must|should|may|can|cannot):(?=\s|$)""".toRegex()
private val EMPHASIS = """(?<=^|\W)_([^_]+)_(?=[\W]|$)""".toRegex()
private val SUPERSCRIPT = """\^([^^]+)\^""".toRegex()
private val SUBSCRIPT = """~([^~]+)~""".toRegex()
private val MATHJAX = """\$([^$]+)\$""".toRegex()
private val DOUBLE = """``((?:(?!').)+)''""".toRegex()
private val STRUCT_OR_HANDLE = """s(?:name|link):(\w+)""".toRegex()
private val STRUCT_FIELD = """::pname:(\w+)""".toRegex()
private val EQUATION = """\[eq]#([^#]+)#""".toRegex()
private val CODE1 = """`([^`]+)`""".toRegex()
private val FUNCTION = """(?:fname|flink):vk(\w+)""".toRegex()
private val FUNCTION_TYPE = """(?:tlink):PFN_vk(\w+)""".toRegex()
private val ENUM = """(?:ename|dlink|code):VK_(\w+)""".toRegex()
private val CODE2 = """(?:pname|basetype|elink|code):(\w+(?:[.]\w+)*)""".toRegex()
private val SPEC_LINK = """<<([^,]+),([^>]+)>>""".toRegex()
private val EXTENSION = """[+](\w+)[+]""".toRegex()

internal fun String.replaceMarkup(structs: Map<String, TypeStruct>): String = this.trim()
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
	.replace(STRUCT_OR_HANDLE) {
		val type = it.groups[1]!!.value
		if (structs.containsKey(type))
			"##$type" // struct
		else
			"{@code $type}" // handle
	}
	.replace(STRUCT_FIELD, "{@code ::$1}")
	.replace(EQUATION) { "<code>${it.groups[1]!!.value.replace(CODE2, "$1")}</code>" } // TODO: more?
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
	.replace(SPEC_LINK, """<a href=\\"https://www.khronos.org/registry/vulkan/specs/1.0-extensions/xhtml/vkspec.html\\\\#$1\\">$2</a>""")
	.replace(EXTENSION, "{@code $1}")

internal fun nodeToJavaDoc(node: StructuralNode, structs: Map<String, TypeStruct>): String {
	return node.blocks.asSequence().map {
		if (it is Block) {
			if (it.lines.isEmpty())
				nodeToJavaDoc(it, structs)
			else {
				if (it.blocks.isNotEmpty())
					throw IllegalStateException()
				it.lines.joinToString(" ").replaceMarkup(structs)
			}
		} else if (it is org.asciidoctor.ast.List) { // TODO: title?
			"""<ul>
			${it.items.asSequence().map {
				if (it is ListItem) {
					"<li>${it.text.replaceMarkup(structs)}</li>"
				} else
					throw IllegalStateException("${it.nodeName} - ${it.javaClass}")
			}.joinToString("\n\t\t\t")}
		</ul>"""
		} else if (it is Table) {
			"""${if (it.title == null) "" else "<h6>${it.title}</h6>\n\t\t"}<table class="lwjgl">
			${sequenceOf(
				it.header to ("thead" to "th"),
				it.footer to ("tfoot" to "td"),
				it.body to ("tbody" to "td")
			).map { section ->
				if (section.first.isEmpty())
					""
				else {
					val (group, cell) = section.second
					"<$group>${section.first.asSequence()
						.map {
							"<tr>${it.cells.asSequence().map { "<$cell>${it.text.replaceMarkup(structs)}</$cell>" }.joinToString("")}</tr>"
						}
						.joinToString(
							"\n\t\t\t\t",
							prefix = if (section.first.size == 1) "" else "\n\t\t\t\t",
							postfix = if (section.first.size == 1) "" else "\n\t\t\t")
					}</$group>"
				}
			}.filter(String::isNotEmpty)
				.joinToString("\n\t\t\t")
			}
		</table>"""
		} else if (it is DescriptionList) {
			"""<dl>
			${it.items.asSequence().map {
				if (it.terms.size != 1)
					throw IllegalStateException("${it.terms}")

				"""${it.terms[0].text.let { if (it.isNotEmpty()) "<dt>${it.replaceMarkup(structs)}</dt>\n\t\t\t" else "" }}<dd>${if (it.description.blocks.isEmpty())
					it.description.text.replaceMarkup(structs)
				else
					nodeToJavaDoc(it.description, structs)
				}</dd>"""
			}.joinToString("\n\n\t\t")}
		</dl>"""
		} else {
			throw IllegalStateException("${it.nodeName} - ${it.javaClass}")
		}
	}.joinToString("\n\n\t\t").let {
		if (node.title == null || node.title.isEmpty() || it.startsWith("<h5>"))
			it
		else
			"<h5>${node.title}</h5>\n\t\t$it"
	}
}