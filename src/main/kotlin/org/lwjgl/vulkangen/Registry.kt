/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.vulkangen

import java.io.*
import java.nio.file.*
import kotlin.system.*

internal lateinit var VULKAN_DOCS_ROOT: Path

val String.template
    get() = this
        .splitToSequence('_')
        .map { token ->
            if (EXTENSION_TOKEN_REPLACEMENTS.containsKey(token))
                EXTENSION_TOKEN_REPLACEMENTS[token]
            else
                "${token[0].uppercaseChar()}${token.substring(1)}"
        }
        .joinToString("")

internal data class Import(val templatePackage: String?, val javaPackage: String?)

internal class LWJGLWriter(out: Writer) : PrintWriter(out) {
    override fun println() = print('\n')
}

private class EnumRegistry(enumsList: List<Enums>) {
    val enums = enumsList.associateBy { it.name }
    val enumTypes = HashMap<String, Enums>()
    val enumMap = enums.values.asSequence()
        .flatMap { it.enums?.asSequence() ?: emptySequence() }
        .associateByTo(HashMap()) { it.name }
    val enumImportMap = HashMap<String, String>()

    init {
        enums.forEach { _, v -> v.enums?.forEach { enumTypes[it.name] = v } }
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) {
        "Usage: RegistryKt <vulkan-docs-path> <lwjgl3-path>"
    }

    VULKAN_DOCS_ROOT = Paths.get(args[0]).toAbsolutePath().normalize()
    val registry = VULKAN_DOCS_ROOT.let {
        require(Files.isDirectory(it)) {
            "Invalid Vulkan-Docs repository path specified: $it"
        }

        val registryPath = it.resolve("xml/vk.xml")
        require(Files.isRegularFile(registryPath)) {
            "The path specified does not contain the Vulkan-Docs repository: $it"
        }

        parse(registryPath)
    }

    // Remove Vulkan SC-only definitions.
    registry.types.removeIf { it.api == "vulkansc" }
    registry.types.forEach { type ->
        if (type is TypeStruct) {
            type.members.removeIf { it.api == "vulkansc" }
        }
    }
    registry.enums.forEach { block -> block.enums?.removeIf { it.api == "vulkansc" } }
    registry.commands.removeIf { it.api == "vulkansc" }
    registry.commands.forEach { command ->
        if (command.name == null) {
            command.params.removeIf { it.api == "vulkansc" }
        }
    }
    registry.features.removeIf { it.api == "vulkansc" }
    registry.extensions.removeIf { it.supported == "vulkansc" || it.supported == "disabled" || DISABLED_EXTENSIONS.contains(it.name) }
    registry.extensions.forEach { extension ->
        extension.requires.removeIf { it.api == "vulkansc" }
        extension.requires.forEach { requires ->
            requires.enums?.removeIf { it.api == "vulkansc" }
        }
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
        .associateBy(Type::name) + SYSTEM_STRUCTS

    val enumRegistry = EnumRegistry(registry.enums)

    val commands = registry.commands.associateByTo(HashMap()) {
        it.name ?: it.proto.name
    }

    registry.commands.asSequence()
        .filter { it.name != null }
        .forEach {
            val ref = commands[it.alias]
            if (ref == null) {
                commands.remove(it.name)
                return@forEach
            }
            commands[it.name!!] = Command(
                ref.api,
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

    // TODO: This must be fixed post Vulkan 1.0. We currently have no other way to identify types used in core only.

    registry.features.forEach { feature ->
        feature.requires.forEach { requires ->
            requires.enums?.forEach { enum ->
                enumRegistry.enumMap.putIfAbsent(enum.name, enum)
            }
        }
    }
    registry.extensions.forEach { extension ->
        extension.requires.forEach { requires ->
            requires.enums?.forEach { enum ->
                enumRegistry.enumMap.putIfAbsent(enum.name, enum)
            }
        }
    }

    // see VkQueueFamilyGlobalPriorityPropertiesEXT in Custom.kt
    val enumsSeen = HashSet<Enums>()
    registry.features.forEach { feature ->
        generateFeature(root, types, enumRegistry, structs, commands, feature, enumsSeen)
    }

    registry.extensions.forEach { extension ->
        // Type declarations for enums are missing in some extensions.
        // We generate <enums> the first time we encounter them.
        generateExtension(root, types, enumRegistry, structs, commands, extension, enumsSeen)
    }

    val featureTypes = getDistinctTypes(registry.features.asSequence().flatMap { it.requires.asSequence() }, commands, types)
    val extensionTypes = getDistinctTypes(registry.extensions.asSequence().flatMap { it.requires.asSequence() }, commands, types)
        .toMutableSet()
    extensionTypes.removeAll(featureTypes)

    // Does not contain struct types from disabled extensions
    val structsVisible =
        (featureTypes.asSequence().filterIsInstance<TypeStruct>() + extensionTypes.asSequence().filterIsInstance<TypeStruct>())
            .map { it.name }
            .toHashSet()

    // base struct name -> list of structs extending it
    val structExtends = HashMap<String, MutableList<String>>()
    structs.forEach { (child, childStruct) ->
        val structextends = childStruct.structextends
        if (structextends != null && structsVisible.contains(child)) {
            structextends.forEach { parent ->
                structExtends
                    .getOrPut(parent) { ArrayList() }
                    .add(child)
            }
        }
    }
    structExtends.forEach { (_, structTypes) -> structTypes.sort() }

    generateTypes(root, "VKTypes", types, structs, structExtends, enumRegistry, featureTypes)
    generateTypes(root, "ExtensionTypes", types, structs, structExtends, enumRegistry, extensionTypes)

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
    types: Map<String, Type>,
    commandsOnly: Boolean = false
) = requires
    .flatMap { require ->
        // Get all top-level types
        sequenceOf(
            if (require.types == null || commandsOnly) emptySequence() else require.types.asSequence().map { it.name },
            if (require.enums == null || commandsOnly) emptySequence() else require.enums.asSequence().map { it.name },
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

private val INDIRECTION_REGEX = "\\.p(?=\\.|$)".toRegex()
private fun getCheck(param: Field, indirection: String, structs: Map<String, TypeStruct>, forceIN: Boolean): String {
    if (indirection.isNotEmpty()) { // pointer
        if (param.array != null) {
            return "Check(${param.array}).."
        } else if (param.len.none()) { // not auto-sized
            if (!forceIN && param.modifier != "const" && (!structs.containsKey(param.type) || INDIRECTION_REGEX.findAll(indirection).count() > 1)) // output, non-struct or struct**
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
                           || (nativeType is TypeSystem && SYSTEM_OPAQUE.contains(nativeType.name))
                           /* TODO: validate this */
                           || param.externsync != null
        val check = getCheck(param, indirection, structs, forceINParam)

        val nullable = if ((indirection.isNotEmpty() || nativeType is TypeFuncpointer/* || (nativeType is TypeHandle && nativeType.type == "VK_DEFINE_HANDLE")*//* || nativeType is TypePlatform*/) && (
                // the parameter is optional
                "true" == param.optional ||
                // the parameter is marked with noautovalidity
                param.noautovalidity != null ||
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
            !(type == "opaque_p" || type == "opaque_const_p" || nativeType is TypeSystem || (nativeType is TypeBase && !nativeType.name.startsWith("Vk")) || nativeType is TypeStruct) &&
            params.none { param.len.contains(it.name) }
        ) "Unsafe.." else ""
        val paramType = if ("false,true" == param.optional && (isString || nativeType is TypeStruct)) "Input.." else ""

        "$autoSize$check$unsafe$nullable$paramType$type(\"${param.name}\")"
    }.joinToString(",\n$indent", prefix = ",\n\n$indent")
}

private fun getJavaImports(types: Map<String, Type>, fields: Sequence<Field>, enumRegistry: EnumRegistry) = fields
    .mapNotNull {
        if (it.array != null && it.array.startsWith("\"VK_"))
            "static org.lwjgl.vulkan.${enumRegistry.enumImportMap[it.array.substring(1, it.array.length - 1)]!!}.*"
        else {
            val type = types[it.type]
            if (type is TypeSystem)
                IMPORTS[type.requires]?.javaPackage
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
    structExtends: Map<String, List<String>>,
    enumRegistry: EnumRegistry,
    templateTypes: Set<Type>
) {
    val file = root.resolve("$template.kt")

    LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
        val forwardDeclarations = HashSet<String>()
        val structDefinitions = HashSet<String>()

        val structInTemplate = templateTypes.asSequence()
            .filterIsInstance<TypeStruct>()
            .map { it.name }
            .toHashSet()

        writer.print(HEADER)
        writer.print("""package vulkan

import org.lwjgl.generator.*${templateTypes.asSequence()
    .flatMap { getDistinctTypes(it.name, types) }
    .distinct()
    .filterIsInstance<TypeSystem>()
    .mapNotNull { IMPORTS[it.requires]?.templatePackage }
    .distinct()
    .sorted()
    .joinToString("") { "\nimport $it" }
}

// Handle types
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
                        if (type is TypeStruct && !forwardDeclarations.contains(type.name)) {
                            forwardDeclarations.add(type.name)
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

                """${structTypes}val ${fp.name} = Module.VULKAN.callback {
    ${getReturnType(fp.proto)}(
        "${fp.name.substring(4).let { "${it[0].uppercase()}${it.substring(1)}" }}"${getParams(fp, types, structs, forceIN = true, indent = "$t$t")},

        nativeType = "${fp.name}"
    )${getJavaImports(types, sequenceOf(fp.proto) + fp.params.asSequence(), enumRegistry).let { if (it.isEmpty()) " {}" else """ {
        $it
    }"""}}
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
                        if (!structDefinitions.contains(it) && structInTemplate.contains(it)) {
                            if (!forwardDeclarations.contains(it)) {
                                aliasForwardDecl = "val _$it = ${struct.type}(Module.VULKAN, \"${struct.alias}\")\n"
                                forwardDeclarations.add(it)
                            }
                            alias = ", alias = _$it"
                        } else {
                            alias = ", alias = $it"
                        }
                    }
                }
                var parentStruct: String? = null
                struct.parentstruct.let {
                    if (it != null) {
                        if (!structDefinitions.contains(it) && structInTemplate.contains(it)) {
                            if (!forwardDeclarations.contains(it)) {
                                aliasForwardDecl = "val _$it = ${struct.type}(Module.VULKAN, \"$it\")\n"
                                forwardDeclarations.add(it)
                            }
                            parentStruct = ", parentStruct = _$it"
                        } else {
                            parentStruct = ", parentStruct = $it"
                        }
                    }
                }

                """${aliasForwardDecl ?: ""}${
                if (struct.members.any { it.type == struct.name }) "val _${struct.name} = ${struct.type}(Module.VULKAN, \"${struct.name}\")\n" else ""
                }val ${struct.name} = ${struct.type}(Module.VULKAN, "${struct.name}"${
                if (struct.returnedonly) ", mutable = false" else ""
                }${alias ?: ""}${parentStruct ?: ""}) {
    ${getJavaImports(types, struct.members.asSequence(), enumRegistry)}${struct.members.asSequence()
                    .map { member ->
                        val pointerSetters = if (member.name == "pNext" && member.type == "void" && member.indirection == ".p") {
                            val pNextTypes = structExtends[struct.name]
                            if (pNextTypes != null) {
                                "PointerSetter(\n$t$t${pNextTypes.joinToString { "\"$it\"" } },\n$t${t}prepend = true\n$t).."
                            } else {
                                ""
                            }
                        } else {
                            ""
                        }

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
                            (member.optional != null || (member.noautovalidity != null /*&& member.len.any()*/)) && 
                            (member.indirection.isNotEmpty() || types.getValue(member.type).let { it is TypeFuncpointer || (it is TypeHandle && it.type == "VK_DEFINE_HANDLE") })
                        ) "nullable.." else ""

                        val hasConst = member.modifier == "const"
                        val type = getParamType(member, member.indirection, hasConst, false,
                            if (member.len.contains("null-terminated") || (member.array != null && member.type == "char")) "UTF8" else ""
                        ).let {
                            if (member.type == struct.name) "_$it" else it
                        }

                        "$pointerSetters$expression$autoSize$nullable$type(\"${member.name}\"${if (member.bits == null) "" else ", bits = ${member.bits}"})${
                        if (member.array != null) "[${member.array}]" else ""
                        }${
                        if (struct.returnedonly && ((member.name == "sType" && member.type == "VkStructureType") || (member.name == "pNext" && member.type == "void" && member.indirection == ".p"))) ".mutable()" else ""
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
        val imports = getDistinctTypes(feature.requires.asSequence(), commands, types, commandsOnly = true).asSequence()
            .filterIsInstance<TypeSystem>()
            .mapNotNull { IMPORTS[it.requires] }
            .distinct()
            .toList()

        writer.print(HEADER)
        writer.print("""package vulkan.templates

import org.lwjgl.generator.*
${imports.asSequence()
    .mapNotNull { it.templatePackage }
    .sorted()
    .joinToString("") { "import $it\n" }
}import vulkan.*

val $template = "$template".nativeClass(Module.VULKAN, "$template", prefix = "VK", binding = VK_BINDING_INSTANCE) {
    ${
        VERSION_HISTORY[feature.number].let {
            if (it == null) "" else "extends = VK$it\n$t"
        }
    }${
        imports.asSequence()
            .mapNotNull { it.javaPackage }
            .sorted()
            .joinToString("") { "javaImport(\"$it\")\n$t" }
    }""")

        feature.requires.asSequence()
            .mapNotNull { it.enums }
            .plus(
                getDistinctTypes(feature.requires.asSequence(), commands, types)
                    .filterEnums(enumRegistry.enums, enumsSeen)
                    .onEach { enumsSeen += it }
                    .mapNotNull { it.enums }
            )
            .flatten()
            .groupBy { enumRegistry.enumTypes[it.name]?.name ?: it.extends!! }
            .forEach { enumName, enumList ->
                if (enumName == "API Constants") {
                    enumList.forEach { enumRegistry.enumImportMap[it.name] = template }
                    return@forEach
                }

                val typeLong = enumRegistry.enums[enumName]?.bitwidth == 64
                writer.println("""
    EnumConstant${if (typeLong) "Long" else ""}(
        ${enumList.asSequence()
            .sortedByValue(0, enumRegistry, typeLong)
            .map {
                enumRegistry.enumImportMap[it.name] = template
                "\"${it.name.substring(3)}\".${it.getEnumValue(it.extnumber ?: 0, enumRegistry, typeLong)}"
            }
            .joinToString(",\n$t$t")}
    )""")
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
    val template = name.template
    val file = root.resolve("templates/$name.kt")

    LWJGLWriter(OutputStreamWriter(Files.newOutputStream(file), Charsets.UTF_8)).use { writer ->
        val imports = getDistinctTypes(extension.requires.asSequence(), commands, types, commandsOnly = true).asSequence()
            .filterIsInstance<TypeSystem>()
            .mapNotNull { IMPORTS[it.requires] }
            .distinct()
            .toList()

        writer.print(HEADER)
        writer.print("""package vulkan.templates

import org.lwjgl.generator.*
${imports.asSequence()
    .mapNotNull { it.templatePackage }
    .sorted()
    .joinToString("") { "import $it\n" }
}import vulkan.*

val $name = "${name.template}".nativeClassVK("$name", type = "${extension.type}", postfix = "${name.substringBefore('_')}") {${
        imports.asSequence()
            .mapNotNull { it.javaPackage }
            .sorted()
            .joinToString("") { "\n${t}javaImport(\"$it\")" }
    }""")

        extension.requires.asSequence()
            .mapNotNull { it.enums }
            .plus(
                getDistinctTypes(extension.requires.asSequence(), commands, types)
                    .filterEnums(enumRegistry.enums, enumsSeen)
                    .onEach { enumsSeen += it }
                    .mapNotNull { it.enums }
            )
            .flatten()
            .groupBy { enumRegistry.enumTypes[it.name]?.name ?: it.extends ?: it.name }
            .forEach nextEnumList@ { (enumName, enumList) ->
                val values = enumList.distinctBy { it.name }
                if (values.size == 1) {
                    val enum = values.first()
                    if (enum.name.endsWith("_EXTENSION_NAME")) {
                        writer.println("""
    StringConstant(
        "${enum.name.substring(3)}"${enum.alias.let { if (it == null) "..${enum.value}" else ".expr(\"$it\")" }}
    )""")
                        return@nextEnumList
                    } else if (enum.name.endsWith("_SPEC_VERSION")) {
                        writer.println("""
    IntConstant(
        "${enum.name.substring(3)}".."${enum.alias ?: enum.value}"
    )""")
                        return@nextEnumList
                    }
                }

                val typeLong = enumRegistry.enums[enumName]?.bitwidth == 64
                writer.println("""
    EnumConstant${if (typeLong) "Long" else ""}(
        ${values.asSequence()
            .sortedByValue(extension.number, enumRegistry, typeLong)
            .map {
                enumRegistry.enumImportMap[it.name] = template
                "\"${it.name.substring(3)}\".${it.getEnumValue(it.extnumber ?: extension.number, enumRegistry, typeLong)}"
            }
            .joinToString(",\n$t$t")}
    )""")
            }

        // Merge multiple dependencies (in different <require>) for the same command
        val dependencies = HashMap<String, String>()
        extension.requires.forEach { require ->
            if (require.depends == null) {
                return@forEach
            }

            if (require.commands != null) {
                val parsed = parseDepends(require.depends)
                require.commands.forEach { commandRef ->
                    dependencies.merge(
                        commandRef.name,
                        parsed
                    ) { current, dependency ->
                        if (current.contains(' '))
                            """$current || ext.contains("$dependency")"""
                        else
                            """ext.contains("$current") || ext.contains("$dependency")"""
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

private fun Sequence<Enum>.sortedByValue(extensionNumber: Int, enumRegistry: EnumRegistry, typeLong: Boolean) =
    if (typeLong) {
        this.sortedWith { a, b ->
            val va = a.getEnumValueLong(a.extnumber ?: extensionNumber, enumRegistry)
            val vb = b.getEnumValueLong(b.extnumber ?: extensionNumber, enumRegistry)

            val pa = 0L <= va.toLong()
            val pb = 0L <= vb.toLong()
            if (pa xor pb) {
                if (pa) -1 else 1
            } else if (pa) {
                va.compareTo(vb)
            } else {
                vb.compareTo(va)
            }
        }
    } else {
        this.sortedWith { a, b ->
            try {
                val va = a.getEnumValueInt(a.extnumber ?: extensionNumber, enumRegistry)
                val vb = b.getEnumValueInt(b.extnumber ?: extensionNumber, enumRegistry)

                val pa = 0 <= va.toInt()
                val pb = 0 <= vb.toInt()
                if (pa xor pb) {
                    if (pa) -1 else 1
                } else if (pa) {
                    va.compareTo(vb)
                } else {
                    vb.compareTo(va)
                }
            } catch (_: Exception) {
                a.getEnumValue(a.extnumber ?: extensionNumber, enumRegistry, false)
                    .compareTo(b.getEnumValue(b.extnumber ?: extensionNumber, enumRegistry, false))
            }
        }
    }

private fun Enum.getEnumValueLong(extensionNumber: Int, enumRegistry: EnumRegistry): ULong = when {
    value != null  -> value.replace("U", "").let {
        if (it.startsWith("0x")) {
            it.substring(2).toULong(16)
        } else if (it.startsWith("-")) {
            it.toLong().toULong()
        } else {
            it.toULong()
        }
    }
    offset != null -> throw UnsupportedOperationException()
    bitpos != null -> 1UL shl bitpos.toInt()
    else           -> enumRegistry.enumMap.getValue(alias ?: name).getEnumValueLong(extensionNumber, enumRegistry)
}

private fun Enum.getEnumValueInt(extensionNumber: Int, enumRegistry: EnumRegistry): UInt = when {
    value != null  -> value.replace("U", "").let {
        if (it.startsWith("0x")) {
            it.substring(2).toUInt(16)
        } else if (it.startsWith("-")) {
            it.toInt().toUInt()
        } else {
            it.toUInt()
        }
    }
    offset != null -> offsetAsEnum(extnumber ?: extensionNumber, offset, dir).toUInt()
    bitpos != null -> 1U shl bitpos.toInt()
    else           -> enumRegistry.enumMap.getValue(alias ?: name).getEnumValueInt(extensionNumber, enumRegistry)
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

private fun Set<Type>.filterEnums(enums: Map<String, Enums>, enumsSeen: MutableSet<Enums>) = this.asSequence()
    .flatMap {
        if (it is TypeBitmask && it.requires != null) {
            sequenceOf(it.name, it.requires)
        } else
            sequenceOf(it.name)
    }
    .mapNotNull { enums[it] }
    .filter { !enumsSeen.contains(it) }
    .distinct()

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
                print(if (it.contains('.'))
                    "DependsOn(\"\"\"$it\"\"\").."
                else
                    "DependsOn(\"$it\").."
                )
            }
        }

        println("""${getReturnType(cmd.proto)}(
        "$name"${getParams(
            cmd,
            types, 
            structs,
            // workaround: const missing from VK_EXT_debug_marker struct params
            forceIN = cmd.cmdbufferlevel != null
        )}
    )""")
    }
}