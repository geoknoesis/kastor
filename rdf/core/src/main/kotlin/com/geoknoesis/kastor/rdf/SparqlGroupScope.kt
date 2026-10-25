package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.SparqlDatasetClauses.Kind
import com.geoknoesis.kastor.rdf.SparqlDatasetClauses.SIMPLE_PATTERN_WORDS
import com.geoknoesis.kastor.rdf.SparqlDatasetClauses.Token
import com.geoknoesis.kastor.rdf.SparqlDatasetClauses.isKeyword
import com.geoknoesis.kastor.rdf.SparqlDatasetClauses.isPrefixedName

/**
 * The scope analysis of [SparqlDatasetClauses.withoutNamedGraphs] over the [tokens] of [text]: which variables
 * are in scope of a group (see `patternVariables` there). Recursion follows the nesting of groups, which the caller
 * bounds ([SparqlDatasetClauses.MAX_GROUP_DEPTH]).
 */
internal class SparqlGroupScope(private val text: String, private val tokens: List<Token>) {
    private fun punct(index: Int, c: Char) = tokens[index].kind == Kind.PUNCT && text[tokens[index].start] == c
    private fun keyword(index: Int, word: String) = isKeyword(text, tokens[index], word)
    private fun variable(index: Int) = text.substring(tokens[index].start, tokens[index].end)

    /** Adds the variables in scope of the group content [from] until [until] to [out]; false if not certain. */
    fun group(from: Int, until: Int, out: MutableSet<String>): Boolean {
        // The content of a group is a sub-select, or patterns.
        if (from < until && keyword(from, "SELECT")) return subSelect(from, until, out)
        var k = from
        while (k < until) {
            val token = tokens[k]
            when (token.kind) {
                Kind.VAR -> { out.add(variable(k)); k++ }
                Kind.PUNCT -> {
                    if (punct(k, '{')) {
                        val close = closing(k, until, '{', '}') ?: return false
                        if (!group(k + 1, close, out)) return false
                        k = close + 1
                    } else k++
                }
                Kind.WORD -> {
                    val first = text[token.start]
                    if (first in '0'..'9' || first == '.' || isPrefixedName(text, token)) { k++; continue }
                    val word = text.substring(token.start, token.end)
                    k = when {
                        word == "a" || word.uppercase() in SIMPLE_PATTERN_WORDS -> k + 1
                        word.equals("FILTER", ignoreCase = true) -> constraintEnd(k + 1, until)
                        word.equals("MINUS", ignoreCase = true) -> groupEnd(k + 1, until)
                        word.equals("BIND", ignoreCase = true) -> {
                            val assigned = assignment(k + 1, until) ?: return false
                            out.add(variable(assigned.first))
                            assigned.second
                        }
                        word.equals("VALUES", ignoreCase = true) -> inlineData(k + 1, until, out)
                        else -> null
                    } ?: return false
                }
                else -> k++
            }
        }
        return true
    }

    /** Index of the token closing the bracket [open] at [openIndex], before [until]; null if there is none. */
    private fun closing(openIndex: Int, until: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (j in openIndex until until) {
            if (tokens[j].kind != Kind.PUNCT) continue
            val c = text[tokens[j].start]
            if (c == open) depth++ else if (c == close && --depth == 0) return j
        }
        return null
    }

    /** Index after the `{ ... }` group that must start at [index]; null if there is none. */
    private fun groupEnd(index: Int, until: Int): Int? {
        if (index >= until || !punct(index, '{')) return null
        return closing(index, until, '{', '}')?.plus(1)
    }

    /**
     * Index of the `)` closing the `(` at [openIndex], if the expression between them was certainly read as it
     * is written. An IRI token that follows an operand (`?a<?b&&?c>1` reads as the IRI `<?b&&?c>`) may be a
     * comparison that swallowed brackets and variables: such an expression is not certain.
     */
    private fun expressionEnd(openIndex: Int, until: Int): Int? {
        val close = closing(openIndex, until, '(', ')') ?: return null
        for (j in openIndex + 1 until close) {
            if (tokens[j].kind != Kind.IRI) continue
            val before = tokens[j - 1]
            val operand = before.kind == Kind.VAR || before.kind == Kind.WORD || before.kind == Kind.STRING ||
                before.kind == Kind.IRI || punct(j - 1, ')')
            if (operand) return null
        }
        return close
    }

    /**
     * Index after the constraint of a `FILTER` that starts at [index]: `( expr )`, `[NOT] EXISTS { ... }`, or a
     * built-in or function call `name( ... )`.
     */
    private fun constraintEnd(index: Int, until: Int): Int? {
        var j = index
        if (j >= until) return null
        if (punct(j, '(')) return expressionEnd(j, until)?.plus(1)
        if (keyword(j, "NOT")) j++
        if (j < until && keyword(j, "EXISTS")) return groupEnd(j + 1, until)
        if (j != index) return null
        val callable = tokens[j].kind == Kind.IRI || tokens[j].kind == Kind.WORD
        if (!callable || j + 1 >= until || !punct(j + 1, '(')) return null
        return expressionEnd(j + 1, until)?.plus(1)
    }

    /**
     * For `( expr AS ?v )` starting at [index]: the index of `?v` and the index after the closing bracket.
     */
    private fun assignment(index: Int, until: Int): Pair<Int, Int>? {
        if (index >= until || !punct(index, '(')) return null
        val close = expressionEnd(index, until) ?: return null
        if (close - 2 <= index || tokens[close - 1].kind != Kind.VAR || !keyword(close - 2, "AS")) return null
        return (close - 1) to (close + 1)
    }

    /** Adds the variables of `VALUES ?v { ... }` or `VALUES ( ?v ... ) { ... }` at [index]; index after it. */
    private fun inlineData(index: Int, until: Int, out: MutableSet<String>): Int? {
        var j = index
        if (j >= until) return null
        if (tokens[j].kind == Kind.VAR) {
            out.add(variable(j))
            j++
        } else if (punct(j, '(')) {
            j++
            while (j < until && tokens[j].kind == Kind.VAR) { out.add(variable(j)); j++ }
            if (j >= until || !punct(j, ')')) return null
            j++
        } else return null
        return groupEnd(j, until)
    }

    /** The projected variables of the sub-select that fills the group [from] until [until]. */
    private fun subSelect(from: Int, until: Int, out: MutableSet<String>): Boolean {
        var j = from + 1
        if (j < until && (keyword(j, "DISTINCT") || keyword(j, "REDUCED"))) j++
        val projected = ArrayList<String>()
        var star = false
        while (true) {
            if (j >= until) return false
            when {
                tokens[j].kind == Kind.VAR -> { projected.add(variable(j)); j++ }
                punct(j, '*') -> { star = true; j++ }
                punct(j, '(') -> {
                    val assigned = assignment(j, until) ?: return false
                    projected.add(variable(assigned.first))
                    j = assigned.second
                }
                keyword(j, "WHERE") -> { j++; break }
                punct(j, '{') -> break
                else -> return false
            }
        }
        if (j >= until || !punct(j, '{')) return false
        val close = closing(j, until, '{', '}') ?: return false
        if (star) {
            if (projected.isNotEmpty() || !group(j + 1, close, out)) return false
        } else {
            if (projected.isEmpty()) return false
            out.addAll(projected)
        }
        // Solution modifiers may follow; a trailing VALUES clause joins its variables in, so it is not certain,
        // and neither is an expression with an IRI the scanner may have misread.
        for (m in close + 1 until until) {
            val token = tokens[m]
            if (token.kind == Kind.IRI || punct(m, '{') || punct(m, '}') || keyword(m, "VALUES")) return false
        }
        return true
    }
}
