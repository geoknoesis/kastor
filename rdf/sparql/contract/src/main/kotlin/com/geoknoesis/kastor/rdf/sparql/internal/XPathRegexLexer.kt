package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * The lexical half of the XPath regular expression translator ([XPathRegexTranslator]): escapes, property escapes
 * and character class expressions, each computed into a [CodePointSet] (see [XPathRegex] for the rules).
 *
 * [p] is the pattern, read from index [i]; [ignoreCase] is the `i` flag (characters and ranges are closed under case,
 * every other construct is not); [javaComments] says the `x` flag came from a leading `(?x)`, whose `java.util.regex`
 * readings that differ from XPath are refused.
 */
internal abstract class XPathRegexLexer(
    protected val p: String,
    start: Int,
    protected val ignoreCase: Boolean,
    protected val javaComments: Boolean,
) {
    protected var i = start

    /** How many class subtractions [charClass] is nested in. */
    private var classDepth = 0

    /** An invalid pattern (a syntax error): a SPARQL expression error, a shape compile error. */
    protected fun reject(reason: String): Nothing = throw XPathRegexException(p, reason)

    /**
     * A pattern that may be meaningful but that the linear-time engine cannot evaluate with the meaning its author
     * intends (a back-reference, a lookaround, a java.util.regex construct, a structural limit): refused **fail
     * closed** ([XPathRegexException.unsupported]), so a sandboxed query fails instead of a `FILTER` going false.
     */
    protected fun refuse(reason: String): Nothing = throw XPathRegexException(p, reason, unsupported = true)

    /** One escape, read by [escape]. */
    protected sealed interface Escape {
        /** One character (`\n`, `\$`, `\x41`): it can be a range endpoint, and the `i` flag closes it under case. */
        class Single(val cp: Int) : Escape

        /** A set of characters (`\d`, `\p{Lu}`, `\i`): the `i` flag does not change it. */
        class Chars(val set: CodePointSet) : Escape

        /** `\Q...\E`: literal characters, each closed under case by the `i` flag. */
        class Quoted(val codePoints: IntArray) : Escape

        /** A zero-width assertion with the same meaning in RE2 (`\A`, `\z`); [re2] is its RE2 text. */
        class Assertion(val re2: String) : Escape
    }

    /** Reads the escape at [i] (a backslash) and moves past it. */
    protected fun escape(): Escape {
        if (i + 1 >= p.length) reject("a trailing backslash at index $i escapes nothing")
        val from = i
        val n = p[i + 1]
        i += 2
        multiCharEscape(n)?.let { return Escape.Chars(it) }
        return when (n) {
            'Q' -> quoted()
            'p', 'P' -> Escape.Chars(property(from, n == 'P'))
            'A' -> Escape.Assertion("(?:\\A)")
            'z' -> Escape.Assertion("(?:\\z)")
            'n' -> Escape.Single('\n'.code)
            'r' -> Escape.Single('\r'.code)
            't' -> Escape.Single('\t'.code)
            'f' -> Escape.Single(0x0C)
            'a' -> Escape.Single(0x07)
            'e' -> Escape.Single(0x1B)
            'x' -> Escape.Single(hexEscape(from))
            'u' -> Escape.Single(fixedHex(from, 4))
            '0' -> Escape.Single(octalEscape())
            in '1'..'9' -> refuse(
                "the back-reference \\$n at index $from is not supported: patterns run on a linear-time engine (RE2), " +
                    "which has no back-references",
            )
            'b', 'B' -> refuse(
                "\\$n at index $from (a java.util.regex word boundary) is not an XPath regular expression construct, and " +
                    "the linear-time engine's word boundary is ASCII-only, so it would not mean the same",
            )
            else -> if (n.isLetterOrDigit() && n.code < 0x80) {
                refuse("the escape \\$n at index $from is not supported (not XPath regular expression syntax)")
            } else {
                // Any other escaped character is itself (XPath's single-character escapes, and java.util.regex's).
                val cp = p.codePointAt(from + 1)
                i = from + 1 + Character.charCount(cp)
                Escape.Single(cp)
            }
        }
    }

    /** The set of the multi-character escape `\n`, or null when [n] does not name one. */
    private fun multiCharEscape(n: Char): CodePointSet? = when (n) {
        'd' -> UnicodeSets.DIGIT
        'D' -> UnicodeSets.DIGIT.complement()
        's' -> UnicodeSets.SPACE
        'S' -> UnicodeSets.SPACE.complement()
        'w' -> UnicodeSets.WORD
        'W' -> UnicodeSets.WORD.complement()
        'i' -> UnicodeSets.NAME_START
        'I' -> UnicodeSets.NAME_START.complement()
        'c' -> UnicodeSets.NAME_CHAR
        'C' -> UnicodeSets.NAME_CHAR.complement()
        else -> null
    }

    /** `\Q...\E` (java.util.regex quoting) from [i] (after `\Q`) to `\E` or the end of the pattern. */
    private fun quoted(): Escape.Quoted {
        val end = p.indexOf("\\E", i).let { if (it < 0) p.length else it }
        val codePoints = p.substring(i, end).codePoints().toArray()
        i = if (end == p.length) end else end + 2
        return Escape.Quoted(codePoints)
    }

    /** `\x41` (two hex digits) or `\x{1F600}`, after the `\x` (at [i]); [from] is the backslash. */
    private fun hexEscape(from: Int): Int {
        if (i >= p.length || p[i] != '{') return fixedHex(from, 2)
        val close = p.indexOf('}', i)
        if (close < 0) reject("the hexadecimal escape at index $from is not terminated: '}' expected")
        val digits = p.substring(i + 1, close)
        i = close + 1
        val cp = if (digits.isNotEmpty() && digits.length <= 8 && digits.all(::isHex)) digits.toLong(16) else -1L
        if (cp !in 0..CodePointSet.MAX.toLong()) reject("the hexadecimal escape at index $from is not a code point")
        return cp.toInt()
    }

    private fun fixedHex(from: Int, count: Int): Int {
        if (i + count > p.length || !p.substring(i, i + count).all(::isHex)) {
            reject("the escape at index $from needs $count hexadecimal digits")
        }
        return p.substring(i, i + count).toInt(16).also { i += count }
    }

    /** java.util.regex's `\0n`, `\0nn`, `\0mnn` (m at most 3), after the `\0` (at [i]). */
    private fun octalEscape(): Int {
        var value = 0
        var digits = 0
        while (digits < 3 && i < p.length && p[i] in '0'..'7' && (digits < 2 || value <= 0x1F)) {
            value = value * 8 + (p[i] - '0')
            i++
            digits++
        }
        if (digits == 0) reject("the octal escape at index ${i - 2} has no digit")
        return value
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** `\p{Name}`, `\P{Name}` or `\pL` after the `\p` (at [i]); [from] is the backslash. */
    private fun property(from: Int, negated: Boolean): CodePointSet {
        val name = if (i < p.length && p[i] == '{') {
            val close = p.indexOf('}', i)
            if (close < 0) reject("the property escape at index $from is not terminated: '}' expected")
            p.substring(i + 1, close).also { i = close + 1 }
        } else if (i < p.length) {
            p[i].toString().also { i++ }
        } else {
            reject("the property escape at index $from has no name")
        }
        val set = when {
            name.startsWith("Is") -> blockOrScript(from, name.substring(2))
            UnicodeSets.isCategory(name) -> UnicodeSets.category(name)
            else -> refuse(
                "\\p{$name} at index $from: \"$name\" is not a general category of XML Schema regular expressions (L, Lu, " +
                    "Ll, Lt, Lm, Lo, M, Mn, Mc, Me, N, Nd, Nl, No, P, Pc, Pd, Ps, Pe, Pi, Pf, Po, Z, Zs, Zl, Zp, S, Sm, Sc, Sk, " +
                    "So, C, Cc, Cf, Co, Cn) or a block (\\p{IsBasicLatin})",
            )
        }
        return if (negated) set.complement() else set
    }

    /** `\p{IsName}`: the block `Name` (XML Schema), otherwise the script `Name` (java.util.regex). */
    private fun blockOrScript(from: Int, name: String): CodePointSet =
        UnicodeSets.block(XSD_BLOCK_ALIASES[name] ?: name) ?: UnicodeSets.script(name) ?: refuse(
            "\\p{Is$name} at index $from: \"$name\" is neither a Unicode block nor a Unicode script. In XML Schema " +
                "regular expressions \\p{IsX} names a block (the Unicode block name without spaces, e.g. \\p{IsBasicLatin}, " +
                "\\p{IsGreek}, \\p{IsLatin-1Supplement}); a java.util.regex script name is also accepted (\\p{IsLatin}, " +
                "\\p{IsHan}); java.util.regex binary properties (\\p{IsAlphabetic}) are not supported",
        )

    // --- character class expressions ----------------------------------------------------------------------------------

    /** The members of one class being read: characters and ranges (closed under case by `i`) and sets (not). */
    private inner class Members {
        val closed = CodePointSet.Builder()
        val plain = CodePointSet.Builder()
        var count = 0

        /** Whether the last member is one character (it can start a range), and that character. */
        var lastSingle = -1

        /** Whether the last member is an unescaped hyphen at the start of the class (it cannot start a range). */
        var hyphen = false

        fun single(cp: Int) {
            closed.add(cp)
            count++
            lastSingle = cp
            hyphen = false
        }

        fun set(set: CodePointSet) {
            plain.addAll(set)
            count++
            lastSingle = -1
            hyphen = false
        }

        fun result(negated: Boolean): CodePointSet {
            val chars = closed.build().let { if (ignoreCase) UnicodeSets.caseClosure(it) else it }
            val all = chars.union(plain.build())
            return if (negated) all.complement() else all
        }
    }

    /**
     * Reads the character class expression at [i] (`[`) and moves past its closing `]`: XML Schema `charClassExpr`
     * (positive or negated groups, ranges, escapes, a subtraction `-[...]` that ends the class), plus POSIX bracket
     * expressions. java.util.regex syntax that XPath reads differently (`&&`, a nested `[`) is refused.
     */
    protected fun charClass(): CodePointSet {
        val open = i
        i++
        val negated = i < p.length && p[i] == '^'
        if (negated) i++
        val members = Members()
        while (i < p.length) {
            val c = p[i]
            val next = p.getOrNull(i + 1)
            when {
                c == ']' -> {
                    if (members.count == 0) emptyClass(open)
                    i++
                    return members.result(negated)
                }
                c == '-' && next == '[' -> return subtraction(members, negated)
                c == '-' -> hyphen(members, next)
                c == '\\' && next != null -> classEscape(members)
                c == '[' && next == ':' -> members.set(posixClass())
                c == '[' -> refuse(
                    "unescaped '[' inside a character class at index $i: nested classes and unions ([a-c[x-z]]) are " +
                        "java.util.regex syntax that XPath regular expressions do not have; write \\[ for a literal " +
                        "bracket, [base-[excluded]] for a class subtraction, or [[:alpha:]] for a POSIX class",
                )
                c == '&' && next == '&' -> refuse(
                    "'&&' inside a character class at index $i: class intersection is java.util.regex syntax that " +
                        "XPath regular expressions do not have; write [base-[excluded]] for a class subtraction, or \\& " +
                        "for a literal ampersand",
                )
                else -> {
                    val cp = p.codePointAt(i)
                    rejectJavaComment(cp)
                    members.single(cp)
                    i += Character.charCount(cp)
                }
            }
        }
        reject("the character class starting at index $open is not terminated: ']' expected")
    }

    private fun emptyClass(open: Int): Nothing = reject(
        "']' at index $i right after \"${p.substring(open, i)}\": an XML Schema character class cannot be empty and an " +
            "unescaped ']' is never a member (java.util.regex and POSIX would read it as a member here); write \\] for a " +
            "literal bracket",
    )

    /** An escape inside a class: a member, the endpoint of nothing yet. */
    private fun classEscape(members: Members) {
        when (val e = escape()) {
            is Escape.Single -> members.single(e.cp)
            is Escape.Chars -> members.set(e.set)
            is Escape.Quoted -> {
                e.codePoints.forEach { members.closed.add(it) }
                members.count++
                members.lastSingle = -1
                members.hyphen = false
            }
            is Escape.Assertion -> reject("the assertion before index $i cannot be a member of a character class")
        }
    }

    /** XML Schema: `charClassSub ::= ( posCharGroup | negCharGroup ) '-' charClassExpr`, then `]`. */
    private fun subtraction(members: Members, negated: Boolean): CodePointSet {
        val at = i
        if (members.count == 0) {
            reject(
                "the character class subtraction at index $at has nothing to subtract from (XML Schema: " +
                    "[base-[excluded]]); write \\- for a literal hyphen and \\[ for a literal bracket",
            )
        }
        i++
        if (classDepth >= MAX_CLASS_NESTING) refuse("character class subtractions are nested more than $MAX_CLASS_NESTING levels deep at index $i")
        classDepth++
        val excluded = try { charClass() } finally { classDepth-- }
        if (i >= p.length || p[i] != ']') {
            reject("the character class subtraction at index $at must end its class: ']' expected after \"-[...]\" (XML Schema: [base-[excluded]])")
        }
        i++
        return members.result(negated).minus(excluded)
    }

    /**
     * A hyphen that does not start a subtraction: a literal at the start or the end of the class, after a range or a
     * set, and before a set escape (`[+-\w]`); otherwise a range from the previous character to the next one, which
     * may be a single-character escape but never an unescaped hyphen.
     */
    private fun hyphen(members: Members, next: Char?) {
        val literal = members.count == 0 || next == ']' || next == null || members.lastSingle < 0 ||
            (next == '\\' && p.getOrNull(i + 2)?.let { it in SET_ESCAPE_LETTERS } == true)
        if (literal) {
            val first = members.count == 0
            members.single('-'.code)
            if (!first) members.lastSingle = -1
            members.hyphen = first
            i++
            return
        }
        if (members.hyphen || next == '-') {
            reject(
                "unescaped '-' as an endpoint of the character range at index $i: XML Schema does not allow it and " +
                    "java.util.regex would read a range from or to the hyphen; write \\- for a literal hyphen",
            )
        }
        val first = members.lastSingle
        i++
        val last = if (p[i] == '\\' && i + 1 < p.length) {
            (escape() as? Escape.Single ?: reject("the character range ending at index $i needs a single character"))
                .cp
        } else {
            p.codePointAt(i).also { rejectJavaComment(it); i += Character.charCount(it) }
        }
        if (last < first) reject("the character range ending at index $i runs backwards")
        members.closed.add(first, last)
        members.lastSingle = -1
        members.hyphen = false
    }

    /** The POSIX bracket expression `[:name:]` at [i] (inside a class), moving past it. */
    private fun posixClass(): CodePointSet {
        val close = p.indexOf(":]", i + 2)
        val name = if (close < 0) null else p.substring(i + 2, close)
        val set = name?.let { UnicodeSets.POSIX[it] } ?: reject(
            "\"${if (close < 0) p.substring(i) else p.substring(i, close + 2)}\" at index $i is not a POSIX bracket " +
                "expression that can be translated: the supported names are ${UnicodeSets.POSIX.keys.joinToString(", ") { "[:$it:]" }} " +
                "(inside a character class, e.g. [[:alpha:]_]); write \\[ for a literal bracket",
        )
        i = close + 2
        return set
    }

    /** Under a leading `(?x)`, what java.util.regex's comments mode skips inside a class is refused. */
    private fun rejectJavaComment(cp: Int) {
        if (!javaComments) return
        if (cp == '#'.code) {
            refuse("unescaped '#' inside a character class at index $i after a leading (?x): java.util.regex reads it as the start of a comment, XPath regular expressions as a literal; write \\#")
        }
        if (isPatternWhitespace(cp)) {
            refuse(
                "whitespace inside a character class at index $i after a leading (?x): java.util.regex ignores it, XPath " +
                    "regular expressions keep it as a member; escape it (\\x20, \\t) or remove it",
            )
        }
    }

    protected fun isPatternWhitespace(cp: Int): Boolean = cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code

    protected companion object {
        /** The deepest nesting of character class subtractions (`[a-[b-[c]]]`): [charClass] recurses per level. */
        const val MAX_CLASS_NESTING = 64

        /** The letters of the escapes that stand for a set of characters (they cannot be the endpoint of a range). */
        const val SET_ESCAPE_LETTERS = "dDwWsSiIcCpPQ"

        /** XML Schema 1.0 block names that are not names or aliases of the block in `Character.UnicodeBlock`. */
        val XSD_BLOCK_ALIASES: Map<String, String> = mapOf("PrivateUse" to "PrivateUseArea")
    }
}
