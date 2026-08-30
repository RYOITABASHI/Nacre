package space.manus.nacre.ime.input

import org.junit.Assert.*
import org.junit.Test

/**
 * isShellyDevModeTarget() / looksLikeCodeTail() — the two pure functions behind
 * "Shelly Dev Mode": auto-detecting focus on Shelly (dev.shelly.terminal) and
 * biasing predictions away from interrupting code/command typing.
 */
class InputEngineShellyDevModeTest {

    // --- isShellyDevModeTarget ---

    @Test
    fun `recognizes Shelly's exact package name`() {
        assertTrue(isShellyDevModeTarget("dev.shelly.terminal"))
    }

    @Test
    fun `does not match null package name`() {
        assertFalse(isShellyDevModeTarget(null))
    }

    @Test
    fun `does not match unrelated terminal apps`() {
        // isTerminalApp() deliberately uses a loose `contains("terminal")` match
        // for cursor-movement purposes; isShellyDevModeTarget must NOT be that
        // loose — it is a stronger feature flag scoped to Shelly specifically.
        assertFalse(isShellyDevModeTarget("com.termux"))
        assertFalse(isShellyDevModeTarget("org.connectbot"))
        assertFalse(isShellyDevModeTarget("com.example.terminal"))
    }

    @Test
    fun `does not match a package name that merely contains Shelly's package as a substring`() {
        assertFalse(isShellyDevModeTarget("dev.shelly.terminal.debug"))
        assertFalse(isShellyDevModeTarget("evil.dev.shelly.terminal"))
    }

    @Test
    fun `is case-sensitive (Android package names are case-sensitive)`() {
        assertFalse(isShellyDevModeTarget("DEV.SHELLY.TERMINAL"))
    }

    // --- looksLikeCodeTail ---

    @Test
    fun `treats an ASCII letter tail as code`() {
        assertTrue(looksLikeCodeTail("cd /usr/loc"))
    }

    @Test
    fun `treats a trailing symbol as code`() {
        assertTrue(looksLikeCodeTail("git status --short -"))
        assertTrue(looksLikeCodeTail("a != b"))
    }

    @Test
    fun `treats a trailing digit as code`() {
        assertTrue(looksLikeCodeTail("port 808"))
    }

    @Test
    fun `ignores trailing whitespace when checking the last real character`() {
        assertTrue(looksLikeCodeTail("cd /usr/loc  "))
        assertTrue(looksLikeCodeTail("cd /usr/loc\t"))
    }

    @Test
    fun `does not treat a Japanese tail as code`() {
        assertFalse(looksLikeCodeTail("こんにちは"))
        assertFalse(looksLikeCodeTail("ありがとう "))
    }

    @Test
    fun `returns false for empty or all-whitespace input`() {
        assertFalse(looksLikeCodeTail(""))
        assertFalse(looksLikeCodeTail("   "))
    }
}
