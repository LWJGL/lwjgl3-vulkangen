package org.lwjgl.vulkangen

import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.converters.Converter
import com.thoughtworks.xstream.converters.MarshallingContext
import com.thoughtworks.xstream.converters.UnmarshallingContext
import com.thoughtworks.xstream.io.HierarchicalStreamReader
import com.thoughtworks.xstream.io.HierarchicalStreamWriter
import com.thoughtworks.xstream.io.xml.Xpp3Driver
import java.nio.file.Path
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
	val attribs: MutableMap<String, String>
) {
	val len: Sequence<String> get() = attribs["len"].let {
		if (it == null)
			emptySequence<String>()
		else
			it.splitToSequence(",")
	}

	val optional: String? get() = attribs["optional"]
	val externsync: String? get() = attribs["externsync"]
	val noautovalidity: String? get() = attribs["noautovalidity"]
	val validextensionstructs: String? get() = attribs["validextensionstructs"]
}

internal class Validity

internal class ImplicitExternSyncParams(
	val params: List<Field>
)

internal class Command(
	val successcodes: String?,
	val errorcodes: String?,
	val queues: String?,
	val renderpass: String?,
	val cmdbufferlevel: String?,
	val proto: Field,
	val params: List<Field>,
	val validity: Validity?,
	val implicitexternsyncparams: ImplicitExternSyncParams?
)

internal class TypeRef(val name: String)
internal class EnumRef(
	val name: String,
	val value: String?,
	val bitpos: String?,
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
			.associateTo(HashMap()) { it to reader.getAttribute(it) }

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
			else if (it.startsWith('[')) {
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
			"basetype"    -> {
				reader.moveDown()
				val type = reader.value
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeBase(type, name)
			}
			"bitmask"     -> {
				val requires = reader.getAttribute("requires")

				reader.moveDown()
				// VkFlags
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeBitmask(requires, name)
			}
			"handle"      -> {
				val parent = reader.getAttribute("parent")

				reader.moveDown()
				val type = reader.value
				reader.moveUp()

				reader.moveDown()
				val name = reader.value
				reader.moveUp()

				TypeHandle(parent, type, name)
			}
			"enum"        -> {
				TypeEnum(reader.getAttribute("name"))
			}
			"funcpointer" -> {
				val proto = reader.let {
					val (modifier, type, indirection) = FUNC_POINTER_RETURN_TYPE_REGEX.find(it.value)!!.destructured
					it.moveDown()
					val name = it.value
					it.moveUp()

					Field(modifier, type, indirection.indirection, name, null, HashMap())
				}

				if (proto.name == "PFN_vkVoidFunction")
					TypeIgnored
				else {
					val params = ArrayList<Field>()
					while (reader.hasMoreChildren()) {
						val (modifier) = FUNC_POINTER_PARAM_MOD_REGEX.find(reader.value)!!.destructured

						val type = StringBuilder()
						reader.moveDown()
						type.append(reader.value)
						reader.moveUp()

						val (indirection, paramName) = FUNC_POINTER_PARAM_NAME_REGEX.find(reader.value)!!.destructured

						params.add(Field(modifier, type.toString(), indirection.indirection, paramName, null, HashMap()))
					}

					TypeFuncpointer(proto, params)
				}
			}
			"union",
			"struct"      -> {
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
			else          -> TypeIgnored
		}
	}

	override fun canConvert(type: Class<*>?): Boolean = type == Type::class.java
}

internal fun parse(registry: Path) = XStream(Xpp3Driver()).let { xs ->
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
		xs.useAttributeFor(it, "queues")
		xs.useAttributeFor(it, "renderpass")
		xs.useAttributeFor(it, "cmdbufferlevel")
	}

	Field::class.java.let {
		xs.registerConverter(FieldConverter())

		xs.alias("proto", it)
		xs.addImplicitCollection(Command::class.java, "params", "param", it)
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
		xs.useAttributeFor(it, "bitpos")
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