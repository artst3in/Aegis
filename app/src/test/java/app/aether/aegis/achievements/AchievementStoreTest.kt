package app.aether.aegis.achievements

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the earned-badge store (SPEC_TESTING #24).
 *
 * unlockOnce backs the "award a badge exactly once" contract — the
 * security flows drop Achievements.unlock(X) at their success points, so
 * a non-idempotent store would re-fire the trusted-contact badge
 * broadcast on every call. SharedPreferences-backed → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AchievementStoreTest {

    private lateinit var store: AchievementStore
    private val a = Achievement.entries[0]
    private val b = Achievement.entries[1]

    @Before
    fun setUp() {
        store = AchievementStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun unlockOnce_is_true_first_then_false() {
        assertTrue("first unlock awards the badge", store.unlockOnce(a))
        assertFalse("re-unlocking the same badge must be a no-op", store.unlockOnce(a))
    }

    @Test
    fun earnedIds_accumulates_distinct_badges() {
        assertTrue(store.earnedIds().isEmpty())
        store.unlockOnce(a)
        store.unlockOnce(b)
        store.unlockOnce(a) // dup
        assertEquals(setOf(a.id, b.id), store.earnedIds())
    }

    @Test
    fun earnedAt_is_null_until_unlocked() {
        assertNull(store.earnedAt(a))
        store.unlockOnce(a)
        assertNotNull("earnedAt must be stamped on unlock", store.earnedAt(a))
    }

    @Test
    fun earned_set_persists_across_instances() {
        store.unlockOnce(a)
        val reborn = AchievementStore(ApplicationProvider.getApplicationContext())
        assertTrue("earned badges must survive a restart", reborn.earnedIds().contains(a.id))
    }
}
