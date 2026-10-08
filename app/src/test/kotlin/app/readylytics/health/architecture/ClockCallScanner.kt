package app.readylytics.health.architecture

/**
 * Test-only lexical scanner for detecting unclocked time calls in production code.
 * Rejects unclocked java.time and System time calls, resolves import aliases,
 * ignores comments and string literals, and normalizes violation identities.
 */
object ClockCallScanner {
    private val TIME_IMPORT_REGEX =
        Regex(
            """import\s+(?:java\.time\.""" +
                """(LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime|Instant|Clock))""" +
                """(?:\s+as\s+([A-Za-z0-9_]+))?""",
        )

    fun violations(source: String): List<String> {
        val stripped = stripCommentsAndStrings(source)
        val typeAliases =
            mutableMapOf(
                "LocalDateTime" to "LocalDateTime",
                "OffsetDateTime" to "OffsetDateTime",
                "java.time.LocalDateTime" to "LocalDateTime",
                "java.time.OffsetDateTime" to "OffsetDateTime",
                "LocalDate" to "LocalDate",
                "LocalTime" to "LocalTime",
                "ZonedDateTime" to "ZonedDateTime",
                "Instant" to "Instant",
                "Clock" to "Clock",
                "java.time.LocalDate" to "LocalDate",
                "java.time.LocalTime" to "LocalTime",
                "java.time.ZonedDateTime" to "ZonedDateTime",
                "java.time.Instant" to "Instant",
                "java.time.Clock" to "Clock",
            )

        for (match in TIME_IMPORT_REGEX.findAll(stripped)) {
            val canonical = match.groupValues[1]
            val alias = match.groupValues[2]
            if (alias.isNotEmpty()) {
                typeAliases[alias] = canonical
            }
        }

        val violations = mutableListOf<String>()

        scanNowCalls(stripped, typeAliases, violations)
        scanSystemCurrentTimeMillis(stripped, violations)
        scanOperationalClockConstruction(stripped, typeAliases, violations)

        return violations
    }

    private fun stripCommentsAndStrings(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            i =
                when {
                    source.startsWith("\"\"\"", i) -> consumeTripleQuote(source, i, sb)
                    source[i] == '"' -> consumeStringLiteral(source, i, sb)
                    source[i] == '\'' -> consumeStringLiteral(source, i, sb, '\'')
                    source.startsWith("/*", i) -> consumeBlockComment(source, i, sb)
                    source.startsWith("//", i) -> consumeLineComment(source, i, sb)
                    else -> {
                        sb.append(source[i])
                        i + 1
                    }
                }
        }
        return sb.toString()
    }

    private fun consumeTripleQuote(
        source: String,
        startIdx: Int,
        sb: StringBuilder,
    ): Int {
        sb.append("   ")
        var i = startIdx + 3
        val n = source.length
        while (i < n && !source.startsWith("\"\"\"", i)) {
            if (source[i] == '\n') sb.append('\n') else sb.append(' ')
            i++
        }
        if (i < n) {
            sb.append("   ")
            i += 3
        }
        return i
    }

    private fun consumeStringLiteral(
        source: String,
        startIdx: Int,
        sb: StringBuilder,
        quote: Char = '"',
    ): Int {
        sb.append(' ')
        var i = startIdx + 1
        val n = source.length
        while (i < n && source[i] != quote) {
            if (source[i] == '\\' && i + 1 < n) {
                sb.append("  ")
                i += 2
            } else {
                if (source[i] == '\n') sb.append('\n') else sb.append(' ')
                i++
            }
        }
        if (i < n) {
            sb.append(' ')
            i++
        }
        return i
    }

    private fun consumeBlockComment(
        source: String,
        startIdx: Int,
        sb: StringBuilder,
    ): Int {
        var depth = 1
        sb.append("  ")
        var i = startIdx + 2
        val n = source.length
        while (i < n && depth > 0) {
            if (source.startsWith("/*", i)) {
                depth++
                sb.append("  ")
                i += 2
            } else if (source.startsWith("*/", i)) {
                depth--
                sb.append("  ")
                i += 2
            } else {
                if (source[i] == '\n') sb.append('\n') else sb.append(' ')
                i++
            }
        }
        return i
    }

    private fun consumeLineComment(
        source: String,
        startIdx: Int,
        sb: StringBuilder,
    ): Int {
        sb.append("  ")
        var i = startIdx + 2
        val n = source.length
        while (i < n && source[i] != '\n') {
            sb.append(' ')
            i++
        }
        return i
    }

    private fun scanNowCalls(
        stripped: String,
        typeAliases: Map<String, String>,
        violations: MutableList<String>,
    ) {
        val aliasPattern = typeAliases.keys.joinToString("|") { Regex.escape(it) }
        val nowCallRegex = Regex("""\b($aliasPattern)\s*\.\s*now\s*\(""")

        for (match in nowCallRegex.findAll(stripped)) {
            val matchedType = match.groupValues[1]
            val canonicalType = typeAliases[matchedType] ?: matchedType
            val parenStart = match.range.last

            val parenEnd = findMatchingParen(stripped, parenStart)
            if (parenEnd == -1) continue

            val argText = stripped.substring(parenStart + 1, parenEnd).trim()
            if (argText.isEmpty()) {
                violations.add("$canonicalType.now()")
            } else {
                val clockTypes = typeAliases.filterValues { it == "Clock" }.keys.joinToString("|") { Regex.escape(it) }
                val declaredClocks =
                    Regex("""\b(\w+)\s*:\s*(?:$clockTypes)\b""")
                        .findAll(stripped)
                        .map { it.groupValues[1] }
                        .toSet() + "clock"
                val passesClock = isClockExpression(argText, declaredClocks, clockTypes)
                if (!passesClock) {
                    violations.add("$canonicalType.now(zoneId)")
                }
            }
        }
    }

    private fun scanSystemCurrentTimeMillis(
        stripped: String,
        violations: MutableList<String>,
    ) {
        val regex = Regex("""\b(?:java\s*\.\s*lang\s*\.\s*)?System\s*\.\s*currentTimeMillis\s*\(\s*\)""")
        for (match in regex.findAll(stripped)) {
            violations.add("System.currentTimeMillis()")
        }
    }

    private fun scanOperationalClockConstruction(
        stripped: String,
        typeAliases: Map<String, String>,
        violations: MutableList<String>,
    ) {
        val clockTypes = typeAliases.filterValues { it == "Clock" }.keys.joinToString("|") { Regex.escape(it) }
        val clockRegex = Regex("""\b(?:$clockTypes)\s*\.\s*(systemDefaultZone|systemUTC|system)\s*\(""")
        for (match in clockRegex.findAll(stripped)) {
            val method = match.groupValues[1]
            violations.add(if (method == "system") "Clock.system(zoneId)" else "Clock.$method()")
        }
    }

    private fun isClockExpression(
        expression: String,
        declaredClocks: Set<String>,
        clockTypes: String,
    ): Boolean {
        var compact = expression.replace(Regex("""\s+"""), "")
        while (compact.startsWith('(') && findMatchingParen(compact, 0) == compact.lastIndex) {
            compact = compact.substring(1, compact.lastIndex)
        }
        val names = declaredClocks.joinToString("|") { Regex.escape(it) }
        val reference = Regex("""^(?:this\.)?(?:$names)\b""").find(compact)
        val factory = Regex("""^(?:$clockTypes)\.(?:fixed|offset|tick|tickSeconds|tickMinutes)\(""").find(compact)
        val baseEnd =
            reference?.range?.last?.plus(1)
                ?: factory?.let { findMatchingParen(compact, it.range.last).takeIf { end -> end >= 0 }?.plus(1) }
                ?: return false
        var remainder = compact.substring(baseEnd)
        var isClock = true
        while (isClock && remainder.isNotEmpty()) {
            val end =
                Regex("""^\.withZone\(""").find(remainder)?.let { findMatchingParen(remainder, it.range.last) } ?: -1
            isClock = end >= 0
            if (isClock) remainder = remainder.substring(end + 1)
        }
        return isClock
    }
}

private fun findMatchingParen(
    text: String,
    openParenIdx: Int,
): Int {
    var depth = 1
    var idx = openParenIdx + 1
    while (idx < text.length && depth > 0) {
        when (text[idx]) {
            '(' -> depth++
            ')' -> depth--
        }
        if (depth == 0) return idx
        idx++
    }
    return -1
}
