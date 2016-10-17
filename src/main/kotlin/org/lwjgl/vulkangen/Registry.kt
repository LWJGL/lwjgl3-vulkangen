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

	"VK_ANDROID_native_buffer",

	"VK_NV_external_memory",
	"VK_NV_external_memory_capabilities",
	"VK_NV_external_memory_win32",
	"VK_NV_win32_keyed_mutex"
)

val ABBREVIATIONS = setOf(
	"gcn",
	"glsl",
	"gpu",
	"pvrtc"
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

	val vulkanPath = "org/lwjgl/vulkan2"
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

	//convert(vulkanDocs)

	val types = registry.types.asSequence()
		.associateBy(Type::name)

	val structs = registry.types.asSequence()
		.filterIsInstance(TypeStruct::class.java)
		.associateBy(Type::name)

	val enums = registry.enums.asSequence()
		.associateBy { it.name }

	val commands = registry.commands.asSequence()
		.associateBy { it.proto.name }

	val vulkanPackage = vulkanPath.replace('/', '.')

	// TODO: This must be fixed post Vulkan 1.0. We currently have no other way to identify types used in core only.
	val featureTypes = getDistinctTypes(registry.features[0].requires.asSequence(), commands, types)

	generateTypes(root, vulkanPackage, "VKTypes", types, structs, featureTypes)
	registry.features.forEach { feature ->
		generateFeature(root, vulkanPackage, types, enums, structs, commands, feature, featureTypes)
	}

	val extensions = registry.extensions.asSequence()
		.filter { it.supported != "disabled" && !DISABLED_EXTENSIONS.contains(it.name) }

	val extensionTypes = getDistinctTypes(extensions.map { it.require }, commands, types)
		.filter { !featureTypes.contains(it) }
		.toSet()

	generateTypes(root, vulkanPackage, "ExtensionTypes", types, structs, extensionTypes)
	extensions.forEach { extension ->
		generateExtension(root, vulkanPackage, types, enums, structs, commands, extension)
	}
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
			// recurse into struct types
			.flatMap {
				if (!types.containsKey(it)) {
					println("Ignoring feature type: $it")
					emptySequence<Type>()
				} else {
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
else
	params.asSequence().map { param ->
		val indirection = param.indirection.let {
			if (param.array != null) {
				if (it.isEmpty())
					"_p"
				else
					"${it}p"
			} else
				it
		}

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

		val check = getCheck(param, indirection, structs, forceIN)

		val nullable = if (indirection.isNotEmpty() && (
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

		val paramType = if (forceIN || indirection.isEmpty() || param.modifier == "const")
			"IN"
		else if ("false,true" == param.optional)
			"INOUT"
		else
			"OUT"

		"$autoSize$check$nullable$const$type.$paramType(\"${param.name}\", \"\")"
	}.joinToString(",\n$indent", prefix = ",\n\n$indent")

private fun generateTypes(
	root: Path,
	vulkanPackage: String,
	template: String,
	types: Map<String, Type>,
	structs: Map<String, TypeStruct>,
	templateTypes: Set<Type>
) {
	val file = root.resolve("$template.kt")

	LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
		writer.print(HEADER)
		writer.print("""package $vulkanPackage

import org.lwjgl.generator.*${if (templateTypes.any { it is TypeSystem }) """
import org.lwjgl.system.linux.*
import org.lwjgl.system.windows.*""" else ""}

// Handle types
${templateTypes
			.filterIsInstance(TypeHandle::class.java)
			.map { "val ${it.name} = ${it.type}(\"${it.name}\")" }
			.joinToString("\n")}

// Enum types
${templateTypes
			.filterIsInstance(TypeEnum::class.java)
			.map { "val ${it.name} = \"${it.name}\".enumType" }
			.joinToString("\n")}

// Bitmask types
${templateTypes
			.filterIsInstance(TypeBitmask::class.java)
			.map { "val ${it.name} = typedef(VkFlags, \"${it.name}\")" }
			.joinToString("\n")}

${templateTypes
			.filterIsInstance(TypeFuncpointer::class.java)
			.map {
				"""val ${it.name} = "${it.name}".callback(
	VULKAN_PACKAGE, ${getReturnType(it.proto)}, "${it.name.substring(4).let { "${it[0].toUpperCase()}${it.substring(1)}" }}",
	""${getParams(it.proto, it.params, types, structs, forceIN = true, indent = "\t")}
) {
	documentation =
		$QUOTES3
		$QUOTES3
	useSystemCallConvention()
}"""
			}
			.joinToString("\n\n").let {
			if (it.isNotEmpty())
				"""// Function pointer types
$it

""" else ""
		}}// Struct types
${templateTypes
			.filterIsInstance(TypeStruct::class.java)
			.map {
				"""val ${it.name} = struct(VULKAN_PACKAGE, "${it.name}"${if (it.returnedonly) ", mutable = false" else ""}) {
	documentation =
		$QUOTES3
		$QUOTES3

	${it.members.asSequence()
					.map {
						if (it.name == "sType" && it.type == "VkStructureType" && it.indirection.isEmpty())
							"sType(this)"
						else if (it.name == "pNext" && it.type == "void" && it.indirection == "_p")
							"pNext()"
						else {
							val nullable = if (it.indirection.isNotEmpty() && it.optional != null) "nullable.." else ""

							val const = if (it.modifier == "const") "const.." else ""

							val encoding = if (it.len.contains("null-terminated") || (it.array != null && it.type == "char")) "UTF8" else ""
							val type = if (it.type == "void" && it.indirection == "_p" && it.len.none())
								"voidptr"
							else
								"${it.type}$encoding${if (it.indirection.isNotEmpty() && types[it.type].let { it !is TypePlatform })
									it.indirection.replace('_', '.')
								else
									it.indirection
								}"

							if (it.array == null)
								"$nullable$const$type.member(\"${it.name}\", \"\")"
							else
								"$nullable$const$type.array(\"${it.name}\", \"\", size = ${it.array})"
						}
					}
					.joinToString("\n\t")}
}"""
			}
			.joinToString("\n\n")}
""")
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
	featureTypes: Set<Type>
) {
	val template = "VK${feature.number.substringBefore('.')}${feature.number.substringAfter('.')}"
	val file = root.resolve("templates/$template.kt")

	LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
		writer.print(HEADER)
		writer.print("""package $vulkanPackage.templates

import org.lwjgl.generator.*
import $vulkanPackage.*

val $template = "$template".nativeClass(VULKAN_PACKAGE, "$template", prefix = "VK", binding = VK_BINDING) {
	javaImport(
		"org.lwjgl.vulkan.VK",
		"org.lwjgl.vulkan.VkInstance",
		"org.lwjgl.vulkan.VkPhysicalDevice",
		"org.lwjgl.vulkan.VkDevice",
		"org.lwjgl.vulkan.VkQueue",
		"org.lwjgl.vulkan.VkCommandBuffer"
	)

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

		writer.printEnums(featureTypes.asSequence(), enums)

		feature.requires.asSequence()
			.filter { it.commands != null }
			.forEach {
				writer.println("\n\t// ${it.comment}")
				writer.printCommands(it.commands!!.asSequence(), types, structs, commands)
			}

		writer.println("\n}")
	}
}

private fun generateExtension(
	root: Path,
	vulkanPackage: String,
	types: Map<String, Type>,
	enums: Map<String, Enums>,
	structs: Map<String, TypeStruct>,
	commands: Map<String, Command>,
	extension: Extension
) {
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
import $vulkanPackage.*

val $name = "$template".nativeClassVK("${extension.name}", postfix = ${name.substringBefore('_')}) {
	javaImport(
		"org.lwjgl.vulkan.VK",
		"org.lwjgl.vulkan.VkInstance",
		"org.lwjgl.vulkan.VkPhysicalDevice",
		"org.lwjgl.vulkan.VkDevice",
		"org.lwjgl.vulkan.VkQueue",
		"org.lwjgl.vulkan.VkCommandBuffer"
	)

	documentation =
		$QUOTES3
		$QUOTES3
""")
		extension.require.enums?.forEach {
			if ( it.value != null) {
				if ( it.value.startsWith('\"')) {
					val description = if (it.name.endsWith("_EXTENSION_NAME")) "The extension name." else ""
					writer.println("""
		StringConstant(
			"$description",

			"${it.name}"..${it.value}
		)""")
				} else {
					val description = if (it.name.endsWith("_SPEC_VERSION")) "The extension specification version." else ""
					writer.println("""
		IntConstant(
			"$description",

			"${it.name}".."${it.value}"
		)""")
				}
			}
		}

		if (extension.require.types != null)
			writer.printEnums(extension.require.types.asSequence().map { types[it.name]!! }, enums)

		if (extension.require.commands != null)
			writer.printCommands(extension.require.commands.asSequence(), types, structs, commands)

		writer.println("\n}")
	}
}

private fun PrintWriter.printEnums(types: Sequence<Type>, enums: Map<String, Enums>) {
	types
		.filter { it is TypeEnum || it is TypeBitmask }
		.flatMap {
			if (it is TypeBitmask && it.requires != null)
				sequenceOf(it.name, it.requires)
			else
				sequenceOf(it.name)
		}
		.distinct()
		.mapNotNull { enums[it] }
		.forEach { block ->
			println("""
	EnumConstant(
		"${block.name}",

		${block.enums.map {
				if (it.bitpos != null)
					"\"${it.name.substring(3)}\".enum(${if (it.comment != null)
						"$QUOTES3${it.comment}$QUOTES3" else "\"\""
					}, 0x${Integer.toHexString(1 shl it.bitpos.toInt()).padStart(8, '0')})"
				else
					"\"${it.name.substring(3)}\".enum(${if (it.comment != null)
						"$QUOTES3${it.comment}$QUOTES3" else "\"\""
					}, \"${it.value}\")"
			}.joinToString(",\n\t\t")}
	)""")
		}
}

private fun PrintWriter.printCommands(
	commandRefs: Sequence<CommandRef>,
	types: Map<String, Type>,
	structs: Map<String, TypeStruct>,
	commands: Map<String, Command>
) {
	commandRefs.forEach {
		val cmd = commands[it.name]!!
		print("\n\t")
		// If we don't have a dispatchable handle, mark ICD-global
		if (cmd.params.none { it.indirection.isEmpty() && types[it.type]!!.let { it is TypeHandle && it.type == "VK_DEFINE_HANDLE" } })
			print("GlobalCommand..")
		println("""${getReturnType(cmd.proto)}(
		"${cmd.proto.name.substring(2)}",
		""${getParams(cmd.proto, cmd.params, types, structs)}
	)""")
	}
}