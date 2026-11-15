package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ClipboardHistoryStore]: the capture decision (privacy),
 * dedupe/move-to-top, caps, pinning, removal and clear.
 */
class ClipboardHistoryStoreTest {

    // --- Capture decision -------------------------------------------------

    @Test
    fun `capture accepts plain text in a normal field`() {
        assertTrue(ClipboardHistoryStore.shouldCapture("hello", isSensitive = false, isPasswordField = false))
    }

    @Test
    fun `capture skips sensitive clips`() {
        assertFalse(ClipboardHistoryStore.shouldCapture("hunter2", isSensitive = true, isPasswordField = false))
    }

    @Test
    fun `capture skips clips copied in a password field`() {
        assertFalse(ClipboardHistoryStore.shouldCapture("hunter2", isSensitive = false, isPasswordField = true))
    }

    @Test
    fun `capture skips null and blank text`() {
        assertFalse(ClipboardHistoryStore.shouldCapture(null, isSensitive = false, isPasswordField = false))
        assertFalse(ClipboardHistoryStore.shouldCapture("", isSensitive = false, isPasswordField = false))
        assertFalse(ClipboardHistoryStore.shouldCapture("   ", isSensitive = false, isPasswordField = false))
    }

    // --- Add / dedupe -----------------------------------------------------

    @Test
    fun `add prepends newest first`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        store.add("two", timestamp = 2)
        assertEquals(listOf("two", "one"), store.entries.map { it.text })
    }

    @Test
    fun `re-copy moves entry to top and refreshes timestamp`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        store.add("two", timestamp = 2)
        store.add("one", timestamp = 3)
        assertEquals(listOf("one", "two"), store.entries.map { it.text })
        assertEquals(3L, store.entries[0].timestamp)
        assertEquals(2, store.entries.size)
    }

    @Test
    fun `re-copy preserves pin state`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        store.add("two", timestamp = 2)
        store.togglePin(1) // pin "one"
        store.add("one", timestamp = 3)
        assertTrue(store.entries[0].pinned)
    }

    // --- Caps ---------------------------------------------------------------

    @Test
    fun `size cap evicts oldest unpinned`() {
        val store = ClipboardHistoryStore()
        for (i in 1..(ClipboardHistoryStore.MAX_ENTRIES + 5)) {
            store.add("item-$i", timestamp = i.toLong())
        }
        assertEquals(ClipboardHistoryStore.MAX_ENTRIES, store.entries.size)
        assertEquals("item-${ClipboardHistoryStore.MAX_ENTRIES + 5}", store.entries.first().text)
        assertFalse(store.entries.any { it.text == "item-1" })
    }

    @Test
    fun `pinned entries survive the size cap`() {
        val store = ClipboardHistoryStore()
        store.add("keep-me", timestamp = 1)
        store.togglePin(0)
        for (i in 2..(ClipboardHistoryStore.MAX_ENTRIES + 5)) {
            store.add("item-$i", timestamp = i.toLong())
        }
        assertTrue(store.entries.any { it.text == "keep-me" && it.pinned })
        assertEquals(ClipboardHistoryStore.MAX_ENTRIES, store.entries.count { !it.pinned })
    }

    @Test
    fun `long entries are truncated and marked`() {
        val store = ClipboardHistoryStore()
        val long = "a".repeat(ClipboardHistoryStore.MAX_ENTRY_LENGTH + 100)
        store.add(long, timestamp = 1)
        val entry = store.entries.single()
        assertEquals(ClipboardHistoryStore.MAX_ENTRY_LENGTH, entry.text.length)
        assertTrue(entry.truncated)
    }

    @Test
    fun `short entries are not marked truncated`() {
        val store = ClipboardHistoryStore()
        store.add("short", timestamp = 1)
        assertFalse(store.entries.single().truncated)
    }

    // --- Remove / pin / clear ----------------------------------------------

    @Test
    fun `removeAt deletes only the target`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        store.add("two", timestamp = 2)
        assertTrue(store.removeAt(0))
        assertEquals(listOf("one"), store.entries.map { it.text })
        assertFalse(store.removeAt(5))
    }

    @Test
    fun `togglePin flips state and validates index`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        assertTrue(store.togglePin(0))
        assertTrue(store.entries[0].pinned)
        assertTrue(store.togglePin(0))
        assertFalse(store.entries[0].pinned)
        assertFalse(store.togglePin(3))
    }

    @Test
    fun `unpinning at capacity re-applies the cap`() {
        val store = ClipboardHistoryStore()
        store.add("oldest", timestamp = 1)
        store.togglePin(0) // pin the oldest so it survives the fill below
        for (i in 2..(ClipboardHistoryStore.MAX_ENTRIES + 1)) {
            store.add("item-$i", timestamp = i.toLong())
        }
        assertEquals(ClipboardHistoryStore.MAX_ENTRIES, store.entries.count { !it.pinned })
        // Unpin: unpinned count would exceed the cap, so the oldest
        // unpinned entry (the just-unpinned one) is evicted.
        val idx = store.entries.indexOfFirst { it.text == "oldest" }
        assertTrue(store.togglePin(idx))
        assertEquals(ClipboardHistoryStore.MAX_ENTRIES, store.entries.count { !it.pinned })
        assertFalse(store.entries.any { it.text == "oldest" })
    }

    @Test
    fun `clear empties the store`() {
        val store = ClipboardHistoryStore()
        store.add("one", timestamp = 1)
        store.add("two", timestamp = 2)
        store.clear()
        assertTrue(store.entries.isEmpty())
    }

    // --- Load -----------------------------------------------------------------

    @Test
    fun `replaceAll restores entries and re-applies caps`() {
        val store = ClipboardHistoryStore()
        val loaded = (1..(ClipboardHistoryStore.MAX_ENTRIES + 3)).map { i ->
            ClipboardEntry(text = "old-$i", timestamp = i.toLong())
        }
        store.replaceAll(loaded)
        assertEquals(ClipboardHistoryStore.MAX_ENTRIES, store.entries.size)

        val longStore = ClipboardHistoryStore()
        longStore.replaceAll(
            listOf(ClipboardEntry(text = "b".repeat(ClipboardHistoryStore.MAX_ENTRY_LENGTH + 1), timestamp = 1)),
        )
        assertEquals(ClipboardHistoryStore.MAX_ENTRY_LENGTH, longStore.entries.single().text.length)
        assertTrue(longStore.entries.single().truncated)
    }
}
