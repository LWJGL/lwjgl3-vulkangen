package org.lwjgl.vulkangen

import com.thoughtworks.xstream.*
import com.thoughtworks.xstream.converters.*
import com.thoughtworks.xstream.io.*
import com.thoughtworks.xstream.io.xml.*
import java.nio.charset.*
import java.nio.file.*

internal class Platform(
    val name: String,
    val protect: String,
    val comment: String
)

internal class Tag(
    val name: String,
    val author: String,
    val contact: String
)

internal abstract class Type(val api: String?, val name: String)

internal object TypeIgnored : Type(null, "<IGNORED>")

internal class TypeSystem(
    val requires: String,
    api: String?,
    name: String
) : Type(api, name)

internal class TypeBase(
    val type: String,
    api: String?,
    name: String
) : Type(api, name)

internal class TypePlatform(api: String?, name: String) : Type(api, name)

internal class TypeBitmask(
    val requires: String?,
    val typedef: String,
    api: String?,
    name: String
) : Type(api, name)

internal class TypeHandle(
    val parent: String?,
    val type: String,
    api: String?,
    name: String
) : Type(api, name)

internal class TypeEnum(api: String?, name: String, val alias: String?) : Type(api, name)

internal interface Function {
    val proto: Field
    val params: MutableList<Field>
}

internal class TypeFuncpointer(
    api: String?,
    override val proto: Field,
    override val params: MutableList<Field>
) : Type(api, proto.name), Function // TODO

internal class TypeStruct(
    val type: String, // struct or union
    api: String?,
    name: String,
    val returnedonly: Boolean,
    val structextends: List<String>?,
    val members: MutableList<Field>,
    val alias: String?,
    val parentstruct: String?
) : Type(api, name)

internal class Enum(
    val api: String?,
    val name: String,
    val alias: String?,
    val value: String?,
    val bitpos: String?,
    val extnumber: Int?,
    val offset: String?,
    val dir: String?,
    val extends: String?,
    val comment: String?
)

internal class Unused(val start: String)

internal class Enums(
    val name: String,
    val type: String?,
    val bitwidth: Int?,
    val comment: String?,
    val enums: MutableList<Enum>?,
    val unused: Unused?
)

internal class Field(
    val modifier: String,
    val type: String,
    val indirection: String,
    val name: String,
    val bits: Int?,
    val array: String?,
    val attribs: MutableMap<String, String>
) {
    val len: Sequence<String> get() = attribs["len"].let {
        it?.splitToSequence(",") ?: emptySequence()
    }

    val api: String? get() = attribs["api"]
    val optional: String? get() = attribs["optional"]
    val values: String? get() = attribs["values"]
    val externsync: String? get() = attribs["externsync"]
    val noautovalidity: String? get() = attribs["noautovalidity"]
    //val validextensionstructs: String? get() = attribs["validextensionstructs"]
}

internal class Validity

internal class ImplicitExternSyncParams(
    val params: MutableList<Field>
)

internal class Command(
    val api: String?,
    val name: String?,
    val alias: String?,
    val successcodes: String?,
    val errorcodes: String?,
    val queues: String?,
    val renderpass: String?,
    val cmdbufferlevel: String?,
    override val proto: Field,
    override val params: MutableList<Field>,
    val validity: Validity?,
    val implicitexternsyncparams: ImplicitExternSyncParams?
) : Function

internal class TypeRef(val name: String)

internal data class CommandRef(val name: String)

internal class Require(
    val api: String?,
    val comment: String?,
    val depends: String?,
    val types: MutableList<TypeRef>?,
    val enums: MutableList<Enum>?,
    val commands: MutableList<CommandRef>?
)

internal class Remove(
    val comment: String?,
    val types: List<TypeRef>?,
    val enums: List<Enum>?,
    val commands: List<CommandRef>?
)

internal class Feature(
    val api: String,
    val name: String,
    val number: String,
    val requires: MutableList<Require>,
    val removes: MutableList<Remove>
)

internal class Extension(
    val name: String,
    val number: Int,
    val type: String,
    val depends: String?,
    val supported: String,
    val platform: String?,
    val promotedto: String?,
    val deprecatedby: String?,
    val obsoletedby: String?,
    val requires: MutableList<Require>
)

internal class Enable(
    val version: String?,
    val extension: String?,
    val struct: String?,
    val feature: String?,
    val requires: String?
)

internal class Registry(
    val platforms: MutableList<Platform>,
    val tags: MutableList<Tag>,
    val types: MutableList<Type>,
    val enums: MutableList<Enums>,
    val commands: MutableList<Command>,
    val features: MutableList<Feature>,
    val extensions: MutableList<Extension>
)

private val INDIRECTION_REGEX = Regex("""([*]+)(?:\s+const\s*([*]+))?""")

private val String.indirection: String get() = if (this.isEmpty())
    this
else {
    val (p, const_p) = INDIRECTION_REGEX.matchEntire(this)!!.destructured
    "${p.indirection(".")}${if (const_p.isEmpty()) "" else const_p.indirection(".const.")}"
}

private fun String.indirection(prefix: String) = this.length
    .downTo(1)
    .asSequence()
    .map { "p" }
    .joinToString(".", prefix = prefix)

private val VK_VERSION_REGEX = "VK_VERSION_(\\d+)_(\\d+)".toRegex()
private fun parseDependExpression(name: String, wrap: Boolean) = (VK_VERSION_REGEX
    .matchEntire(name)
    ?.let {
        val (major, minor) = it.destructured
        "Vulkan$major$minor"
    } ?: name).let {
        if (wrap) "ext.contains(\"$it\")" else it
    }

internal fun parseDepends(depends: String): String {
    val dependencies = depends.split(',')
    return dependencies
        .asSequence()
        .map { dependency ->
            if (dependency.startsWith('(')) {
                "(${parseDepends(dependency.substring(1, dependency.length - 1))})"
            } else if (dependency.contains('+')) {
                dependency
                    .splitToSequence('+')
                    .map { parseDependExpression(it, true) }
                    .joinToString(" && ", prefix = "(", postfix = ")")
            } else {
                parseDependExpression(dependency, dependencies.size != 1)
            }
        }
        .joinToString(" || ")
}

internal class FieldConverter : Converter {
    override fun marshal(source: Any, writer: HierarchicalStreamWriter, context: MarshallingContext) {
        TODO()
    }

    private val MODIFIER_STRUCT_REGEX = "\\s*struct(?=\\s|$)".toRegex()
    override fun unmarshal(reader: HierarchicalStreamReader, context: UnmarshallingContext): Any {
        val attribs = reader.attributeNames.asSequence()
            .map(Any?::toString)
            .associateWithTo(HashMap()) { reader.getAttribute(it) }

        val modifier = reader.value.trim().replace(MODIFIER_STRUCT_REGEX, "")

        if (!reader.hasMoreChildren())
            return Field("", "N/A", "", modifier, null, null, attribs)

        val type = StringBuilder()
        reader.moveDown()
        type.append(reader.value)
        reader.moveUp()
        val indirection = reader.value.trim().indirection
        reader.moveDown()
        val name = reader.value
        reader.moveUp()

        var bits: Int? = null
        var array: String? = null
        reader.value.trim().let {
            when {
                it.isEmpty()       -> {}
                it.startsWith(':') -> {
                    bits = it.substring(1).toInt()
                    check(!reader.hasMoreChildren())
                }
                it.startsWith('[') ->
                    when {
                        reader.hasMoreChildren() -> {
                            reader.moveDown()
                            array = "\"${reader.value}\""
                            reader.moveUp()
                            check(!reader.hasMoreChildren() && reader.value == "]")
                        }
                        it.endsWith(']')         -> array = it.substring(1, it.length - 1)
                        else                     -> throw IllegalStateException()
                    }
                else               -> throw IllegalStateException(it)
            }
        }

        return Field(modifier, type.toString(), indirection, name, bits, array, attribs)
    }

    override fun canConvert(type: Class<*>?): Boolean = type === Field::class.java
}

private class RegistryMap {

    val bitmasks = HashMap<String, TypeBitmask>()
    val handles = HashMap<String, TypeHandle>()
    val structs = HashMap<String, TypeStruct>()

}

private val UnmarshallingContext.registryMap: RegistryMap
    get() {
        var map = this["registryMap"] as RegistryMap?
        if (map == null) {
            map = RegistryMap()
            this.put("registryMap", map)
        }
        return map
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

    override fun unmarshal(reader: HierarchicalStreamReader, context: UnmarshallingContext): Any {
        val category = reader.getAttribute("category")
        if (category == null) {
            val api = reader.getAttribute("api")
            val name = reader.getAttribute("name")
            val requires = reader.getAttribute("requires")
            return if (name != null && requires != null) {
                if ("vk_platform" == requires)
                    TypePlatform(api, name)
                else
                    TypeSystem(requires, api, name)
            } else
                TypeIgnored
        }

        return when (category) {
            "define"      -> {
                if (reader.getAttribute("name") != null) {
                    TypeIgnored
                } else {
                    val api = reader.getAttribute("api")
                    reader.moveDown()
                    val name = reader.value
                    reader.moveUp()
                    if (name.startsWith("VK_")) {
                        TypeIgnored
                    } else {
                        TypePlatform(api, name)
                    }
                }
            }
            "basetype"    -> {
                val api = reader.getAttribute("api")
                reader.moveDown()
                val type = if (reader.nodeName == "type") {
                    try {
                        reader.value
                    } finally {
                        reader.moveUp()
                        reader.moveDown()
                    }
                } else {
                    "opaque"
                }

                val name = reader.value
                reader.moveUp()

                TypeBase(type, api, name)
            }
            "bitmask"     -> {
                val requires = reader.getAttribute("requires")

                val api = reader.getAttribute("api")
                var name = reader.getAttribute("name")
                val t = if (name == null) {
                    reader.moveDown()
                    val typedef = reader.value // e.g. VkFlags
                    reader.moveUp()

                    reader.moveDown()
                    name = reader.value
                    reader.moveUp()

                    TypeBitmask(requires, typedef, api, name)
                } else {
                    val ref = context.registryMap.bitmasks[reader.getAttribute("alias")]!!
                    TypeBitmask(ref.requires, ref.typedef, api, name)
                }
                context.registryMap.bitmasks[name] = t
                t
            }
            "handle"      -> {
                val parent = reader.getAttribute("parent")

                val api = reader.getAttribute("api")
                var name = reader.getAttribute("name")
                val t = if (name == null) {
                    reader.moveDown()
                    val type = reader.value
                    reader.moveUp()

                    reader.moveDown()
                    name = reader.value
                    reader.moveUp()

                    TypeHandle(parent, type, api, name)
                } else {
                    val ref = context.registryMap.handles[reader.getAttribute("alias")]!!
                    TypeHandle(ref.parent, ref.type, api, name)
                }
                context.registryMap.handles[name] = t
                t
            }
            "enum"        -> {
                TypeEnum(reader.getAttribute("api"), reader.getAttribute("name"), reader.getAttribute("alias"))
            }
            "funcpointer" -> {
                val api = reader.getAttribute("api")
                val proto = reader.let {
                    val (modifier, type, indirection) = FUNC_POINTER_RETURN_TYPE_REGEX.find(it.value)!!.destructured
                    it.moveDown()
                    val name = it.value
                    it.moveUp()

                    Field(modifier, type, indirection.indirection, name, null, null, HashMap())
                }

                if (OPAQUE_PFN_TYPES.contains(proto.name))
                    TypePlatform(api, proto.name)
                else {
                    val params = ArrayList<Field>()
                    while (reader.hasMoreChildren()) {
                        val (modifier) = FUNC_POINTER_PARAM_MOD_REGEX.find(reader.value)!!.destructured

                        val type = StringBuilder()
                        reader.moveDown()
                        type.append(reader.value)
                        reader.moveUp()

                        val (indirection, paramName) = FUNC_POINTER_PARAM_NAME_REGEX.find(reader.value)!!.destructured

                        params.add(Field(modifier, type.toString(), indirection.indirection, paramName, null, null, HashMap()))
                    }

                    TypeFuncpointer(api, proto, params)
                }
            }
            "union",
            "struct"      -> {
                val alias = reader.getAttribute("alias")
                val api = reader.getAttribute("api")
                val name = reader.getAttribute("name")
                val parentstruct = reader.getAttribute("parentstruct")

                val t = if (alias == null) {
                    val returnedonly = reader.getAttribute("returnedonly") != null
                    val structextends = reader.getAttribute("structextends")?.split(",")

                    val members = ArrayList<Field>()
                    while (reader.hasMoreChildren()) {
                        reader.moveDown()
                        if (reader.nodeName == "member")
                            members.add(FIELD_CONVERTED.unmarshal(reader, context) as Field)
                        reader.moveUp()
                    }

                    TypeStruct(category, api, name, returnedonly, structextends, members, null, parentstruct)
                } else {
                    val ref = context.registryMap.structs[alias]
                    if (ref != null) {
                        return TypeStruct(ref.type, api, name, ref.returnedonly, ref.structextends, ref.members, alias, parentstruct)
                    } else {
                        throw IllegalStateException("Struct reference not found: $alias for struct $name")
                    }
                }
                context.registryMap.structs[name] = t
                t
            }
            else          -> TypeIgnored
        }
    }

    override fun canConvert(type: Class<*>?): Boolean = type == Type::class.java
}

internal fun parse(registry: Path) = XStream(Xpp3Driver()).let { xs ->
    xs.allowTypesByWildcard(arrayOf("org.lwjgl.vulkangen.*"))

    xs.alias("registry", Registry::class.java)
    xs.ignoreUnknownElements()

    Platform::class.java.let {
        xs.alias("platform", it)
        xs.useAttributeFor(it, "name")
        xs.useAttributeFor(it, "protect")
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
        xs.useAttributeFor(it, "bitwidth")
        xs.useAttributeFor(it, "comment")
    }

    Enum::class.java.let {
        xs.addImplicitCollection(Enums::class.java, "enums", "enum", it)
        xs.addImplicitCollection(Require::class.java, "enums", "enum", it)
        xs.addImplicitCollection(Remove::class.java, "enums", "enum", it)
        xs.useAttributeFor(it, "api")
        xs.useAttributeFor(it, "name")
        xs.useAttributeFor(it, "alias")
        xs.useAttributeFor(it, "value")
        xs.useAttributeFor(it, "bitpos")
        xs.useAttributeFor(it, "extnumber")
        xs.useAttributeFor(it, "offset")
        xs.useAttributeFor(it, "dir")
        xs.useAttributeFor(it, "extends")
        xs.useAttributeFor(it, "comment")
    }

    Command::class.java.let {
        xs.alias("command", it)
        xs.useAttributeFor(it, "api")
        xs.useAttributeFor(it, "name")
        xs.useAttributeFor(it, "alias")
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

    Remove::class.java.let {
        xs.addImplicitCollection(Feature::class.java, "removes", "remove", it)
        xs.useAttributeFor(it, "comment")
    }

    Feature::class.java.let {
        xs.addImplicitCollection(Registry::class.java, "features", "feature", it)
        xs.useAttributeFor(it, "api")
        xs.useAttributeFor(it, "name")
        xs.useAttributeFor(it, "number")
    }

    Require::class.java.let {
        xs.addImplicitCollection(Feature::class.java, "requires", "require", it)
        xs.addImplicitCollection(Extension::class.java, "requires", "require", it)
        xs.useAttributeFor(it, "api")
        xs.useAttributeFor(it, "comment")
        xs.useAttributeFor(it, "depends")
    }

    TypeRef::class.java.let {
        xs.addImplicitCollection(Require::class.java, "types", "type", it)
        xs.addImplicitCollection(Remove::class.java, "types", "type", it)
        xs.useAttributeFor(it, "name")
    }

    CommandRef::class.java.let {
        xs.addImplicitCollection(Require::class.java, "commands", "command", it)
        xs.addImplicitCollection(Remove::class.java, "commands", "command", it)
        xs.useAttributeFor(it, "name")
    }

    Extension::class.java.let {
        xs.alias("extension", it)
        xs.useAttributeFor(it, "name")
        xs.useAttributeFor(it, "number")
        xs.useAttributeFor(it, "type")
        xs.useAttributeFor(it, "depends")
        xs.useAttributeFor(it, "supported")
        xs.useAttributeFor(it, "platform")
        xs.useAttributeFor(it, "promotedto")
        xs.useAttributeFor(it, "deprecatedby")
        xs.useAttributeFor(it, "obsoletedby")
    }

    Enable::class.java.let {
        xs.useAttributeFor(it, "version")
        xs.useAttributeFor(it, "extension")
        xs.useAttributeFor(it, "struct")
        xs.useAttributeFor(it, "feature")
        xs.useAttributeFor(it, "requires")
    }

    xs
}.fromXML(Files
    .readAllBytes(registry)
    .toString(StandardCharsets.UTF_8)
    .replace("""<comment>[\s\S]*?</comment>""".toRegex(), "") // easier to remove than parse correctly
) as Registry