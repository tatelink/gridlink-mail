package app.gridlink.core.data.db

import app.gridlink.core.data.mail.NOT_SEARCHED_ROLES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_21_22] re-seeds the search index and has to skip the folders a search must never
 * surface. Every other place that decides this reads [NOT_SEARCHED_ROLES]; the migration cannot,
 * because a `Migration` executes raw SQL with no bound parameters, so it spells the roles out in
 * [SEARCH_SEED_EXCLUDED_ROLES_SQL].
 *
 * 🔴 That makes it the one copy of the rule that can silently fall behind. Adding a fourth role to
 * [NOT_SEARCHED_ROLES] would tighten the live search and the crawl and leave this migration seeding
 * that folder's mail straight back into the index on upgrade — mail the user deleted, reappearing in
 * search, on exactly one code path, months later. This test is what makes that a red build instead.
 *
 * It reads the SAME constant the migration interpolates, so it cannot pass by agreeing with a stale
 * copy of itself.
 */
class SearchIndexMigrationRolesTest {

    @Test
    fun `every excluded role is named in the migration's re-seed`() {
        NOT_SEARCHED_ROLES.forEach { role ->
            assertTrue(
                "MIGRATION_21_22 does not exclude '$role'. NOT_SEARCHED_ROLES gained a role and " +
                    "SEARCH_SEED_EXCLUDED_ROLES_SQL did not: on upgrade, that folder's mail would " +
                    "be seeded back into the search index.",
                SEARCH_SEED_EXCLUDED_ROLES_SQL.contains("'$role'"),
            )
        }
    }

    @Test
    fun `the migration excludes nothing that is not an excluded role`() {
        val named = Regex("'([^']+)'").findAll(SEARCH_SEED_EXCLUDED_ROLES_SQL)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "MIGRATION_21_22 excludes a different set than NOT_SEARCHED_ROLES. A folder excluded " +
                "here but searched everywhere else is mail that is findable until the next upgrade " +
                "and unfindable after it.",
            NOT_SEARCHED_ROLES,
            named,
        )
    }

    /**
     * The migration lowercases the column before comparing (`LOWER(TRIM(...)) IN (...)`), so a role
     * literal with a capital in it would match nothing and quietly re-seed that folder.
     */
    @Test
    fun `the role literals are lowercase`() {
        assertEquals(
            "SEARCH_SEED_EXCLUDED_ROLES_SQL is compared against LOWER(...), so an uppercase " +
                "literal can never match.",
            SEARCH_SEED_EXCLUDED_ROLES_SQL.lowercase(),
            SEARCH_SEED_EXCLUDED_ROLES_SQL,
        )
    }
}
