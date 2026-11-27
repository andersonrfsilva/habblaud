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

    @Test fun snapshotTransitionsDoNotSynthesizeReplies() {
        val working = watchOf(listOf(session("working")))
        assertTrue(agentEvents(working, listOf(session("idle"))).isEmpty())
        assertTrue(agentEvents(working, listOf(session("done"))).isEmpty())
        assertTrue(agentEvents(working, listOf(session("shell"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("idle"))), listOf(session("idle"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("waiting", "p1"))), listOf(session("working"))).isEmpty())
    }

    @Test fun newSessionAskingPermissionNotifiesButNotAsTurnDone() {
        assertTrue(agentEvents(emptyMap(), listOf(session("waiting", "p1"))).single() is AgentEvent.Permission)
        assertEquals(emptyList<AgentEvent>(), agentEvents(emptyMap(), listOf(session("idle"))))
    }

    @Test fun reconnectDoesNotSynthesizeRepliesOrRepeatOldRequests() {
        // Antes da queda: um agente trabalhando e outro já com o pedido p1 avisado.
        val other = SessionKey("http://servidor:4747", "omp", ".omp", "s2")
        val before = watchOf(listOf(session("working"), session("waiting", "p1").copy(key = other)))
        // Depois da reconexão: o primeiro terminou; o segundo segue no mesmo p1.
        val events = agentEvents(before, listOf(session("idle"), session("waiting", "p1").copy(key = other)))
        assertTrue(events.isEmpty())
    }

    @Test fun selectsTypedNoticesOnlyForMainSessions() {
        val main = session("waiting")
        val sub = main.copy(agentId = "sub", sub = true)
        fun event(fields: String) = noticeEvent(wireJson.parseToJsonElement("{$fields}").jsonObject, listOf(main, sub))

        for ((kind, wait) in listOf("permission" to WaitKind.Permission, "question" to WaitKind.Question, "terminal" to WaitKind.Terminal)) {
            assertEquals(AgentEvent.Permission(main, wait, "teste"),
                event(""""kind":"wait","agentId":"agente","wait":"$kind","reason":"teste""""))
        }
        assertEquals(AgentEvent.Permission(main, WaitKind.Terminal),
            event(""""kind":"wait","agentId":"agente","wait":"terminal","repeat":true"""))
        assertNull(event(""""kind":"wait","agentId":"sub","wait":"terminal""""))
        assertNull(event(""""kind":"wait","agentId":"fora","wait":"terminal""""))
        assertNull(event(""""agentId":"agente","text":"Aviso antigo""""))
        assertNull(event(""""kind":"room","agentId":"agente""""))
    }

    @Test fun typedDoneNeedsARealReplyAndAnIdleMainSession() {
        val idle = session("idle")
        fun event(extra: String, current: SessionItem = idle): AgentEvent? {
            val notice = wireJson.parseToJsonElement("""{"kind":"done","agentId":"agente"$extra}""").jsonObject
            return noticeEvent(notice, listOf(current))
        }

        val reply = ""","excerpt":"  Resposta final  """"
        assertEquals(AgentEvent.TurnDone(idle, 77_000, "Resposta final"), event("""$reply,"durationMs":77000"""))
        assertEquals(AgentEvent.TurnDone(idle, excerpt = "Resposta final"), event("""$reply,"repeat":true"""))
        assertNull(event(""))
        assertNull(event(""","excerpt":"  """"))
        assertNull(event(""","excerpt":42"""))
        assertNull(event("""$reply,"interrupted":true"""))
        assertNull(event(reply, idle.copy(sub = true)))
        for (status in listOf("working", "waiting", "shell", "done", null)) {
            assertNull(event(reply, idle.copy(status = status)), "status=$status")
        }
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
        val rules = NotificationRules()
        val reply = AgentEvent.TurnDone(session("idle"), durationMs = 29_999, excerpt = "Resposta final")
        val wait = AgentEvent.Permission(session("waiting"), WaitKind.Terminal)
        assertFalse(rules.allows(reply))
        assertTrue(rules.allows(reply.copy(durationMs = 30_000)))
        assertTrue(rules.allows(reply.copy(durationMs = null)))
        assertTrue(rules.copy(minTurn = false).allows(reply))
        assertFalse(rules.copy(done = false).allows(reply.copy(durationMs = null)))
        assertTrue(rules.copy(done = false).allows(wait))
        assertFalse(rules.copy(permission = false).allows(wait))
        assertTrue(rules.copy(permission = false).allows(reply.copy(durationMs = null)))
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
        fun waiting(keys: List<SessionKey>) = keys.associateWith { AgentEvent.Permission(session("waiting").copy(key = it)) }
        val reply = AgentEvent.TurnDone(session("idle").copy(key = keys.last()), excerpt = "Resposta final")
        val replies = mapOf(reply.session.key to reply)
        assertTrue(mergeAlertBatch(summary, waiting(keys.take(2)) + replies).isEmpty())
        assertTrue(summary.isEmpty())
        assertEquals(keys.take(3).toSet(), mergeAlertBatch(summary, waiting(keys.take(3)) + replies))
        assertEquals(keys.slice(2..4).toSet(), mergeAlertBatch(summary, waiting(keys.slice(2..4)) + replies))
        assertTrue(mergeAlertBatch(summary, waiting(keys.takeLast(2))).isEmpty())
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
        // Cota do tenant: janelas legadas com o provedor nomeado, não o nome da conta (ZeroClaw).
        val tenant = parseAccounts("""{"accounts":[{"id":"z","name":"ZeroClaw","provider":"zeroclaw","usage":{"ompProvider":"xlinx","fiveHour":{"utilization":5},"sevenDay":{"utilization":61}}}]}""")
        assertEquals(listOf(WeeklyQuota("XLinx", 61.0, null, false)), weeklyQuotas(tenant))
    }
}
