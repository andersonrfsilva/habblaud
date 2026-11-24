package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class NotificationRulesTest {
    private val key = SessionKey("http://servidor:4747", "omp", ".omp", "s1")
    private fun session(status: String?, permission: String? = null) =
        SessionItem(key, "agente", "Título", "/p", status, null, permission != null, permissionId = permission)

    @Test fun newPermissionNotifiesOnce() {
        val before = watchOf(listOf(session("working")))
        val asked = listOf(session("waiting", "p1"))
        assertTrue(agentEvents(before, asked).single() is AgentEvent.Permission)
        assertTrue(agentEvents(watchOf(asked), asked).isEmpty())
        assertTrue(agentEvents(watchOf(asked), listOf(session("waiting", "p2"))).single() is AgentEvent.Permission)
    }

    @Test fun workingToIdleIsTurnDoneButOtherChangesAreNot() {
        assertTrue(agentEvents(watchOf(listOf(session("working"))), listOf(session("idle"))).single() is AgentEvent.TurnDone)
        assertTrue(agentEvents(watchOf(listOf(session("working"))), listOf(session("shell"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("idle"))), listOf(session("idle"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("waiting", "p1"))), listOf(session("working"))).isEmpty())
    }

    @Test fun newSessionAskingPermissionNotifiesButNotAsTurnDone() {
        assertTrue(agentEvents(emptyMap(), listOf(session("waiting", "p1"))).single() is AgentEvent.Permission)
        assertEquals(emptyList<AgentEvent>(), agentEvents(emptyMap(), listOf(session("idle"))))
    }

    @Test fun changesDuringADisconnectNotifyAgainstTheLastBaselineWithoutRepeatingOldRequests() {
        // Antes da queda: um agente trabalhando e outro já com o pedido p1 avisado.
        val other = SessionKey("http://servidor:4747", "omp", ".omp", "s2")
        val before = watchOf(listOf(session("working"), session("waiting", "p1").copy(key = other)))
        // Depois da reconexão: o primeiro terminou; o segundo segue no mesmo p1.
        val events = agentEvents(before, listOf(session("idle"), session("waiting", "p1").copy(key = other)))
        assertEquals(listOf(key), events.map { it.session.key })
        assertTrue(events.single() is AgentEvent.TurnDone)
    }

    @Test fun selectsTypedNoticesOnlyForMainSessions() {
        val main = session("waiting")
        val sub = main.copy(agentId = "sub", sub = true)
        fun event(fields: String) = noticeEvent(wireJson.parseToJsonElement("{$fields}").jsonObject, listOf(main, sub))

        for ((kind, wait) in listOf("permission" to WaitKind.Permission, "question" to WaitKind.Question, "terminal" to WaitKind.Terminal)) {
            assertEquals(AgentEvent.Permission(main, wait, "teste"),
                event(""""kind":"wait","agentId":"agente","wait":"$kind","reason":"teste""""))
        }
        assertEquals(AgentEvent.TurnDone(main, 77_000, interrupted = true),
            event(""""kind":"done","agentId":"agente","durationMs":77000,"interrupted":true"""))
        assertEquals(AgentEvent.TurnDone(main), event(""""kind":"done","agentId":"agente","repeat":true"""))
        assertEquals(AgentEvent.Permission(main, WaitKind.Terminal),
            event(""""kind":"wait","agentId":"agente","wait":"terminal","repeat":true"""))
        assertNull(event(""""kind":"done","agentId":"sub""""))
        assertNull(event(""""kind":"done","agentId":"fora""""))
        assertNull(event(""""agentId":"agente","text":"Aviso antigo""""))
        assertNull(event(""""kind":"room","agentId":"agente""""))
    }

    @Test fun queuedPermissionNotifiesButOnlyTheHeadCanOpenApproval() {
        val head = session("waiting", "p1")
        val active = session("waiting", "p2")
        val other = key.copy(sessionId = "different")
        val json = wireJson.parseToJsonElement("""{"kind":"wait","agentId":"agente","wait":"permission","requestId":"p2"}""").jsonObject
        assertEquals("p2", assertIs<AgentEvent.Permission>(noticeEvent(json, listOf(active))).requestId)
        assertEquals("p2", assertIs<AgentEvent.Permission>(noticeEvent(json, listOf(head))).requestId)
        assertNull(noticeEvent(json, listOf(session("waiting"))))
        assertNull(notificationPermission(head, key, "p2"))
        assertEquals(active, notificationPermission(active, key, "p2"))
        assertNull(notificationPermission(active, key, "p1"))
        assertNull(notificationPermission(active, other, "p2"))
        assertNull(notificationPermission(active.copy(permissionId = null), key, "p2"))
        val before = watchOf(listOf(head))
        val after = listOf(active)
        assertTrue(withdrawnSessions(before, watchOf(after)).isEmpty())
        assertEquals(AgentEvent.Permission(active), agentEvents(before, after, useFallback = false).single())
        assertTrue(agentEvents(watchOf(after), after, useFallback = false).isEmpty())
        assertTrue(agentEvents(emptyMap(), after, useFallback = false).isEmpty())
    }

    @Test fun legacyAndReconnectUseDiffWithTerminalWaitAndWithdrawal() {
        assertTrue(useSnapshotDiff(firstSnapshot = false, typedNoticeSeen = false))
        assertFalse(useSnapshotDiff(firstSnapshot = false, typedNoticeSeen = true))
        assertTrue(useSnapshotDiff(firstSnapshot = true, typedNoticeSeen = true))

        val before = watchOf(listOf(session("working")))
        val waiting = session("waiting")
        assertEquals(AgentEvent.Permission(waiting, WaitKind.Terminal), agentEvents(before, listOf(waiting)).single())
        assertTrue(agentEvents(watchOf(listOf(waiting)), listOf(waiting)).isEmpty())
        assertEquals(listOf(key), withdrawnSessions(watchOf(listOf(waiting)), before))
        assertEquals(listOf(key), withdrawnSessions(watchOf(listOf(session("waiting", "p1"))), watchOf(listOf(waiting))))
        assertTrue(withdrawnSessions(watchOf(listOf(waiting)), watchOf(listOf(waiting))).isEmpty())
    }

    @Test fun shortTurnLimitKeepsUnknownAndAllowsBoundary() {
        val complete = session("done")
        val rules = NotificationRules()
        val short = AgentEvent.TurnDone(complete, 29_999)
        assertFalse(rules.allows(short))
        assertTrue(rules.allows(AgentEvent.TurnDone(complete, 30_000)))
        assertTrue(rules.allows(AgentEvent.TurnDone(complete)))
        assertTrue(rules.copy(minTurn = false).allows(short))
        assertFalse(rules.copy(done = false).allows(AgentEvent.TurnDone(complete)))
        assertFalse(rules.copy(permission = false).allows(AgentEvent.Permission(session("waiting"), WaitKind.Terminal)))
    }

    @Test fun suppressesOnlyTheVisibleSession() {
        val other = key.copy(origin = "http://outro:4747")
        assertTrue(suppressNotification(key, visible = true, open = key))
        assertFalse(suppressNotification(key, visible = true, open = other))
        assertFalse(suppressNotification(key, visible = true, open = null))
        assertFalse(suppressNotification(key, visible = false, open = key))
    }

    @Test fun mergesSuccessiveBatchesWithoutDroppingOrDoubleCountingSessions() {
        val keys = (1..6).map { key.copy(sessionId = "s$it") }
        val summary = linkedSetOf<SessionKey>()
        assertFalse(mergeAlertBatch(summary, keys.take(2).toSet()))
        assertTrue(summary.isEmpty())
        assertTrue(mergeAlertBatch(summary, keys.take(3).toSet()))
        assertTrue(mergeAlertBatch(summary, keys.slice(2..4).toSet()))
        assertFalse(mergeAlertBatch(summary, keys.takeLast(2).toSet()))
        assertEquals(keys.take(5).toSet(), summary)
    }

    @Test fun weeklyQuotaChoosesMostUsedSevenDayWindowAcrossServers() {
        val first = parseAccounts("""
            {"accounts":[
              {"id":"one","usage":{"ompProviders":[
                {"provider":"openai","limits":[
                  {"label":"short","windowMs":18000000,"utilization":100},
                  {"label":"weekly","windowMs":604800000,"utilization":70},
                  {"label":"other weekly","windowMs":604800000,"utilization":80,"resetsAt":1}
                ]}
              ]}},
              {"id":"legacy","name":"Minha conta","usage":{
                "fiveHour":{"utilization":100},
                "sevenDay":{"utilization":38},
                "sevenDayOpus":{"utilization":65,"resetsAt":2}
              }}
            ]}
        """)
        val second = parseAccounts("""
            {"accounts":[{"id":"two","usage":{"ompProviders":[
              {"provider":"openai","limits":[
                {"label":"weekly","windowMs":604800000,"utilization":95,"resetsAt":3,"exhausted":true}
              ]},
              {"provider":"another","limits":[{"label":"no duration","utilization":50}]}
            ]}}]}
        """)
        assertEquals(
            listOf(WeeklyQuota(providerLabel("openai"), 95.0, 3, true), WeeklyQuota("Minha conta", 65.0, 2, false)),
            weeklyQuotas(first + second),
        )
        assertEquals(listOf(80.0, 65.0), weeklyQuotas(first).map { it.utilization })
        assertTrue(weeklyQuotas(emptyList()).isEmpty())
    }
}
