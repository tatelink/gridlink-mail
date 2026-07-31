package app.sterna.core.data.mail

import java.io.File

/**
 * Hands a JVM test the SQL of a Room `@Query` **as it is written in the shipped DAO**.
 *
 * The conversation list's SQL is a pure Kotlin function ([conversationSql]), so a test can just
 * call it. The queries a DAO declares in an annotation cannot be called that way: `androidx.room.
 * Query` has BINARY retention, so it is gone at runtime and reflection cannot reach it. The
 * existing SQL tests therefore RETYPE those queries into a `private val` — which means editing the
 * DAO breaks nothing, and the test keeps checking a copy of a query the app no longer runs.
 *
 * This reads the annotation out of the DAO source file instead, so the statement executed here is
 * literally the statement the app ships. If the DAO's SQL changes, this test's SQL changes with it
 * — and if the DAO moves or the function is renamed, the lookup fails loudly rather than silently
 * validating a stale copy.
 */
internal object DaoQuerySource {
    private const val DAO_DIR = "core/data/src/main/kotlin/app/sterna/core/data/db/"
    private const val MAIL_DIR = "core/data/src/main/kotlin/app/sterna/core/data/mail/"

    private val sources = mutableMapOf<String, String>()

    private fun daoSource(daoName: String): String =
        sources.getOrPut(daoName) { locate("$DAO_DIR$daoName.kt").readText() }

    /**
     * One statement a DAO function issues: which `@Query` function carried it, its [sql], and
     * whether the calling line wraps it in a `runCatching` — i.e. whether the shipped code lets
     * that statement fail without taking the rest of the path down with it.
     */
    data class DaoStatement(val function: String, val sql: String, val guarded: Boolean)

    /**
     * The statements `EmailDao.[functionName]` issues, in the order its source issues them: its own
     * `@Query` when it has one, otherwise the queries of the functions its body calls.
     *
     * This is how a JVM SQL test replays a `@Transaction`-style composition without assuming what
     * it composes: drop the un-indexing from the DAO body and it disappears from here too, so the
     * tests that assert an index row is gone turn red instead of quietly checking a path the app no
     * longer runs. Calls to anything that is not a `@Query` of the same DAO (`isEmpty`, a log) are
     * not statements and are skipped.
     */
    fun emailDaoStatements(functionName: String): List<DaoStatement> {
        queryOrNull("EmailDao", functionName)?.let {
            return listOf(DaoStatement(functionName, it, guarded = false))
        }
        val body = daoFunctionBody("EmailDao", functionName)
        return body.lines().flatMap { line ->
            Regex("""(\w+)\(""").findAll(line).mapNotNull { m ->
                val called = m.groupValues[1]
                queryOrNull("EmailDao", called)
                    ?.let { DaoStatement(called, it, guarded = line.contains("runCatching")) }
            }
        }
    }

    /**
     * Whether `[daoName].[functionName]` carries `@Transaction` — a replay of a composed path has
     * to honour that: inside one, a statement that throws takes its predecessors down with it;
     * without it, each statement stands (and commits) on its own.
     *
     * Read from the annotation lines sitting directly above the declaration, not by searching the
     * file backwards: KDoc prose naming the annotation would otherwise answer for the code.
     */
    fun isTransactional(daoName: String, functionName: String): Boolean {
        val source = daoSource(daoName)
        val fn = Regex("""\bfun\s+$functionName\s*\(""").find(source)
            ?: error("$daoName has no function named '$functionName' — did it get renamed?")
        return source.substring(0, fn.range.first).lines().dropLast(1).asReversed()
            .map { it.trim() }
            .takeWhile { it.startsWith("@") }
            .any { it == "@Transaction" }
    }

    /**
     * The Kotlin body of `fun [functionName]` in a file of the `mail` package ([fileName] without
     * its `.kt`) — same purpose as [daoFunctionBody] one layer up: a test that wants to know which
     * DAO call a repository path makes reads the path itself rather than restating it.
     */
    fun mailFunctionBody(fileName: String, functionName: String): String =
        functionBody(
            sources.getOrPut("mail/$fileName") { locate("$MAIL_DIR$fileName.kt").readText() },
            fileName,
            functionName,
        )

    /**
     * The SQL of the `@Query` annotating `fun [functionName]` in `EmailDao`, with Room's named
     * parameters left in place (`:accountId`, …) — [bindOrder] turns them into positional `?`.
     */
    fun emailDaoQuery(functionName: String): String = daoQuery("EmailDao", functionName)

    /**
     * The Kotlin body of `fun [functionName]` in [daoName], braces included.
     *
     * A `@Transaction` default method composes several statements, and running those statements
     * one after another in a test proves what each does but NOT that the shipped function still
     * calls both. This is how a test checks the wiring itself; the lookup fails loudly if the
     * function is renamed or stops having a body.
     */
    fun daoFunctionBody(daoName: String, functionName: String): String =
        functionBody(daoSource(daoName), daoName, functionName)

    private fun functionBody(source: String, owner: String, functionName: String): String {
        val fn = Regex("""\bfun\s+$functionName\s*\(""").find(source)
            ?: error("$owner has no function named '$functionName' — did it get renamed?")
        // The brace must be this signature's own, right after its closing ')': an abstract DAO
        // function has none, and taking "the next '{' in the file" would silently hand back some
        // later function's body.
        val open = bodyBrace(source, fn.range.last)
        check(open >= 0) {
            "'$functionName' in $owner has no body — is it still the function that composes " +
                "its statements, or has it gone back to a single abstract @Query?"
        }
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in $owner.$functionName")
    }

    /** [emailDaoQuery] for any DAO of the `db` package, named without its `.kt` ([daoName]). */
    fun daoQuery(daoName: String, functionName: String): String =
        queryOrNull(daoName, functionName)
            ?: error("'$functionName' in $daoName is not annotated with @Query — did it get renamed?")

    /**
     * The SQL of `[daoName].[functionName]`'s `@Query`, or null when that function does not exist
     * or carries no `@Query` of its OWN. "Of its own" is the whole point: the nearest preceding
     * annotation in the file belongs to another function as soon as a declaration sits between the
     * two, and handing back that neighbour's SQL would have a test quietly execute a statement the
     * function it names never issues.
     */
    fun queryOrNull(daoName: String, functionName: String): String? {
        val source = daoSource(daoName)
        val fn = Regex("""\bfun\s+$functionName\s*\(""").find(source) ?: return null
        val head = source.substring(0, fn.range.first)
        val annotation = head.lastIndexOf("@Query(")
        if (annotation < 0 || head.substring(annotation).contains(DECLARATION)) return null
        val literals = stringLiterals(head.substring(annotation + "@Query(".length))
        check(literals.isNotEmpty()) { "@Query on '$functionName' holds no string literal" }
        return literals.joinToString("")
    }

    /** A function declaration — what tells an annotation apart from the one before it. */
    private val DECLARATION = Regex("""\bfun\s+\w+\s*\(""")

    /**
     * The same query with Room's named parameters replaced by positional `?`, plus the order the
     * caller must bind them in — the order the names appear in the statement. [listParams] gives
     * the length of each collection parameter (Room expands `IN (:ids)` into that many `?`), and
     * a name listed there contributes that many binds in a row.
     */
    fun bindOrder(sql: String, listParams: Map<String, Int> = emptyMap()): Pair<String, List<String>> {
        val names = Regex(":([A-Za-z_][A-Za-z0-9_]*)").findAll(sql).map { it.groupValues[1] }.toList()
        var out = sql
        val order = mutableListOf<String>()
        names.forEach { name ->
            val count = listParams[name] ?: 1
            order += List(count) { name }
        }
        // Longest names first, so ':threadKey' can't be eaten by a ':thread' prefix.
        names.distinct().sortedByDescending { it.length }.forEach { name ->
            val count = listParams[name] ?: 1
            out = out.replace(":$name", List(count) { "?" }.joinToString(","))
        }
        return out to order
    }

    /**
     * Index of the `{` opening the body of the function whose parameter list starts at [paren],
     * or -1 when the function is abstract. Walks the parameter list to its closing `)` (it may
     * span lines), then accepts only a `{` on that same line — anything else means no body.
     */
    private fun bodyBrace(source: String, paren: Int): Int {
        var depth = 0
        var i = paren
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) {
                    var j = i + 1
                    while (j < source.length && source[j] == ' ') j++
                    return if (j < source.length && source[j] == '{') j else -1
                }
            }
            i++
        }
        return -1
    }

    /** The double-quoted literals of [text] up to its first unbalanced `)`, in order. */
    private fun stringLiterals(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(text[i]); i++
                    }
                    out += sb.toString()
                    i++
                }
                c == '(' -> { depth++; i++ }
                c == ')' -> { if (depth == 0) return out; depth--; i++ }
                else -> i++
            }
        }
        return out
    }

    /**
     * [relative] resolved from the test's working directory (Gradle runs a module's tests with the
     * module directory as CWD, so both the repo root and `core/data` must work), walking up until
     * the file turns up.
     */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
