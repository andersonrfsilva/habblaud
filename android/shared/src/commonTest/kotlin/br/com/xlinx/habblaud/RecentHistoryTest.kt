package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.RecentFooter
import br.com.xlinx.habblaud.data.RecentLoad
import br.com.xlinx.habblaud.data.RecentWindow
import br.com.xlinx.habblaud.data.recentFooter
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentHistoryTest {
    @Test fun usaMaiorJanelaECapacidadeConhecidasPorServidor() {
        val legacy = RecentWindow(requestedStep = 2, days = 7)
        val updated = RecentWindow(requestedStep = 2, days = 30, maxStep = 4)
        val stepTwo = RecentLoad().begin().finish(anyLoaded = true)
        assertEquals(RecentFooter(30, true), recentFooter(listOf(legacy, updated), stepTwo))
        assertEquals(RecentFooter(7, false), recentFooter(listOf(legacy), stepTwo))
        assertEquals(RecentFooter(null, false), recentFooter(emptyList(), RecentLoad()))
    }

    @Test fun legadoBemSucedidoMantemDegrauEBotaoQuandoServidorNovoFalha() {
        val legacy = RecentWindow(requestedStep = 3, days = 7)
        val previous = RecentWindow(requestedStep = 2, days = 30, maxStep = 4)
        val load = RecentLoad(step = 2).begin().finish(anyLoaded = true)
        assertEquals(3, load.step)
        assertEquals(RecentFooter(30, true), recentFooter(listOf(previous, legacy), load))
        assertEquals(4, load.begin().step)
    }

    @Test fun voltaSomenteQuandoNenhumServidorCarrega() {
        val pending = RecentLoad(step = 2).begin()
        assertEquals(RecentLoad(step = 3, loading = true), pending)
        assertEquals(pending, pending.begin())
        assertEquals(RecentLoad(step = 3), pending.finish(anyLoaded = true))
        val failed = pending.finish(anyLoaded = false)
        assertEquals(RecentLoad(step = 2, failed = true), failed)
        assertEquals(pending, failed.begin())
        val previous = RecentWindow(requestedStep = 2, days = 30, maxStep = 4)
        assertEquals(RecentFooter(30, true), recentFooter(listOf(previous), failed))
    }

    @Test fun nuncaPassaDoQuartoDegrau() {
        val last = RecentLoad(step = 4)
        val stale = RecentWindow(requestedStep = 4, days = 365, maxStep = 6)
        assertEquals(last, last.begin())
        assertEquals(RecentFooter(365, false), recentFooter(listOf(stale), last))
    }
}
