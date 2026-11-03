package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.UsageLimit
import br.com.xlinx.habblaud.data.WINDOW_FIVE_HOUR
import br.com.xlinx.habblaud.data.WINDOW_SEVEN_DAY
import br.com.xlinx.habblaud.data.parseAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test fun ompAccountListsEveryProviderAndLimit() {
        val snapshot = """{"agents":[],"accounts":[{"id":".omp","provider":"omp","name":"omp","usageStatus":"ok","usage":{"source":"omp","fetchedAt":5,
            "sevenDay":{"utilization":43},"ompProviders":[
              {"provider":"openai-codex","plan":"prolite","limits":[{"label":"7 days","utilization":44,"resetsAt":1793000000000}],"fetchedAt":5},
              {"provider":"ollama","limits":[],"note":"no limits","fetchedAt":5},
              {"provider":"google-antigravity","limits":[{"label":"Gemini","utilization":99.5,"exhausted":true},{"label":"Claude & GPT (shared)"}],"fetchedAt":5}]}}]}"""
        val account = parseAccounts(snapshot).single()
        assertEquals(listOf("openai-codex", "ollama", "google-antigravity"), account.groups.map { it.provider })
        assertEquals(listOf(UsageLimit("7 days", 44.0, 1_793_000_000_000L)), account.groups[0].limits)
        assertEquals("prolite", account.groups[0].plan)
        assertEquals("no limits", account.groups[1].note)
        assertEquals(UsageLimit("Gemini", 99.5, null, exhausted = true), account.groups[2].limits[0])
        assertEquals(null, account.groups[2].limits[1].utilization)
    }

    @Test fun claudeAccountUsesFixedWindowsAndNoQuotaFlag() {
        val snapshot = """{"accounts":[
            {"id":".claude","name":"Conta C","plan":"Max","usageStatus":"stale","usage":{"source":"cache","fetchedAt":1,"fiveHour":{"utilization":21,"resetsAt":9},"sevenDay":{"utilization":37}}},
            {"id":".codex","provider":"codex","name":"Codex","usageStatus":"ok","usage":{"source":"codex","fetchedAt":1,"noQuota":true}},
            {"id":".claude-2","name":"Sem dados","usageStatus":"disabled"}]}"""
        val (claude, codex, empty) = parseAccounts(snapshot)
        assertEquals("claude", claude.provider)
        assertEquals(listOf(WINDOW_FIVE_HOUR to 21.0, WINDOW_SEVEN_DAY to 37.0), claude.groups.single().limits.map { it.label to it.utilization })
        assertEquals("stale", claude.status)
        assertTrue(codex.noQuota && codex.groups.isEmpty())
        assertTrue(empty.groups.isEmpty() && empty.status == "disabled")
    }
}
