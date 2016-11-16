/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

val DISABLED_EXTENSIONS = setOf(
	"VK_KHR_android_surface",
	"VK_KHR_mir_surface",
	"VK_KHR_wayland_surface",
	"VK_KHR_xcb_surface",

	"VK_ANDROID_native_buffer"
)

val ABBREVIATIONS = setOf(
	"gcn",
	"glsl",
	"gpu",
	"pvrtc"
)

val IMPORTS = mapOf(
	"X11/Xlib.h" to "org.lwjgl.system.linux.*",
	"windows.h" to "org.lwjgl.system.windows.*"
)

val HEADER = """/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 * MACHINE GENERATED FILE, DO NOT EDIT
 */
"""

internal class LWJGLWriter(out: Writer) : PrintWriter(out) {
	override fun println() = print('\n')
}

fun main(args: Array<String>) {
	if (args.size != 2)
		throw IllegalArgumentException("Usage: VulkanSpecKt <vulkan-docs-path> <lwjgl3-path>")

	val vulkanDocs = Paths.get(args[0])
	val registry = vulkanDocs.let {
		if (!Files.isDirectory(it))
			throw IllegalArgumentException("Invalid Vulkan-Docs repository path specified: $it")

		val registryPath = it.resolve("src/spec/vk.xml")
		if (!Files.isRegularFile(registryPath))
			throw IllegalArgumentException("The path specified does not contain the Vulkan-Docs repository: $it")

		parse(registryPath)
	}

	val vulkanPath = "org/lwjgl/vulkan"
	val root = args[1].let {
		val lwjgl3 = Paths.get(it)
		if (!Files.isDirectory(lwjgl3))
			throw IllegalArgumentException("Invalid lwjgl3 repository path specified: $it")

		val root = lwjgl3.resolve("modules/templates/src/main/kotlin/$vulkanPath")
		if (!Files.isDirectory(root))
			throw IllegalArgumentException("The path specified does not contain the lwjgl3 repository: $it")

		Files.createDirectories(root.resolve("templates"))

		root
	}

	val types = registry.types.asSequence()
		.associateBy(Type::name)

	val structs = registry.types.asSequence()
		.filterIsInstance<TypeStruct>()
		.associateBy(Type::name)

	val enums = registry.enums.asSequence()
		.associateBy { it.name }

	val commands = registry.commands.asSequence()
		.associateBy { it.proto.name }

	try {
		convert(vulkanDocs, structs)
	} catch(e: Exception) {
		e.printStackTrace()
		System.exit(-1)
	}

	val vulkanPackage = vulkanPath.replace('/', '.')

	// TODO: This must be fixed post Vulkan 1.0. We currently have no other way to identify types used in core only.
	val featureTypes = getDistinctTypes(registry.features[0].requires.asSequence(), commands, types)
	generateTypes(root, vulkanPackage, "VKTypes", types, structs, featureTypes) {
		registry.tags.asSequence()
			.map { "val ${it.name} = \"${it.name}\"" }
			.joinToString("\n", postfix = "\n\n")
	}

	var enumsSeen = featureTypes.filterEnums(enums)
	registry.features.forEach { feature ->
		generateFeature(root, vulkanPackage, types, enums, structs, commands, feature, enumsSeen)
	}

	val extensions = registry.extensions.asSequence()
		.filter { it.supported != "disable" && it.supported != "disabled" && !DISABLED_EXTENSIONS.contains(it.name) }

	val extensionTypes = getDistinctTypes(extensions.map { it.require }, commands, types)
		.filter { !featureTypes.contains(it) }
		.toSet()

	generateTypes(root, vulkanPackage, "ExtensionTypes", types, structs, extensionTypes)
	extensions.forEach { extension ->
		// Type declarations for enums are missing in some extensions.
		// We generate <enums> the first time we encounter them.
		enumsSeen = generateExtension(root, vulkanPackage, types, enums, structs, commands, extension, enumsSeen)
	}

	System.exit(0)
}

/*
The sequence order here is important: We return the types in the order we encounter them in the API
(function return type first, function param types later, etc). Doing it this way matches the order
in <vulkan.h>.
 */
private fun getDistinctTypes(
	requires: Sequence<Require>,
	commands: Map<String, Command>,
	types: Map<String, Type>
): Set<Type> = requires
	.flatMap {
		// Get all top-level types
		sequenceOf(
			if (it.types == null) emptySequence<String>() else it.types.asSequence()
				.map { it.name },
			if (it.enums == null) emptySequence<String>() else it.enums.asSequence()
				.map { it.name },
			if (it.commands == null) emptySequence<String>() else it.commands.asSequence()
				.map { commands[it.name]!! }
				.flatMap {
					sequenceOf(it.proto.type) + it.params.asSequence().map { it.type }
				}
		)
			.flatten()
			.distinct()
			.flatMap {
				if (!types.containsKey(it)) {
					emptySequence<Type>()
				} else {
					// recurse into struct types
					getDistinctTypes(it, types)
				}
			}
	}
	.toSet()

private fun getDistinctTypes(name: String, types: Map<String, Type>): Sequence<Type> {
	val type = types[name]!!
	return if (type is TypeStruct)
		type.members.asSequence().flatMap { getDistinctTypes(it.type, types) } + sequenceOf(type)
	else if (type is TypeFuncpointer)
		getDistinctTypes(type.proto.type, types) + sequenceOf(type) + type.params.asSequence().flatMap { getDistinctTypes(it.type, types) }
	else
		sequenceOf(type)
}

private fun getReturnType(proto: Field): String = proto.let { "${if (it.modifier == "const") "const.." else ""}${it.type}${it.indirection}" }

private fun getCheck(param: Field, indirection: String, structs: Map<String, TypeStruct>, forceIN: Boolean): String {
	if (indirection.isNotEmpty()) { // pointer
		if (param.array != null) {
			return "Check(${param.array}).."
		} else if (param.len.none()) { // not auto-sized
			if (!forceIN && param.modifier != "const" && !structs.containsKey(param.type)) // output, non-struct
				return "Check(1).."
		} else {
			// TODO: support more? this currently only supports "struct.member"
			val customExpression = param.len.firstOrNull { it.contains("::") }
			if (customExpression != null)
				return "Check(\"${customExpression.substringBefore(':')}.${customExpression.substringAfterLast(':')}()\").."
		}
	}
	return ""
}

private fun getParams(returns: Field, params: List<Field>, types: Map<String, Type>, structs: Map<String, TypeStruct>, forceIN: Boolean = false, indent: String = "\t\t"): String = if (params.isEmpty())
	""
else {
	val functionDoc = FUNCTION_DOC[returns.name.let { if (it.startsWith("PFN_vk")) it else it.substring(2) }]
	params.asSequence().map { param ->
		val autoSize = params.asSequence()
			.filter { it.len.contains(param.name) }
			.map { "\"${it.name}\"" }
			.joinToString(",")
			.let {
				if (it.isEmpty())
					""
				else
					"AutoSize($it).."
			}

		val indirection = param.indirection.let {
			if (param.array != null) {
				if (it.isEmpty())
					"_p"
				else
					"${it}p"
			} else
				it
		}

		val forceINParam = forceIN || types[param.type] is TypeSystem || /* TODO: validate this */ param.externsync != null
		val check = getCheck(param, indirection, structs, forceINParam)

		val nullable = if ((indirection.isNotEmpty() || types[param.type] is TypeFuncpointer) && (
			// the parameter is optional
			"true" == param.optional ||
			// the AutoSize param is optional
			param.len.any() && params.any { param.len.contains(it.name) && "true" == it.optional })
		) "nullable.." else ""

		val const = if (param.modifier == "const") "const.." else ""

		val encoding = if (param.len.contains("null-terminated") || (param.array != null && param.type == "char")) {
			if (returns.type == "PFN_vkVoidFunction") "ASCII" else "UTF8"
		} else ""
		val type = if (param.type == "void" && indirection == "_p" && param.len.none() && check.isEmpty())
			"voidptr"
		else
			"${param.type}$encoding${if (indirection.isNotEmpty() && types[param.type].let { it !is TypePlatform })
				indirection.replace('_', '.')
			else
				indirection
			}"

		val paramType = if (forceINParam || indirection.isEmpty() || param.modifier == "const")
			"IN"
		else if ("false,true" == param.optional)
			"INOUT"
		else
			"OUT"

		"$autoSize$check$nullable$const$type.$paramType(\"${param.name}\", \"${functionDoc?.parameters?.get(param.name) ?: ""}\")"
	}.joinToString(",\n$indent", prefix = ",\n\n$indent")
}

private fun getJavaImports(vulkanPackage: String, types: Map<String, Type>, fields: Sequence<Field>) = fields
	.mapNotNull {
		if (it.array != null && it.array.startsWith("\"VK_MAX_"))
			"static $vulkanPackage.VK10.*"
		else {
			val type = types[it.type]
			if (type is TypeSystem)
				IMPORTS[type.requires]
			else
				null
		}
	}
	.distinct()
	.map { "javaImport(\"$it\")" }
	.joinToString("\n\t")
	.let { if (it.isEmpty()) "" else "$it\n\t" }

private fun generateTypes(
	root: Path,
	vulkanPackage: String,
	template: String,
	types: Map<String, Type>,
	structs: Map<String, TypeStruct>,
	templateTypes: Set<Type>,
	custom: (() -> String)? = null
) {
	val file = root.resolve("$template.kt")

	LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
		writer.print(HEADER)
		writer.print("""package $vulkanPackage

import org.lwjgl.generator.*${if (templateTypes.any { it is TypeSystem }) """
import org.lwjgl.system.linux.*
import org.lwjgl.system.windows.*""" else ""}

${if (custom != null) custom() else ""}// Handle types
${templateTypes
			.filterIsInstance<TypeHandle>()
			.map { "val ${it.name} = ${it.type}(\"${it.name}\")" }
			.joinToString("\n")}

// Enum types
${templateTypes
			.filterIsInstance<TypeEnum>()
			.map { "val ${it.name} = \"${it.name}\".enumType" }
			.joinToString("\n")}

// Bitmask types
${templateTypes
			.filterIsInstance<TypeBitmask>()
			.map { "val ${it.name} = typedef(VkFlags, \"${it.name}\")" }
			.joinToString("\n")}

${templateTypes
			.filterIsInstance<TypeFuncpointer>()
			.map {
				val functionDoc = FUNCTION_DOC[it.name]
				"""val ${it.name} = "${it.name}".callback(
	VULKAN_PACKAGE, ${getReturnType(it.proto)}, "${it.name.substring(4).let { "${it[0].toUpperCase()}${it.substring(1)}" }}",
	"${functionDoc?.shortDescription ?: ""}"${getParams(it.proto, it.params, types, structs, forceIN = true, indent = "\t")}
) {
	${getJavaImports(vulkanPackage, types, sequenceOf(it.proto) + it.params.asSequence())}${if (functionDoc == null) "" else """documentation =
		$QUOTES3
		${functionDoc.shortDescription}

		${functionDoc.cSpecification}

		${functionDoc.description}${if (functionDoc.seeAlso == null) "" else """

		${functionDoc.seeAlso}"""}
		$QUOTES3
	"""}useSystemCallConvention()
}"""
			}
			.joinToString("\n\n").let {
			if (it.isNotEmpty())
				"""// Function pointer types
$it

""" else ""
		}}// Struct types
${templateTypes
			.filterIsInstance<TypeStruct>()
			.map { struct ->
				val structDoc = STRUCT_DOC[struct.name]

				"""val ${struct.name} = ${struct.type}(VULKAN_PACKAGE, "${struct.name}"${if (struct.returnedonly) ", mutable = false" else ""}) {
	${getJavaImports(vulkanPackage, types, struct.members.asSequence())}${if (structDoc == null) "" else """documentation =
		$QUOTES3
		${structDoc.shortDescription}${if (structDoc.description.isEmpty()) "" else """

		${structDoc.description}${if (structDoc.seeAlso == null) "" else """

		${structDoc.seeAlso}"""}"""}
		$QUOTES3

	"""}${struct.members.asSequence()
					.map { member ->
						val autoSize = struct.members.asSequence()
							.filter { it.len.contains(member.name) }
							.map { "\"${it.name}\"" }
							.joinToString(",")
							.let {
								if (it.isEmpty())
									""
								else if (member.optional != null)
									"AutoSize($it, optional = true).."
								else if (struct.members.asSequence()
									         .filter { it.len.contains(member.name) && it.noautovalidity != null }
									         .count() > 1
								)
									"AutoSize($it, atLeastOne = true).."
								else
									"AutoSize($it).."
							}

						val nullable = if ((member.name == "pNext" || member.optional != null || (member.noautovalidity != null && member.len.any() && member.len.first().let { len ->
							struct.members.asSequence()
								.filter { it.len.contains(len) }
								.count() > 1
						})) && (member.indirection.isNotEmpty() || types[member.type] is TypeFuncpointer)) "nullable.." else ""

						val const = if (member.modifier == "const") "const.." else ""

						val encoding = if (member.len.contains("null-terminated") || (member.array != null && member.type == "char")) "UTF8" else ""
						val type = if (member.type == "void" && member.indirection == "_p" && member.len.none())
							"voidptr"
						else
							"${member.type}$encoding${if (member.indirection.isNotEmpty() && types[member.type].let { it !is TypePlatform })
								member.indirection.replace('_', '.')
							else
								member.indirection
							}"

						val memberType = when {
							member.array != null                                 -> "array"
							member.len.any() && types[member.type] is TypeStruct -> "buffer"
							else                                                 -> "member"
						}

						"$autoSize$nullable$const$type.$memberType(\"${member.name}\", \"${structDoc?.members?.get(member.name) ?: ""}\"${if (member.array != null) ", size = ${member.array}" else ""})"
					}
					.joinToString("\n\t")}
}"""
			}
			.joinToString("\n\n")}""")
	}
}

private fun generateFeature(
	root: Path,
	vulkanPackage: String,
	types: Map<String, Type>,
	enums: Map<String, Enums>,
	structs: Map<String, TypeStruct>,
	commands: Map<String, Command>,
	feature: Feature,
	featureEnums: Sequence<Enums>
) {
	val template = "VK${feature.number.substringBefore('.')}${feature.number.substringAfter('.')}"
	val file = root.resolve("templates/$template.kt")

	LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
		writer.print(HEADER)
		writer.print("""package $vulkanPackage.templates

import org.lwjgl.generator.*
import $vulkanPackage.*

val $template = "$template".nativeClass(VULKAN_PACKAGE, "$template", prefix = "VK", binding = VK_BINDING) {
	documentation =
		$QUOTES3
		The core Vulkan ${feature.number} functionality.
		$QUOTES3
""")
		// API Constants

		writer.println("""
	IntConstant(
		"API Constants",

		${enums["API Constants"]!!.enums.asSequence()
			.filter { !it.value!!.contains('L') && !it.value.contains('f') }
			.map { "\"${it.name.substring(3)}\"..\"${it.value!!.replace("U", "")}\"" }
			.joinToString(",\n\t\t")}
	)"""
		)

		writer.println("""
	FloatConstant(
		"API Constants",

		${enums["API Constants"]!!.enums.asSequence()
			.filter { it.value!!.contains('f') }
			.map { "\"${it.name.substring(3)}\"..\"${it.value}\"" }
			.joinToString(",\n\t\t")}
	)"""
		)

		writer.println("""
	LongConstant(
		"API Constants",

		${enums["API Constants"]!!.enums.asSequence()
			.filter { it.value!!.contains('L') }
			.map { "\"${it.name.substring(3)}\"..\"${it.value!!.replace("U?L+".toRegex(), "L")}\"" }
			.joinToString(",\n\t\t")}
	)"""
		)

		writer.printEnums(featureEnums)

		feature.requires.asSequence()
			.filter { it.commands != null }
			.forEach {
				writer.println("\n\t// ${it.comment}")
				writer.printCommands(it.commands!!.asSequence(), types, structs, commands)
			}

		writer.print("\n}")
	}
}

private fun generateExtension(
	root: Path,
	vulkanPackage: String,
	types: Map<String, Type>,
	enums: Map<String, Enums>,
	structs: Map<String, TypeStruct>,
	commands: Map<String, Command>,
	extension: Extension,
	enumsSeen: Sequence<Enums>
): Sequence<Enums> {
	val distinctTypes = getDistinctTypes(sequenceOf(extension.require), commands, types)
	val extensionEnums = distinctTypes
		.filterEnums(enums)
		.filter { extEnum -> enumsSeen.none { extEnum.name == it.name } }

	val name = extension.name.substring(3)
	val template = name
		.splitToSequence('_')
		.map {
			if (ABBREVIATIONS.contains(it))
				it.toUpperCase()
			else
				"${it[0].toUpperCase()}${it.substring(1)}"
		}
		.joinToString("")

	val file = root.resolve("templates/$name.kt")

	LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
		writer.print(HEADER)
		writer.print("""package $vulkanPackage.templates

import org.lwjgl.generator.*
import $vulkanPackage.*${distinctTypes
			.filterIsInstance<TypeSystem>()
			.map { it.requires }
			.distinct()
			.map { "\nimport ${IMPORTS[it]!!}" }
			.joinToString()
		}

val $name = "$template".nativeClassVK("$name", postfix = ${name.substringBefore('_')}) {
	documentation =
		$QUOTES3
		${EXTENSION_DOC[name] ?: "The ${S}templateName extension."}
		$QUOTES3
""")
		if (extension.require.enums != null) {
			extension.require.enums
				.groupBy { it.extends ?: it.name }
				.forEach {
					if (it.value.singleOrNull()?.value != null) {
						it.value.first().let {
							if (it.value != null) {
								if (it.value.startsWith('\"')) {
									val description = if (it.name.endsWith("_EXTENSION_NAME")) "The extension name." else ""
									writer.println("""
	StringConstant(
		"$description",

		"${it.name.substring(3)}"..${it.value}
	)""")
								} else if (!it.value.startsWith("VK_")) { // skip aliases
									val description = if (it.name.endsWith("_SPEC_VERSION")) "The extension specification version." else ""
									writer.println("""
	${if (it.name.endsWith("_SPEC_VERSION")) "Int" else "Enum"}Constant(
		"$description",

		"${it.name.substring(3)}".."${it.value}"
	)""")
								}
							}
						}
					} else {
						val extends = it.value.firstOrNull { it.extends != null }?.extends
						val enumDoc = ENUM_DOC[it.key]
						writer.println("""
	EnumConstant(
		${if (extends != null) "\"Extends {@code ${it.key}}.\"" else if (enumDoc == null) "\"${it.key}\"" else """"$QUOTES3
		${enumDoc.shortDescription}${
						if (enumDoc.description.isEmpty()) "" else "\n\n\t\t${enumDoc.description}"}${
						if (enumDoc.seeAlso.isEmpty()) "" else "\n\n\t\t${enumDoc.seeAlso}"}
		$QUOTES3"""},

		${it.value.asSequence()
							.map {
								"\"${it.name.substring(3)}\".${if (it.value != null)
									".\"${it.value}\""
								else if (it.offset != null)
									".\"${offsetAsEnum(extension.number, it.offset, it.dir)}\""
								else if (it.bitpos != null)
									"enum(${bitposAsHex(it.bitpos)})"
								else
									throw IllegalStateException()
								}"
							}
							.joinToString(",\n\t\t")}
	)""")
					}
				}
		}

		if (extensionEnums.any())
			writer.printEnums(extensionEnums)

		if (extension.require.commands != null)
			writer.printCommands(extension.require.commands.asSequence(), types, structs, commands)

		writer.print("}")
	}

	return enumsSeen + extensionEnums
}

private fun offsetAsEnum(extension_number: Int, offset: String, dir: String?) =
	(1000000000 + (extension_number - 1) * 1000 + offset.toInt()).let {
		if (dir != null)
			-it
		else
			it
	}

private fun bitposAsHex(bitpos: String) = "0x${Integer.toHexString(1 shl bitpos.toInt()).padStart(8, '0')}"

private fun Set<Type>.filterEnums(enums: Map<String, Enums>) = this.asSequence()
	.filter { it is TypeEnum || it is TypeBitmask }
	.flatMap {
		if (it is TypeBitmask && it.requires != null) {
			sequenceOf(it.name, it.requires)
		} else
			sequenceOf(it.name)
	}
	.distinct()
	.mapNotNull { enums[it] }

private fun PrintWriter.printEnums(enums: Sequence<Enums>) = enums.forEach { block ->
	val enumDoc = ENUM_DOC[block.name]
	println("""
	EnumConstant(
		${if (enumDoc == null) "\"${block.name}\"" else """$QUOTES3
		${enumDoc.shortDescription}${
	if (enumDoc.description.isEmpty()) "" else "\n\n\t\t${enumDoc.description}"}${
	if (enumDoc.seeAlso.isEmpty()) "" else "\n\n\t\t${enumDoc.seeAlso}"}
		$QUOTES3"""},

		${block.enums.map {
		if (it.bitpos != null)
			"\"${it.name.substring(3)}\".enum(${bitposAsHex(it.bitpos)})"
		else
			"\"${it.name.substring(3)}\"..\"${it.value}\""
	}.joinToString(",\n\t\t")}
	)""")
}

private fun PrintWriter.printCommands(
	commandRefs: Sequence<CommandRef>,
	types: Map<String, Type>,
	structs: Map<String, TypeStruct>,
	commands: Map<String, Command>
) {
	commandRefs.forEach {
		val cmd = commands[it.name]!!
		val name = it.name.substring(2)
		val functionDoc = FUNCTION_DOC[name]

		print("\n\t")
		// If we don't have a dispatchable handle, mark ICD-global
		if (cmd.params.none { it.indirection.isEmpty() && types[it.type]!!.let { it is TypeHandle && it.type == "VK_DEFINE_HANDLE" } })
			print("GlobalCommand..")
		println("""${getReturnType(cmd.proto)}(
		"$name",
		${if (functionDoc == null) "\"\"" else """$QUOTES3
		${functionDoc.shortDescription}

		${functionDoc.cSpecification}

		${functionDoc.description}${if (functionDoc.seeAlso == null) "" else """

		${functionDoc.seeAlso}"""}
		$QUOTES3"""}${getParams(
			cmd.proto, cmd.params, types, structs,
			// workaround: const missing from VK_EXT_debug_marker struct params
			forceIN = cmd.cmdbufferlevel != null
		)}
	)""")
	}
}