package org.lwjgl.vulkangen

import org.asciidoctor.ast.Block
import org.asciidoctor.ast.ListItem
import org.asciidoctor.ast.StructuralNode
import java.util.*

internal val FUNCTION_DOC = HashMap<String, FunctionDoc>(256)

internal class FunctionDoc(
	val shortDescription: String,
	val cSpecification: String,
	val description: String,
	val parameters: Map<String, String>
	// TODO: host sync, command properties, see also, document notes
)

internal fun addFunction(node: StructuralNode, structs: Map<String, TypeStruct>) {
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

	val function = node.title.substring(2).substringBefore('(')
	System.err.println(function)
	try {
		FUNCTION_DOC[function] = FunctionDoc(
			getFunctionShortDescription(node.blocks[0], structs),
			getFunctionCSpecification(node.blocks[1], structs),
			nodeToJavaDoc(node.blocks[3], structs),
			getFunctionParameters(node.blocks[2], structs)
		)
	} catch(e: Exception) {
		System.err.println("Failed while parsing: $function")
		throw RuntimeException(e)
	}
}

private fun getFunctionShortDescription(name: StructuralNode, structs: Map<String, TypeStruct>) = (name.blocks[0] as Block).lines[0]
	.let { it.substring(it.indexOf('-') + 2).replaceMarkup(structs) }
	.let { if (it.endsWith('.')) it else "$it." }

private fun getFunctionCSpecification(cSpec: StructuralNode, structs: Map<String, TypeStruct>): String {
	return """<h5>C Specification</h5>
		${(cSpec.blocks[0] as Block).source.replaceMarkup(structs)}

		${codeBlock((cSpec.blocks[1] as Block).source)}"""
}

private val PARAM_DOC_REGEX = Regex("""^pname:(\w+)\s+(?:is\s+)?(.+)""", RegexOption.DOT_MATCHES_ALL)

private fun getFunctionParameters(parameters: StructuralNode, structs: Map<String, TypeStruct>): Map<String, String> {
	if (parameters.blocks.isEmpty())
		return emptyMap()

	return parameters.blocks[0].let {
		if (it is org.asciidoctor.ast.List) it.items.asSequence()
			.filterIsInstance(ListItem::class.java)
			.mapNotNull {
				if (it.text == null)
					null
				else {
					val (param, description) = PARAM_DOC_REGEX.matchEntire(it.text)!!.destructured
					param to description.replaceMarkup(structs)
				}
			}
			.toMap()
		else
			emptyMap()
	}
}
