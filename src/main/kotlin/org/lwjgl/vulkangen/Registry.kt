/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import java.io.*
import java.nio.file.*
import kotlin.system.*

internal val DISABLED_EXTENSIONS = setOf(
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_EXT_directfb_surface",
    "VK_FUCHSIA_buffer_collection",
    "VK_FUCHSIA_imagepipe_surface",
    "VK_FUCHSIA_external_memory",
    "VK_FUCHSIA_external_semaphore",
    "VK_GGP_frame_token",
    "VK_GGP_stream_descriptor_surface",
    "VK_KHR_android_surface",
    "VK_KHR_xcb_surface",
    "VK_MVK_ios_surface",
    "VK_NN_vi_surface",
    "VK_QNX_screen_surface",
)

private val ABBREVIATIONS = setOf(
    "gcn",
    "glsl",
    "gpu",
    "pvrtc"
)

val String.template
    get() = this
        .splitToSequence('_')
        .map {
            if (ABBREVIATIONS.contains(it))
                it.uppercase()
            else
                "${it[0].uppercaseChar()}${it.substring(1)}"
        }
        .joinToString("")

private val IMPORTS = mapOf(
    "android/native_window.h" to "org.lwjgl.system.android.*",
    "vk_video/vulkan_video_codec_h264std.h" to "org.lwjgl.vulkan.video.*",
    "vk_video/vulkan_video_codec_h264std_encode.h" to "org.lwjgl.vulkan.video.*",
    "vk_video/vulkan_video_codec_h264std_decode.h" to "org.lwjgl.vulkan.video.*",
    "vk_video/vulkan_video_codec_h265std.h" to "org.lwjgl.vulkan.video.*",
    "vk_video/vulkan_video_codec_h265std_decode.h" to "org.lwjgl.vulkan.video.*",
    "wayland-client.h" to "org.lwjgl.system.linux.*",
    "windows.h" to "org.lwjgl.system.windows.*",
    "X11/Xlib.h" to "org.lwjgl.system.linux.*",
    "X11/extensions/Xrandr.h" to "org.lwjgl.system.linux.*"
)

private const val HEADER = """/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 * MACHINE GENERATED FILE, DO NOT EDIT
 */
"""

// Character sequence used for alignment
internal const val t = "    "

private const val S = "\$"
private const val QUOTES3 = "\"\"\""

internal class LWJGLWriter(out: Writer) : PrintWriter(out) {
    override fun println() = print('\n')
}

private class EnumRegistry(enumsList: List<Enums>) {
    val enums = enumsList.associateBy { it.name }
    val enumMap = enums.values.asSequence()
        .flatMap { it.enums?.asSequence() ?: emptySequence() }
        .associateByTo(HashMap()) { it.name }
}

fun main(args: Array<String>) {
    require(args.size == 2) {
        "Usage: VulkanSpecKt <vulkan-docs-path> <lwjgl3-path>"
    }

    val vulkanDocs = Paths.get(args[0]).toAbsolutePath().normalize()
    val registry = vulkanDocs.let {
        require(Files.isDirectory(it)) {
            "Invalid Vulkan-Docs repository path specified: $it"
        }

        val registryPath = it.resolve("xml/vk.xml")
        require(Files.isRegularFile(registryPath)) {
            "The path specified does not contain the Vulkan-Docs repository: $it"
        }

        parse(registryPath)
    }

    val root = args[1].let {
        val lwjgl3 = Paths.get(it)
        require(Files.isDirectory(lwjgl3)) {
            "Invalid lwjgl3 repository path specified: $it"
        }

        val root = lwjgl3.resolve("modules/lwjgl/vulkan/src/templates/kotlin/vulkan")
        require(Files.isDirectory(root)) {
            "The path specified does not contain the lwjgl3 repository: $it"
        }

        Files.createDirectories(root.resolve("templates"))

        root
    }

    val types = registry.types.associateBy(Type::name)

    val structs = registry.types.asSequence()
        .filterIsInstance<TypeStruct>()
        .associateBy(Type::name)

    val enumRegistry = EnumRegistry(registry.enums)

    val commands = registry.commands.associateByTo(HashMap()) {
        it.name ?: it.proto.name
    }

    registry.commands.asSequence()
        .filter { it.name != null }
        .forEach {
            val ref = commands[it.alias]!!
            commands[it.name!!] = Command(
                it.name,
                it.alias,
                ref.successcodes,
                ref.errorcodes,
                ref.queues,
                ref.renderpass,
                ref.cmdbufferlevel,
                ref.proto,
                ref.params,
                ref.validity,
                ref.implicitexternsyncparams
            )
        }

    try {
        convert(vulkanDocs, structs)
    } catch (e: Exception) {
        e.printStackTrace()
        exitProcess(-1)
    }

    // TODO: This must be fixed post Vulkan 1.0. We currently have no other way to identify types used in core only.

    val extensions = registry.extensions.asSequence()
        .filter { it.supported != "disabled" && !DISABLED_EXTENSIONS.contains(it.name) }

    registry.features.forEach { feature ->
        feature.requires.forEach { requires ->
            requires.enums?.forEach { enum ->
                enumRegistry.enumMap.putIfAbsent(enum.name, enum)
            }
        }
    }
    extensions.forEach { extension ->
        extension.requires.forEach { requires ->
            requires.enums?.forEach { enum ->
                enumRegistry.enumMap.putIfAbsent(enum.name, enum)
            }
        }
    }

    // TODO: build [enum -> core/extension] for static imports
    // see VkQueueFamilyGlobalPriorityPropertiesEXT in Custom.kt
    val enumsSeen = HashSet<Enums>()
    registry.features.forEach { feature ->
        generateFeature(root, types, enumRegistry, structs, commands, feature, enumsSeen)
    }

    extensions.forEach { extension ->
        // Type declarations for enums are missing in some extensions.
        // We generate <enums> the first time we encounter them.
        generateExtension(root, types, enumRegistry, structs, commands, extension, enumsSeen)
    }

    val featureTypes = getDistinctTypes(registry.features.asSequence().flatMap { it.requires.asSequence() }, commands, types)
    generateTypes(root, "VKTypes", types, structs, featureTypes)

    val extensionTypes = getDistinctTypes(extensions.flatMap { it.requires.asSequence() }, commands, types)
        .toMutableSet()
    extensionTypes.removeAll(featureTypes)
    generateTypes(root, "ExtensionTypes", types, structs, extensionTypes) {
        registry.tags.asSequence()
            .map { "const val ${it.name} = \"${it.name}\"" }
            .joinToString("\n", postfix = "\n\n")
    }

    printUnusedSectionXREFs()
    printUnusedLatexEquations()

    exitProcess(0)
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
) = requires
    .flatMap { require ->
        // Get all top-level types
        sequenceOf(
            if (require.types == null) emptySequence() else require.types.asSequence().map { it.name },
            if (require.enums == null) emptySequence() else require.enums.asSequence().map { it.name },
            if (require.commands == null) emptySequence() else require.commands.asSequence()
                .map { commands.getValue(it.name) }
                .flatMap { cmd ->
                    sequenceOf(cmd.proto.type) + cmd.params.asSequence().map { it.type }
                }
        )
            .flatten()
            .distinct()
            .flatMap {
                if (!types.containsKey(it)) {
                    emptySequence()
                } else {
                    // recurse into struct types
                    getDistinctTypes(it, types)
                }
            }
    }
    .toSet()

private fun getDistinctTypes(name: String, types: Map<String, Type>): Sequence<Type> = when (val type = types.getValue(name)) {
    is TypeStruct      -> type.members.asSequence()
        .filter { it.type != name }
        .flatMap { getDistinctTypes(it.type, types) } + sequenceOf(type)
    is TypeFuncpointer -> getDistinctTypes(type.proto.type, types) + sequenceOf(type) + type.params.asSequence().flatMap { getDistinctTypes(it.type, types) }
    else               -> sequenceOf(type)
}

private fun getParamType(param: Field, indirection: String, hasConst: Boolean, hasCheck: Boolean, encoding: String) =
    if (param.type == "void" && indirection.startsWith(".p"))
        "${if (param.len.none() && !hasCheck)
            if (hasConst) "opaque_const_p" else "opaque_p"
        else
            if (hasConst) "void.const.p" else "void.p"
        }${indirection.removePrefix(".p")}"
    else
        "${param.type}$encoding${if (hasConst) ".const.p" + indirection.removePrefix(".p") else indirection}"

private fun getReturnType(proto: Field) = proto.let {
    val hasConst = it.modifier == "const"

    getParamType(it, it.indirection, hasConst, true,
        if (it.len.contains("null-terminated") || (it.array != null && it.type == "char")) "UTF8" else ""
    )
}

private fun getCheck(param: Field, indirection: String, structs: Map<String, TypeStruct>, forceIN: Boolean): String {
    if (indirection.isNotEmpty()) { // pointer
        if (param.array != null) {
            return "Check(${param.array}).."
        } else if (param.len.none()) { // not auto-sized
            if (!forceIN && param.modifier != "const" && !structs.containsKey(param.type)) // output, non-struct
                return "Check(1).."
        } else {
            // TODO: support more? this currently only supports "struct->member"
            val customExpression = param.len.firstOrNull { it.contains("->") }
            if (customExpression != null)
                return "Check(\"${customExpression.substringBefore("->")}.${customExpression.substringAfterLast("->")}()\").."
        }
    }
    return ""
}

private fun getParams(
    function: Function,
    functionDoc: FunctionDoc?,
    types: Map<String, Type>,
    structs: Map<String, TypeStruct>,
    forceIN: Boolean = false,
    indent: String = "$t$t"
): String = if (function.params.isEmpty())
    ""
else {
    val returns = function.proto
    val params = function.params

    params.asSequence().map { param ->
        val autoSize = params.asSequence()
            .filter { it.len.contains(param.name) }
            .map { "\"${it.name}\"" }
            .joinToString(", ")
            .let {
                if (it.isEmpty())
                    ""
                else
                    "AutoSize($it).."
            }

        val indirection = param.indirection.let {
            if (param.array != null) {
                if (it.isEmpty())
                    ".p"
                else
                    "${it}p"
            } else
                it
        }

        val nativeType = types.getValue(param.type)
        val forceINParam = forceIN
                           /* TODO: happens to work nicely atm, revisit */
                           || (nativeType is TypeSystem && param.type.any(Character::isLowerCase))
                           /* TODO: validate this */
                           || param.externsync != null
        val check = getCheck(param, indirection, structs, forceINParam)

        val nullable = if ((indirection.isNotEmpty() || nativeType is TypeFuncpointer/* || (nativeType is TypeHandle && nativeType.type == "VK_DEFINE_HANDLE")*//* || nativeType is TypePlatform*/) && (
                // the parameter is optional
                "true" == param.optional ||
                // the AutoSize param is optional
                param.len.any() && params.any { param.len.contains(it.name) && "true" == it.optional })
        ) "nullable.." else ""

        val hasConst = param.modifier == "const"
        val isString = param.len.contains("null-terminated") || (param.type == "char" && (param.array != null || (function is TypeFuncpointer && hasConst && indirection == ".p")))
        val type = getParamType(param, indirection, hasConst, check.any(),
            if (isString) {
                if (returns.type == "PFN_vkVoidFunction") "ASCII" else "UTF8"
            } else ""
        ).let {
            if (function !is TypeFuncpointer || nativeType !is TypeStruct) {
                it
            } else {
                "_$it" // struct forward declaration
            }
        }
       val unsafe = if (
            indirection.isNotEmpty() &&
            check.isEmpty() &&
            !isString &&
            !(type == "opaque_p" || type == "opaque_const_p" || nativeType is TypeSystem || nativeType is TypeStruct) &&
            params.none { param.len.contains(it.name) }
        ) "Unsafe.." else ""
        val paramType = if ("false,true" == param.optional && (isString || nativeType is TypeStruct)) "Input.." else ""

        "$autoSize$check$unsafe$nullable$paramType$type(\"${param.name}\", \"${functionDoc?.parameters?.get(param.name) ?: ""}\")"
    }.joinToString(",\n$indent", prefix = ",\n\n$indent")
}

private fun getJavaImports(types: Map<String, Type>, fields: Sequence<Field>) = fields
    .mapNotNull {
        if (it.array != null && (it.array.startsWith("\"VK_MAX_") || it.array == "\"VK_UUID_SIZE\""))
            "static org.lwjgl.vulkan.VK10.*"
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
    .joinToString("\n$t")
    .let { if (it.isEmpty()) "" else "$it\n$t" }

private fun generateTypes(
    root: Path,
    template: String,
    types: Map<String, Type>,
    structs: Map<String, TypeStruct>,
    templateTypes: Set<Type>,
    custom: (() -> String)? = null
) {
    val file = root.resolve("$template.kt")

    LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
        val forwardDeclarations = HashSet<TypeStruct>()
        val structDefinitions = HashSet<String>()

        val structInTemplate = templateTypes.asSequence()
            .filterIsInstance<TypeStruct>()
            .map { it.name }
            .toHashSet()

        writer.print(HEADER)
        writer.print("""package vulkan

import org.lwjgl.generator.*${if (templateTypes.any { it is TypeSystem }) """
//import core.android.*
import core.linux.*
import core.macos.*
import core.windows.*""" else ""}

${if (custom != null) custom() else ""}// Handle types
${templateTypes.asSequence()
            .filterIsInstance<TypeHandle>()
            .joinToString("\n") { "val ${it.name} = ${it.type}(\"${it.name}\")" }}

// Enum types
${templateTypes.asSequence()
            .filterIsInstance<TypeEnum>()
            .joinToString("\n") { "val ${it.name} = \"${it.name}\".enumType" }}

// Bitmask types
${templateTypes.asSequence()
            .filterIsInstance<TypeBitmask>()
            .joinToString("\n") { "val ${it.name} = typedef(${it.typedef}, \"${it.name}\")" }}

${templateTypes.asSequence()
            .filterIsInstance<TypeFuncpointer>()
            .joinToString("\n\n") { fp ->
                // detect necessary struct forward declarations
                val structTypes = fp.params.asSequence()
                    .mapNotNull {
                        val type = types[it.type]
                        if (type is TypeStruct && !forwardDeclarations.contains(type)) {
                            forwardDeclarations.add(type)
                            type
                        } else {
                            null
                        }
                    }
                    .joinToString("\n") {
                        "val _${it.name} = ${it.type}(Module.VULKAN, \"${it.name}\")"
                    }.let {
                        if (it.isEmpty()) {
                            it
                        } else {
                            "$it\n"
                        }
                    }

                val functionDoc = FUNCTION_DOC[fp.name]
                """${structTypes}val ${fp.name} = Module.VULKAN.callback {
    ${getReturnType(fp.proto)}(
        "${fp.name.substring(4).let { "${it[0].uppercase()}${it.substring(1)}" }}",
        "${functionDoc?.shortDescription ?: ""}"${getParams(fp, FUNCTION_DOC[fp.proto.name], types, structs, forceIN = true, indent = "$t$t")},

        nativeType = "${fp.name}"
    ) {
        ${getJavaImports(types, sequenceOf(fp.proto) + fp.params.asSequence())}${if (functionDoc == null) "" else """documentation =
        $QUOTES3
        ${functionDoc.shortDescription}

        ${functionDoc.cSpecification}

        ${functionDoc.description}${if (seeAlsoIsEmpty(functionDoc.seeAlso)) "" else """

        ${functionDoc.seeAlso}"""}
        $QUOTES3"""}
    }
}"""
            }
            .let {
                if (it.isNotEmpty())
                    """// Function pointer types
$it

""" else ""
            }}// Struct types
${templateTypes.asSequence()
            .filterIsInstance<TypeStruct>()
            .joinToString("\n\n") { struct ->
                structDefinitions.add(struct.name)

                var aliasForwardDecl: String? = null
                var alias: String? = null
                struct.alias.let {
                    if (it != null) {
                        if (!structDefinitions.contains(it) && forwardDeclarations.none { decl -> decl.name == it } && structInTemplate.contains(it)) {
                            aliasForwardDecl = "val _$it = ${struct.type}(Module.VULKAN, \"${struct.alias}\")\n"
                            alias = ", alias = _$it"
                        } else {
                            alias = ", alias = $it"
                        }
                    }
                }

                val structDoc = STRUCT_DOC[struct.name]

                """${aliasForwardDecl ?: ""}${
                if (struct.members.any { it.type == struct.name }) "val _${struct.name} = ${struct.type}(Module.VULKAN, \"${struct.name}\")\n" else ""
                }val ${struct.name} = ${struct.type}(Module.VULKAN, "${struct.name}"${
                if (struct.returnedonly) ", mutable = false" else ""
                }${alias ?: ""}) {
    ${getJavaImports(types, struct.members.asSequence())}${if (structDoc == null) {
                    if (struct.alias == null) "" else """documentation = "See ##${struct.alias}."

    """
                } else
                    """documentation =
        $QUOTES3
        ${structDoc.shortDescription}${if (structDoc.description.isEmpty()) "" else """

        ${structDoc.description}"""}${if (seeAlsoIsEmpty(structDoc.seeAlso)) "" else """

        ${structDoc.seeAlso}"""}
        $QUOTES3

    """}${struct.members.asSequence()
                    .map { member ->
                        val expression = member.values.let {
                            if (it != null) {
                                if (it.contains(',')) {
                                    throw UnsupportedOperationException("Multiple struct member values! ${struct.name}::${member.name}")
                                }
                                """Expression("#${it.substring(3)}").."""
                            } else {
                                ""
                            }
                        }

                        val autoSize = struct.members
                            .filter { it.len.contains(member.name) }
                            .let { members ->
                                if (members.isEmpty())
                                    ""
                                else {
                                    val references = members.asSequence()
                                        .map { "\"${it.name}\"" }
                                        .joinToString(", ")

                                    if (member.optional != null || members.all { it.optional != null } || members.any { it.noautovalidity != null })
                                        "AutoSize($references, optional = true).."
                                    //else if (members.count { it.noautovalidity != null } > 1)
                                        //"AutoSize($references, atLeastOne = true).."
                                    else
                                        "AutoSize($references).."
                                }
                            }

                        val nullable = if (
                            (member.name == "pNext" || member.optional != null || (member.noautovalidity != null && member.len.any())) && 
                            (member.indirection.isNotEmpty() || types.getValue(member.type).let { it is TypeFuncpointer || (it is TypeHandle && it.type == "VK_DEFINE_HANDLE") })
                        ) "nullable.." else ""

                        val hasConst = member.modifier == "const"
                        val type = getParamType(member, member.indirection, hasConst, false,
                            if (member.len.contains("null-terminated") || (member.array != null && member.type == "char")) "UTF8" else ""
                        ).let {
                            if (member.type == struct.name) "_$it" else it
                        }

                        "$expression$autoSize$nullable$type(\"${member.name}\", \"${structDoc?.members?.get(member.name) ?: ""}\"${if (member.bits == null) "" else ", bits = ${member.bits}"})${
                        if (member.array != null) "[${member.array}]" else ""
                        }${
                        if (struct.returnedonly && (member.name == "sType" || member.name == "pNext")) ".mutable()" else ""
                        }"
                    }
                    .joinToString("\n$t")}
}"""
            }}""")
    }
}

private fun generateFeature(
    root: Path,
    types: Map<String, Type>,
    enumRegistry: EnumRegistry,
    structs: Map<String, TypeStruct>,
    commands: Map<String, Command>,
    feature: Feature,
    enumsSeen: MutableSet<Enums>
) {
    val template = "VK${feature.number.substringBefore('.')}${feature.number.substringAfter('.')}"
    val file = root.resolve("templates/$template.kt")

    LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
        val distinctTypes = getDistinctTypes(feature.requires.asSequence(), commands, types)

        writer.print(HEADER)
        writer.print("""package vulkan.templates

import org.lwjgl.generator.*${distinctTypes.asSequence()
            .filterIsInstance<TypeSystem>()
            .mapNotNull { IMPORTS[it.requires] }
            .distinct()
            .map { "\nimport ${it.replace("org.lwjgl.system.", "core.")}" }
            .distinct()
            .joinToString()
        }
import vulkan.*

val $template = "$template".nativeClass(Module.VULKAN, "$template", prefix = "VK", binding = VK_BINDING_INSTANCE) {
    ${if (feature.number != "1.0") /* TODO: */"extends = VK10\n    " else ""}documentation =
        $QUOTES3
        The core Vulkan ${feature.number} functionality.
        $QUOTES3
""")
        feature.requires.asSequence()
            .mapNotNull { it.enums }
            .forEach { enums ->
                enums.asSequence()
                    .filter { it.extends != null && (it.value == null || !it.value.startsWith("VK_")) }
                    .groupBy { it.extends!! }
                    .forEach { (enumName, enumList) ->
                        val extends = enumList.firstOrNull { it.extends != null }?.extends
                        val typeLong = enumRegistry.enums[enumName]?.bitwidth == 64
                        val enumDoc = ENUM_DOC[enumName]
                        writer.println("""
    EnumConstant(
        ${when {
                            extends != null -> "\"Extends {@code $enumName}.\""
                            enumDoc == null -> "\"$enumName\""
                            else            -> """"$QUOTES3
        ${enumDoc.shortDescription}${
                            if (enumDoc.description.isEmpty()) "" else "\n\n$t$t${enumDoc.description}"}${
                            if (seeAlsoIsEmpty(enumDoc.seeAlso)) "" else "\n\n$t$t${enumDoc.seeAlso}"}
        $QUOTES3"""
                        }},

        ${enumList.asSequence()
                            .map { "\"${it.name.substring(3)}\".${it.getEnumValue(it.extnumber ?: 0, enumRegistry, typeLong)}" }
                            .joinToString(",\n$t$t")}
    )""")
                    }
            }

        val featureEnums = distinctTypes
            .filterEnums(enumRegistry.enums)
            .toMutableList()

        featureEnums.removeAll(enumsSeen)
        if (featureEnums.isNotEmpty()) {
            enumsSeen.addAll(featureEnums)
            writer.printEnums(featureEnums, 0, enumRegistry)
        }

        feature.requires.asSequence()
            .forEach {
                if (it.commands != null) {
                    writer.println("\n$t// ${it.comment}")
                    writer.printCommands(it.commands.asSequence().map { commandRef -> commandRef.name }, types, structs, commands)
                }
            }

        writer.print("\n}")
    }
}

private fun seeAlsoIsEmpty(seeAlso: String?): Boolean {
    return seeAlso == null || seeAlso.isEmpty() || seeAlso.contains("No cross-references are available")
}

private val VK_VERSION_REGEX = "VK_VERSION_(\\d+)_(\\d+)".toRegex()
private fun generateExtension(
    root: Path,
    types: Map<String, Type>,
    enumRegistry: EnumRegistry,
    structs: Map<String, TypeStruct>,
    commands: Map<String, Command>,
    extension: Extension,
    enumsSeen: MutableSet<Enums>
) {
    val name = extension.name.substring(3)
    val file = root.resolve("templates/$name.kt")

    LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
        val distinctTypes = getDistinctTypes(extension.requires.asSequence(), commands, types)

        writer.print(HEADER)
        writer.print("""package vulkan.templates

import org.lwjgl.generator.*${distinctTypes.asSequence()
            .filterIsInstance<TypeSystem>()
            .mapNotNull { IMPORTS[it.requires] }
            .filter { it.startsWith("org.lwjgl.system.") }
            .distinct()
            .map { "\nimport ${it.replace("org.lwjgl.system.", "core.")}" }
            .distinct()
            .joinToString()
        }
import vulkan.*

val $name = "${name.template}".nativeClassVK("$name", type = "${extension.type}", postfix = ${name.substringBefore('_')}) {
    documentation =
        $QUOTES3
        ${EXTENSION_DOC[name] ?: "The ${S}templateName extension."}
        $QUOTES3
""")

        extension.requires.asSequence()
            .mapNotNull { it.enums }
            .forEach { enums ->
                enums.asSequence()
                    .filter { it.value == null || !it.value.startsWith("VK_") }
                    .groupBy { it.extends ?: it.name }
                    .forEach nextEnumList@{ (enumName, enumList) ->
                        if (enumList.size == 1) {
                            val enum = enumList.first()
                            if (enum.name.endsWith("_EXTENSION_NAME")) {
                                writer.println("""
    StringConstant(
        "The extension name.",

        "${enum.name.substring(3)}"${enum.alias.let { if (it == null) "..${enum.value}" else ".expr(\"$it\")" }}
    )""")
                                return@nextEnumList
                            } else if (enum.name.endsWith("_SPEC_VERSION")) {
                                writer.println("""
    IntConstant(
        "The extension specification version.",

        "${enum.name.substring(3)}".."${enum.alias ?: enum.value}"
    )""")
                                return@nextEnumList
                            }
                        }

                        val extends = enumList.firstOrNull { it.extends != null }?.extends
                        val typeLong = enumRegistry.enums[enumName]?.bitwidth == 64
                        val enumDoc = ENUM_DOC[enumName]
                        writer.println("""
    EnumConstant${if (typeLong) "Long" else ""}(
        ${when {
                            extends != null -> "\"Extends {@code $enumName}.\""
                            enumDoc == null -> "\"$enumName\""
                            else -> """"$QUOTES3
        ${enumDoc.shortDescription}${
                        if (enumDoc.description.isEmpty()) "" else "\n\n$t$t${enumDoc.description}"}${
                        if (seeAlsoIsEmpty(enumDoc.seeAlso)) "" else "\n\n$t$t${enumDoc.seeAlso}"}
        $QUOTES3"""}},

        ${enumList.asSequence()
                            .map { "\"${it.name.substring(3)}\".${it.getEnumValue(it.extnumber ?: extension.number, enumRegistry, typeLong)}" }
                            .joinToString(",\n$t$t")}
    )""")
                    }
            }

        val extensionEnums = distinctTypes
            .filterEnums(enumRegistry.enums)
            .toMutableList()

        extensionEnums.removeAll(enumsSeen)
        if (extensionEnums.isNotEmpty()) {
            enumsSeen.addAll(extensionEnums)
            writer.printEnums(extensionEnums, extension.number, enumRegistry)
        }

        // Merge multiple dependencies (in different <require>) for the same command
        val dependencies = HashMap<String, String>()
        extension.requires.forEach { require ->
            val dependency = require.extension ?: require.feature.let {
                if (it == null) {
                    null
                } else {
                    val (major, minor) = VK_VERSION_REGEX.find(it)!!.destructured
                    "Vulkan$major$minor"
                }
            }
            if (dependency != null && require.commands != null) {
                require.commands.forEach { commandRef ->
                    dependencies.merge(commandRef.name, dependency) { current, dependency ->
                        when {
                            current.startsWith("ext.contains") -> """$current || ext.contains("$dependency")"""
                            else                               -> """ext.contains("$current") || ext.contains("$dependency")"""
                        }
                    }
                }
            }
        }

        writer.printCommands(
            extension.requires.asSequence()
                .filter { it.commands != null }
                .flatMap { it.commands!!.asSequence() }
                .map { it.name }
                .distinct(),
            types, structs, commands,
            dependencies
        )

        writer.print("}")
    }
}

private fun Enum.getEnumValue(extensionNumber: Int, enumRegistry: EnumRegistry, typeLong: Boolean): String = when {
    value != null  -> ".\"${value.replace("U", "")}${if (typeLong) "L" else ""}\""
    offset != null -> {
        if (typeLong) throw UnsupportedOperationException()
        ".\"${offsetAsEnum(extnumber ?: extensionNumber, offset, dir)}\""
    }
    bitpos != null -> "enum(${bitposAsHex(bitpos)}${if (typeLong) "L" else ""})"
    else           -> enumRegistry.enumMap.getValue(alias ?: name).getEnumValue(extensionNumber, enumRegistry, typeLong)
}

private fun offsetAsEnum(extensionNumber: Int, offset: String, dir: String?) =
    (1000000000 + (extensionNumber - 1) * 1000 + offset.toInt()).let {
        if (dir != null)
            -it
        else
            it
    }

private fun bitposAsHex(bitpos: String) = "0x${java.lang.Long.toHexString(1L shl bitpos.toInt()).padStart(8, '0')}"

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

private fun PrintWriter.printEnums(enums: List<Enums>, extensionNumber: Int, enumRegistry: EnumRegistry) {
    enums.asSequence()
        .filter { block -> block.enums != null }
        .forEach { block ->
            val typeLong = block.bitwidth == 64
            val enumDoc = ENUM_DOC[block.name]
            println("""
    EnumConstant${if (typeLong) "Long" else ""}(
        ${if (enumDoc == null) "\"${block.name}\"" else """$QUOTES3${"""
        ${enumDoc.shortDescription}${
            if (enumDoc.description.isEmpty()) "" else "\n\n$t$t${enumDoc.description}"}${
            if (seeAlsoIsEmpty(enumDoc.seeAlso)) "" else "\n\n$t$t${enumDoc.seeAlso}"}
        """.splitLargeLiteral()}$QUOTES3"""},

        ${block.enums!!.joinToString(",\n$t$t") {
                "\"${it.name.substring(3)}\".${it.getEnumValue(it.extnumber ?: extensionNumber, enumRegistry, typeLong)}"
            }}
    )""")
        }
}

private fun PrintWriter.printCommands(
    commandRefs: Sequence<String>,
    types: Map<String, Type>,
    structs: Map<String, TypeStruct>,
    commands: Map<String, Command>,
    dependencies: Map<String, String>? = null
) {
    commandRefs.forEach { commandRef ->
        val cmd = commands.getValue(commandRef)
        val name = commandRef.substring(2)

        print("\n$t")
        // If we don't have a dispatchable handle, mark ICD-global
        if (commandRef == "vkGetInstanceProcAddr" || cmd.params.none { param ->
                param.indirection.isEmpty() && types.getValue(param.type).let { it is TypeHandle && it.type == "VK_DEFINE_HANDLE" }/* && param.optional != "true"*/
            }) {
            print("GlobalCommand..")
        }

        dependencies?.get(commandRef).let {
            if (it != null) {
                print(if (it.startsWith("ext.contains"))
                    "DependsOn(\"\"\"$it\"\"\").."
                else
                    "DependsOn(\"$it\").."
                )
            }
        }

        val functionDoc = FUNCTION_DOC[name]
        println("""${getReturnType(cmd.proto)}(
        "$name",
        ${if (functionDoc == null) {
            if (cmd.alias != null) "\"See #${cmd.alias.substring(2)}().\"" else "\"\""
        } else
            """$QUOTES3
        ${functionDoc.shortDescription}

        ${functionDoc.cSpecification}

        ${functionDoc.description}${if (seeAlsoIsEmpty(functionDoc.seeAlso)) "" else """

        ${functionDoc.seeAlso}"""}
        $QUOTES3"""
        }${getParams(
            cmd,
            functionDoc ?: cmd.alias.let { if (it != null) FUNCTION_DOC[it.substring(2)] else null },
            types, 
            structs,
            // workaround: const missing from VK_EXT_debug_marker struct params
            forceIN = cmd.cmdbufferlevel != null
        )}
    )""")
    }
}