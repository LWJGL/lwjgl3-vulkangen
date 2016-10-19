/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import org.asciidoctor.Asciidoctor
import org.asciidoctor.OptionsBuilder
import org.asciidoctor.SafeMode
import org.asciidoctor.ast.*
import org.asciidoctor.converter.AbstractConverter
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

fun convert(root: Path) {
	val asciidoctor = Asciidoctor.Factory.create()

	asciidoctor.javaConverterRegistry().register(LWJGLTemplateConverter::class.java, "lwjgl-template")

	val man = root.resolve("doc/specs/vulkan/man")
	val reader = Files.newBufferedReader(man.resolve("apispec.txt"))
	val writer = StringWriter()

	asciidoctor.convert(
		reader, writer,
		OptionsBuilder.options()
			.backend("lwjgl-template")
			.safe(SafeMode.UNSAFE)
			.baseDir(man.toFile())
	)

	println(writer.toString())
}

class Function(
	val returnType: String,
	val name: String,
	val description: String,
	val parameters: List<String>
	// TODO: host sync, command properties, see also, document notes
)

internal val QUOTES3 = "\"\"\""
internal val S = "\$"
private val PARAM_DOC_REGEX = Regex("^pname:(\\w+) is\\s+(.+)", RegexOption.DOT_MATCHES_ALL)

// Matches both [param type + param name] and [return type + function name]
private val FUNCTION_REGEX = Regex(
	"""(const\s+)?((?:un)?signed\s+)?(?:(?:struct|enum)\s+)?([0-9a-zA-Z_]++)(\s+const)?\s*([*]+)?\s*([0-9a-zA-Z_]+)"""
)

class LWJGLTemplateConverter(backend: String, opts: Map<String, Any>) : AbstractConverter<String>(backend, opts) {

	val functions = ArrayList<Function>(128)

	override fun convert(node: ContentNode, transform: String?, opts: Map<Any, Any>): String {
		val writer = PrintWriter(System.out)
		if (node is StructuralNode) {
			processNode(writer, node, "")

			writer.println("\nFOUND FUNCTIONS:\n---------------")
			for (func in functions) {
				writer.println("""
	${func.returnType}(
		"${func.name}",
		$QUOTES3
		${func.description}
		$QUOTES3${func.parameters.let { if (it.isEmpty()) "" else it.joinToString(",\n\t\t", prefix = ",\n\n\t\t") }}
	)""")
			}

		} else if (node is Column) {
			writer.println("COLUMN: ${node.id} ${node.columnNumber} ${node.style} ${node.nodeName}")
		} else if (node is PhraseNode) {
			writer.println("PHRASE: ${node.id} ${node.nodeName} ${node.text} ${node.type} ${node.target}")
		} else {
			writer.println("TODO: ${node.javaClass}")
		}

		writer.flush()
		return ""
	}

	private fun addFunction(node: StructuralNode) {
		// 0. Section: Name
		// 1. Section: C Specification
		// 2. Section: Parameters
		// 3. Section: Description
		// Block: Valid Usage
		// Block: Valid Usage (Implicit)
		// Block: Host Synchronization
		// Block: Return Codes
		// 4. Section: See Also
		// 5. Section: Document Notes

		functions.add(Function(
			getFunctionReturnType(node.blocks[1]),
			getFunctionName(node.blocks[1]),
			getFunctionDescription(
				node.blocks[0],
				node.blocks[3]
			),
			getFunctionParameters(
				node.blocks[1],
				node.blocks[2]
			)
		))
	}

	private fun getFunctionReturnType(specification: StructuralNode): String {
		val (constBefore, signedness, returnType, constAfter, pointers) = FUNCTION_REGEX
			.find((specification.blocks[1] as Block).source)!!
			.destructured

		val type = "${
		if (constBefore.isNotEmpty()) "const.." else ""
		}${
		if (signedness.isNotEmpty()) "${signedness}_" else ""
		}$returnType${
		if (constAfter.isNotEmpty()) "_const" else ""
		}${if (pointers.isNotEmpty()) pointers.length
			.downTo(0)
			.asSequence()
			.map { "p" }
			.joinToString("", prefix = "_")
		else ""
		}"

		return type
	}

	private fun getFunctionName(specification: StructuralNode): String {
		return specification.blocks[1].id.substring(2)
	}

	private fun getFunctionDescription(name: StructuralNode, description: StructuralNode): String {
		return """${(name.blocks[0] as Block).lines[0]
			.let { it.substring(it.indexOf('-') + 2) }
			.let { if (it.endsWith('.')) it else "$it." }
		}

		${getFunctionDescription(description)}"""
	}

	private fun getFunctionDescription(node: StructuralNode): String {
		// TODO: 1) Replace Vulkan markup with LWJGL markup
		// TODO: 2) Custom joinToString that respects the LWJGL right margin
		return node.blocks.map {
			if (it is Block) {
				if (it.lines.isEmpty())
					getFunctionDescription(it)
				else
					it.lines.joinToString("\n\t\t")
			} else if (it is org.asciidoctor.ast.List) {
				"""${it.title.orEmpty()}$S{ul(
			${it.items.map {
					if (it is ListItem) {
						val text = it.text
						if (text.contains('\n'))
							"""$QUOTES3
			${it.text.splitToSequence("\n").joinToString("\n\t\t\t")}
			$QUOTES3"""
						else
							"\"${it.text}\""
					} else
						"TODO: ${it.javaClass}"
				}.joinToString(",\n\t\t\t")}
		)}"""
			} else {
				"TODO: ${it.javaClass}"
			}
		}.joinToString("\n\n\t\t").let {
			if (node.title.isEmpty() || it.startsWith("<h5>"))
				it
			else
				"<h5>${node.title}</h5>\n\t\t$it"
		}
	}

	private fun getFunctionParameters(specification: StructuralNode, parameters: StructuralNode): List<String> {
		if (parameters.blocks.isEmpty())
			return emptyList()

		val documentation = (parameters.blocks[0] as org.asciidoctor.ast.List).items

		return FUNCTION_REGEX
			.findAll((specification.blocks[1] as Block).source)
			.drop(1)
			.mapIndexed { i, matchResult ->
				val (constBefore, signedness, returnType, constAfter, pointers, name) = matchResult.destructured

				"${if (constBefore.isNotEmpty()) "const.." else ""}${if (signedness.isNotEmpty()) "${signedness}_" else ""}$returnType${
				if (constAfter.isNotEmpty()) "_const" else ""}${if (pointers.isNotEmpty()) pointers.length
					.downTo(1)
					.asSequence()
					.map { "p" }
					.joinToString("", prefix = "_")
				else ""
				}.${if (pointers.isEmpty() || constBefore.isNotEmpty()) "IN" else "OUT"
				}(\"$name\", $QUOTES3${(documentation[i] as ListItem).text.replace(PARAM_DOC_REGEX, "$2")}$QUOTES3)"
			}.toList()
	}

	private fun processNode(writer: PrintWriter, node: StructuralNode, indent: String) {
		writer.println("${indent}__${node.nodeName}__ (${node.javaClass})")
		if (node.title != null)
			writer.println("${indent}TITLE: ${node.title}")
		if (node.id != null)
			writer.println("${indent}ID: ${node.id}")
		if (node.attributes.isNotEmpty())
			writer.println("${indent}ATTRIBS: ${node.attributes}")

		if (node is Section) {
			writer.println("${indent}SECTION ${node.index}: ${node.sectionName}")
		} else if (node is Block) {
			if (node.lines.isNotEmpty())
				writer.println("${indent}BLOCK: ${node.source}")
		} else if (node is org.asciidoctor.ast.List) {
			for (child in node.items) {
				processNode(writer, child, indent + "\t")
			}
		} else if (node is ListItem) {
			writer.println("$indent${node.marker} ${if (node.hasText()) node.text else ""}")
		} else if (node is Table) {
			writer.println(indent + "------------------ TABLE HEADER -------------------")
			for (row in node.header) {
				for (c in row.cells) {
					writer.println("$indent${c.text}")
				}
			}
			writer.println(indent + "------------------ TABLE BODY -------------------")
			for (row in node.body) {
				for (c in row.cells) {
					writer.println("$indent${c.text}")

				}
			}
		} else if (node is DescriptionList) {
			writer.println(indent + "------------------ DESCRIPTION LIST (${node.hasItems()}) -------------------")
			if (node.hasItems()) {
				for (item in node.items) {
					val description = item.description
					//writer.println("$indent${description.marker} ${if (description.hasText()) description.text else ""} ${description.id} ${description.reftext}")
					processNode(writer, description, indent)
					/*if (description.blocks != null && !description.blocks.isEmpty()) {
						for (child in description.blocks) {
							processNode(writer, child, "$indent\t")
						}
					}*/
					for (term in item.terms) {
						processNode(writer, term, indent + "\t")
					}
				}
			}
		} else {
			writer.println("${indent}TODO: ${node.javaClass}")
		}

		if (node.blocks != null && !node.blocks.isEmpty()) {
			if (node.id == "protos") {
				for (child in node.blocks) {
					addFunction(child)
					processNode(writer, child, "$indent\t")
				}
			} else {
				for (child in node.blocks) {
					processNode(writer, child, "$indent\t")
				}
			}
		}
	}

	override fun write(output: String, out: OutputStream) {
	}
}