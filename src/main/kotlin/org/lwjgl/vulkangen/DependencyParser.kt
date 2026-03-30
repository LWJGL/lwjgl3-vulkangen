package org.lwjgl.vulkangen

/**
 * Translates a "protect" expression to a C preprocessor #if expression.
 *
 * Rules:
 * - every identifier becomes defined(<identifier>)
 * - '+' becomes '&&'
 * - ',' becomes '||'
 *
 * Grammar:
 *   expr      := orExpr
 *   orExpr    := andExpr (',' andExpr)*
 *   andExpr   := primary ('+' primary)*
 *   primary   := IDENT | '(' expr ')'
 *
 * Examples:
 *   VK_A,VK_B           -> defined(VK_A) || defined(VK_B)
 *   VK_A+VK_B           -> defined(VK_A) && defined(VK_B)
 *   (VK_A+VK_B),VK_C    -> (defined(VK_A) && defined(VK_B)) || defined(VK_C)
 */
private sealed interface Node

private data class Dependency(val name: String) : Node
private data class AND(val left: Node, val right: Node) : Node
private data class OR(val left: Node, val right: Node) : Node

private class Parser(private val input: String) {
    private var pos = 0

    fun parse(): Node {
        val result = parseOR()
        if (pos != input.length) {
            error("Unexpected character '${input[pos]}' at position $pos")
        }
        return result
    }

    private fun parseOR(): Node {
        var left = parseAND()
        while (true) {
            if (!match(',')) break
            val right = parseAND()
            left = OR(left, right)
        }
        return left
    }

    private fun parseAND(): Node {
        var left = parseExpression()
        while (true) {
            if (!match('+')) break
            val right = parseExpression()
            left = AND(left, right)
        }
        return left
    }

    private fun parseExpression(): Node {
        if (match('(')) {
            val expr = parseOR()
            if (!match(')')) {
                error("Expected ')' at position $pos")
            }
            return expr
        }

        val name = parseDependency()
        if (name.isEmpty()) {
            error("Expected dependency at position $pos")
        }
        return Dependency(name)
    }

    private fun parseDependency(): String {
        val start = pos

        while (pos < input.length) {
            val c = input[pos]
            if (c.isLetterOrDigit() || c == '_') {
                pos++
            } else {
                break
            }
        }

        return input.substring(start, pos)
    }

    private fun match(ch: Char): Boolean {
        if (pos < input.length && input[pos] == ch) {
            pos++
            return true
        }
        return false
    }
}

internal fun parseDepends(protect: String): String {
    val node = Parser(protect).parse()
    return render(node)
}

private fun render(node: Node, parentPrecedence: Int = 0): String {
    val precedence = when (node) {
        is Dependency -> 3
        is AND        -> 2
        is OR         -> 1
    }

    val rendered = when (node) {
        is Dependency -> parseDependExpression(node.name, parentPrecedence != 0)
        is AND        -> "${render(node.left, precedence)} && ${render(node.right, precedence)}"
        is OR         -> "${render(node.left, precedence)} || ${render(node.right, precedence)}"
    }

    return if (precedence < parentPrecedence) "($rendered)" else rendered
}

private val VK_VERSION_REGEX = "VK_VERSION_(\\d+)_(\\d+)".toRegex()
private fun parseDependExpression(name: String, wrap: Boolean): String {
    val dependency = VK_VERSION_REGEX.matchEntire(name)?.let {
        val (major, minor) = it.destructured
        "Vulkan$major$minor"
    } ?: name

    return if (wrap) "ext.contains(\"$dependency\")" else dependency
}