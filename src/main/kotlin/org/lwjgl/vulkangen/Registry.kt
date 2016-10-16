/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.converters.Converter
import com.thoughtworks.xstream.converters.MarshallingContext
import com.thoughtworks.xstream.converters.UnmarshallingContext
import com.thoughtworks.xstream.io.HierarchicalStreamReader
import com.thoughtworks.xstream.io.HierarchicalStreamWriter
import com.thoughtworks.xstream.io.xml.Xpp3Driver
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

internal class VendorID(
	val name: String,
	val id: String,
	val comment: String
)

internal class Tag(
	val name: String,
	val author: String,
	val contact: String
)

internal abstract class Type(val name: String)

internal object TypeIgnored : Type("<IGNORED>")

internal class TypeSystem(
	val requires: String,
	name: String
) : Type(name)

internal class TypeBase(
	val type: String,
	name: String
) : Type(name)

internal class TypePlatform(name: String) : Type(name)

internal class TypeBitmask(
	val requires: String?,
	name: String
) : Type(name)

internal class TypeHandle(
	val parent: String?,
	val type: String,
	name: String
) : Type(name)

internal class TypeEnum(name: String) : Type(name)

internal class TypeFuncpointer(
	val proto: Field,
	val params: List<Field>
) : Type(proto.name)

internal class TypeStruct(
	val type: String, // struct or union
	name: String,
	val returnedonly: Boolean,
	val members: List<Field>
) : Type(name)

internal class Enum(
	val name: String,
	val bitpos: String?,
	val value: String?,
	val comment: String?
)

internal class Unused(val start: String)

internal class Enums(
	val name: String,
	val type: String?,
	val comment: String?,
	val enums: List<Enum>,
	val unused: Unused?
)

internal class Field(
	val modifier: String,
	val type: String,
	val indirection: String,
	val name: String,
	val array: String?,
	val attribs: Map<String, String>
) {
	val len = attribs["len"].let {
		if (it == null)
			emptySequence<String>()
		else
			it.splitToSequence(",")
	}

	val optional: String? = attribs["optional"]
	val externsync: String? = attribs["externsync"]
	val noautovalidity: String? = attribs["noautovalidity"]
	val validextensionstructs: String? = attribs["validextensionstructs"]
}

internal class Validity

internal class ImplicitExternSyncParams(
	val params: List<Field>
)

internal class Command(
	val successcodes: String,
	val errorcodes: String,
	val proto: Field,
	val params: List<Field>,
	val validity: Validity?,
	val implicitexternsyncparams: ImplicitExternSyncParams?
)

internal class TypeRef(val name: String)
internal class EnumRef(
	val name: String,
	val value: String?,
	val offset: String?,
	val dir: String?,
	val extends: String?
)

internal class CommandRef(val name: String)

internal class Require(
	val comment: String,
	val types: List<TypeRef>?,
	val enums: List<EnumRef>?,
	val commands: List<CommandRef>?
)

internal class Feature(
	val api: String,
	val name: String,
	val number: String,
	val requires: List<Require>
)

internal class Extension(
	val name: String,
	val number: Int,
	val type: String,
	val supported: String,
	val require: Require
)

internal class Registry(
	val comment: String,
	val vendorids: List<VendorID>,
	val tags: List<Tag>,
	val types: List<Type>,
	val enums: List<Enums>,
	val commands: List<Command>,
	val features: List<Feature>,
	val extensions: List<Extension>
)

private val INDIRECTION_REGEX = Regex("""([*]+)(?:\s+const\s*([*]+))?""")

private val String.indirection: String get() = if (this.isEmpty())
	this
else {
	val (p, const_p) = INDIRECTION_REGEX.matchEntire(this)!!.destructured
	"${p.indirection("_")}${if (const_p.isEmpty()) "" else const_p.indirection("_const_")}"
}

private fun String.indirection(prefix: String) = this.length
	.downTo(1)
	.asSequence(

	).map { "p" }
	.joinToString("", prefix = prefix)

internal class FieldConverter : Converter {
	override fun marshal(source: Any, writer: HierarchicalStreamWriter, context: MarshallingContext) {
		TODO()
	}

	override fun unmarshal(reader: HierarchicalStreamReader, context: UnmarshallingContext): Any {
		val attribs = reader.attributeNames.asSequence()
			.map(Any?::toString)
			.associate { it to reader.getAttribute(it) }

		val modifier = reader.value.trim()

		if (!reader.hasMoreChildren())
			return Field("", "N/A", "", modifier, null, attribs)

		val type = StringBuilder()
		reader.moveDown()
		type.append(reader.value)
		reader.moveUp()
		val indirection = reader.value.trim().indirection
		reader.moveDown()
		val name = reader.value
		reader.moveUp()
		val array = reader.value.trim().let {
			if (it.isEmpty())
				null
			else if (it.startsWith('[') ) {
				if (reader.hasMoreChildren()) {
					try {
						reader.moveDown()
						"\"${reader.value}\""
					} finally {
						reader.moveUp()
						if (reader.hasMoreChildren() || reader.value != "]")
							throw IllegalStateException()
					}
				} else if (it.endsWith(']'))
					it.substring(1, it.length - 1)
				else
					throw IllegalStateException()
			} else
				throw IllegalStateException(it)
		}

		return Field(modifier, type.toString(), indirection, name, array, attribs)
	}

	override fun canConvert(type: Class<*>?): Boolean = type === Field::class.java
}

internal class TypeConverter : Converter {
	companion object {
		private val FIELD_CONVERTED = FieldConverter()

		private val FUNC_POINTER_RETURN_TYPE_REGEX = Regex("""typedef\s+(?:(const|enum|struct)\s+)?(\w+)\s*([*]*)\s*[(]""")
		private val FUNC_POINTER_PARAM_MOD_REGEX = Regex("""[(,]\s*(\w+)?""")
		private val FUNC_POINTER_PARAM_NAME_REGEX = Regex("""\s*([*]*)\s*(\w+)\s*[,)]""")
	}

	override fun marshal(source: Any, writer: HierarchicalStreamWriter, context: MarshallingContext) {
		TODO()
	}

	override fun unmarshal(reader: HierarchicalStreamReader, context: UnmarshallingContext): Any? {
		val category = reader.getAttribute("category")
		if (category == null) {
			val name = reader.getAttribute("name")
			val requires = reader.getAttribute("requires")
			return if (name != null && requires != null) {
				if ("vk_platform" == requires)
					TypePlatform(name)
				else
					TypeSystem(requires, name)
			} else
				TypeIgnored
		}

		return when (category) {
			"basetype" -> {
				reader.moveDown()
				val type = reader.value
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeBase(type, name)
			}
			"bitmask" -> {
				val requires = reader.getAttribute("requires")

				reader.moveDown()
				// VkFlags
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeBitmask(requires, name)
			}
			"handle" -> {
				val parent = reader.getAttribute("parent")

				reader.moveDown()
				val type = reader.value
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeHandle(parent, type, name)
			}
			"enum" -> { TypeEnum(reader.getAttribute("name")) }
			"funcpointer" -> {
				val proto = reader.let {
					val (modifier, type, indirection) = FUNC_POINTER_RETURN_TYPE_REGEX.find(it.value)!!.destructured
					it.moveDown()
					val name = it.value
					it.moveUp()

					Field(modifier, type, indirection.indirection, name, null, emptyMap())
				}

				val params = ArrayList<Field>()
				while (reader.hasMoreChildren()) {
					val (modifier) = FUNC_POINTER_PARAM_MOD_REGEX.find(reader.value)!!.destructured

					val type = StringBuilder()
					reader.moveDown()
					type.append(reader.value)
					reader.moveUp()

					val (indirection, paramName) = FUNC_POINTER_PARAM_NAME_REGEX.find(reader.value)!!.destructured

					params.add(Field(modifier, type.toString(), indirection.indirection, paramName, null, emptyMap()))
				}

				TypeFuncpointer(proto, params)
			}
			"union",
			"struct" -> {
				val name = reader.getAttribute("name")
				val returnedonly = reader.getAttribute("returnedonly") != null

				val members = ArrayList<Field>()
				while (reader.hasMoreChildren()) {
					reader.moveDown()
					if (reader.nodeName == "member")
						members.add(FIELD_CONVERTED.unmarshal(reader, context) as Field)
					reader.moveUp()
				}

				TypeStruct(category, name, returnedonly, members)
			}
			else -> TypeIgnored
		}
	}

	override fun canConvert(type: Class<*>?): Boolean = type == Type::class.java
}

private fun parse(registry: Path) = XStream(Xpp3Driver()).let { xs ->
	xs.alias("registry", Registry::class.java)

	VendorID::class.java.let {
		xs.alias("vendorid", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "id")
		xs.useAttributeFor(it, "comment")
	}

	Tag::class.java.let {
		xs.alias("tag", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "author")
		xs.useAttributeFor(it, "contact")
	}

	Type::class.java.let {
		xs.registerConverter(TypeConverter())

		xs.alias("type", it)
	}

	Enums::class.java.let {
		xs.addImplicitCollection(Registry::class.java, "enums", "enums", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "type")
		xs.useAttributeFor(it, "comment")
	}

	Enum::class.java.let {
		xs.addImplicitCollection(Enums::class.java, "enums", "enum", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "bitpos")
		xs.useAttributeFor(it, "value")
		xs.useAttributeFor(it, "comment")
	}

	Command::class.java.let {
		xs.alias("command", it)
		xs.useAttributeFor(it, "successcodes")
		xs.useAttributeFor(it, "errorcodes")
	}

	Field::class.java.let {
		xs.registerConverter(FieldConverter())

		xs.alias("proto", it)
		xs.addImplicitCollection(Command::class.java, "params", "param", it)

		xs.useAttributeFor(it, "optional")
		xs.useAttributeFor(it, "len")
		xs.useAttributeFor(it, "noautovalidity")
		xs.useAttributeFor(it, "externsync")
	}

	xs.alias("validity", Validity::class.java)
	xs.addImplicitCollection(ImplicitExternSyncParams::class.java, "params", "param", Field::class.java)

	Feature::class.java.let {
		xs.addImplicitCollection(Registry::class.java, "features", "feature", it)
		xs.useAttributeFor(it, "api")
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "number")
	}

	Require::class.java.let {
		xs.addImplicitCollection(Feature::class.java, "requires", "require", it)
		xs.useAttributeFor(it, "comment")
	}

	TypeRef::class.java.let {
		xs.addImplicitCollection(Require::class.java, "types", "type", it)
		xs.useAttributeFor(it, "name")
	}

	EnumRef::class.java.let {
		xs.addImplicitCollection(Require::class.java, "enums", "enum", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "value")
		xs.useAttributeFor(it, "offset")
		xs.useAttributeFor(it, "dir")
		xs.useAttributeFor(it, "extends")
	}

	CommandRef::class.java.let {
		xs.addImplicitCollection(Require::class.java, "commands", "command", it)
		xs.useAttributeFor(it, "name")
	}

	Extension::class.java.let {
		xs.alias("extension", it)
		xs.useAttributeFor(it, "name")
		xs.useAttributeFor(it, "number")
		xs.useAttributeFor(it, "type")
		xs.useAttributeFor(it, "supported")
	}

	xs
}.fromXML(registry.toFile()) as Registry

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
		.withIndex()
		.associateBy { it.value.name }

	val commands = registry.commands.asSequence()
		.associateBy { it.proto.name }

	val extensionTypes = registry.extensions.asSequence()
		.mapNotNull { it.require.types }
		.flatMap { it.asSequence().map { it.name } }
		.toSet()

	// TODO: This must be fixed post Vulkan 1.0. We currently have no other way to identify types used in core only.
	val featureTypes = getDistinctTypes(registry.features[0].requires.asSequence(), commands, types)

	val vulkanPackage = vulkanPath.replace('/', '.')
	run {
		val template = "VKTypes"
		val file = root.resolve("$template.kt")

		LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
			writer.print(HEADER)
			writer.print("""package $vulkanPackage

import org.lwjgl.generator.*

// Handle types
${featureTypes
				.filterIsInstance(TypeHandle::class.java)
				.map { "val ${it.name} = ${it.type}(\"${it.name}\")" }
				.joinToString("\n")}

// Enum types
${featureTypes
				.filterIsInstance(TypeEnum::class.java)
				.map { "val ${it.name} = \"${it.name}\".enumType" }
				.joinToString("\n")}

// Bitmask types
${featureTypes
				.filterIsInstance(TypeBitmask::class.java)
				.map { "val ${it.name} = typedef(VkFlags, \"${it.name}\")" }
				.joinToString("\n")}

// Function pointer types
${featureTypes
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
				.joinToString("\n\n")}

// Struct types
${featureTypes
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
}.nativeType"""
				}
				.joinToString("\n\n")}
""")
		}
	}

	registry.features.forEach { feature ->
		val template = "VK${feature.number.substringBefore('.')}${feature.number.substringAfter('.')}"
		val file = root.resolve("templates/$template.kt")

		LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
			writer.print(HEADER)
			// TODO:
			writer.print("""package $vulkanPackage.templates

import org.lwjgl.generator.*
import $vulkanPackage.*

val $template = "$template".nativeClass(VULKAN_PACKAGE, "$template", prefix = "VK", binding = VK_BINDING) {
	documentation =
		$QUOTES3
		The core Vulkan ${feature.number} functionality.
		$QUOTES3
""")
			// Enums

			(
				featureTypes.asSequence()
					.filter { it is TypeEnum || it is TypeBitmask }
					.mapNotNull { enums[it.name] } +
					featureTypes.asSequence()
						.mapNotNull { if (it is TypeBitmask && it.requires != null) enums[it.requires] else null }
				)
				.forEach {
					val block = it.value

					writer.println("""
	EnumConstant(
		"${block.name} (${it.javaClass})",

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

			feature.requires.asSequence()
				.filter { it.commands != null }
				.forEach {
					writer.println("\n\t// ${it.comment}")
					it.commands!!.forEach {
						val cmd = commands[it.name]!!
						writer.print("\n\t")
						// If we don't have a dispatchable handle, mark ICD-global
						if (cmd.params.none { it.indirection.isEmpty() && types[it.type]!!.let { it is TypeHandle && it.type == "VK_DEFINE_HANDLE" } })
							writer.print("GlobalCommand..")
						writer.println("""${getReturnType(cmd.proto)}(
		"${cmd.proto.name.substring(2)}",
		""${getParams(cmd.proto, cmd.params, types, structs)}
	)""")
					}
				}

			writer.println("\n}")
		}
	}

}

private fun getDistinctTypes(name: String, types: Map<String, Type>): Sequence<Type> {
	val type = types[name]!!
	return if (type is TypeStruct)
		sequenceOf(type) + type.members.asSequence().flatMap { getDistinctTypes(it.type, types) }
	else if (type is TypeFuncpointer)
		getDistinctTypes(type.proto.type, types) + sequenceOf(type) + type.params.asSequence().flatMap { getDistinctTypes(it.type, types) }
	else
		sequenceOf(type)
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

private fun getReturnType(proto: Field): String = proto.let { "${if (it.modifier == "const") "const.." else ""}${it.type}${it.indirection}" }

private fun getCheck(param: Field, structs: Map<String, TypeStruct>, forceIN: Boolean): String {
	if (param.indirection.isNotEmpty()) { // pointer
		if (param.len.none()) { // not auto-sized
			if (!forceIN && param.modifier != "const" && !structs.containsKey(param.type)) // output, non-struct
				return "Check(1).."
		} else if (param.array != null) {
			return "Check($param.array).."
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
		val autoSize = params.asSequence()
			.filter { it.len.contains(param.name) }
			.map { it.name }
			.joinToString(",")
			.let {
				if (it.isEmpty())
					""
				else
					"AutoSize(\"$it\").."
			}

		val check = getCheck(param, structs, forceIN)

		val nullable = if (param.indirection.isNotEmpty() && (
			// the parameter is optional
			"true" == param.optional ||
				// the AutoSize param is optional
				param.len.any() && params.any { param.len.contains(it.name) && "true" == it.optional })
		) "nullable.." else ""

		val const = if (param.modifier == "const") "const.." else ""

		val encoding = if (param.len.contains("null-terminated") || (param.array != null && param.type == "char")) {
			if (returns.type == "PFN_vkVoidFunction") "ASCII" else "UTF8"
		} else ""
		val type = if (param.type == "void" && param.indirection == "_p" && param.len.none() && check.isEmpty())
			"voidptr"
		else
			"${param.type}$encoding${if (param.indirection.isNotEmpty() && types[param.type].let { it !is TypePlatform })
				param.indirection.replace('_', '.')
			else
				param.indirection
			}"

		val paramType = if (forceIN || param.indirection.isEmpty() || param.modifier == "const")
			"IN"
		else if ("false,true" == param.optional)
			"INOUT"
		else
			"OUT"

		"$autoSize$check$nullable$const$type.$paramType(\"${param.name}\", \"\")"
	}.joinToString(",\n$indent", prefix = ",\n\n$indent")