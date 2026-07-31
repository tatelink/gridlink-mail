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

    private val sources = mutableMapOf<String, String>()

    private fun daoSource(daoName: String): String =
        sources.getOrPut(daoName) { locate("$DAO_DIR$daoName.kt").readText() }

    /**
     * The SQL of the `@Query` annotating `fun [functionName]` in `EmailDao`, with Room's named
     * parameters left in place (`:accountId`, …) — [bindOrder] turns them into positional `?`.
     */
    fun emailDaoQuery(functionName: String): String = daoQuery("EmailDao", functionName)

    /** [emailDaoQuery] for any DAO of the `db` package, named without its `.kt` ([daoName]). */
    fun daoQuery(daoName: String, functionName: String): String {
        val source = daoSource(daoName)
        val fn = Regex("""\bfun\s+$functionName\s*\(""").find(source)
            ?: error("$daoName has no function named '$functionName' — did it get renamed?")
        val head = source.substring(0, fn.range.first)
        val annotation = head.lastIndexOf("@Query(")
        check(annotation >= 0) { "'$functionName' in $daoName is not annotated with @Query" }
        val literals = stringLiterals(head.substring(annotation + "@Query(".length))
        check(literals.isNotEmpty()) { "@Query on '$functionName' holds no string literal" }
        return literals.joinToString("")
    }

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
