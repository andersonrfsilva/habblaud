package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class ZeroClawCapabilitiesTest {
    private val key = SessionKey("https://fixture.test", "zeroclaw", "zeroclaw", "00000000-0000-4000-8000-000000000008")
    private fun item() = SessionItem(key, "agent", "Zélia", "/fixture/projeto", "working", null, canMessage = true)
    private val connected = ReadingState(connection = "connected", messagesAllowed = true, messageGuard = true)

    @Test fun resourceGatesPreserveOmpOnlyToolsAndAccess() {
        for (provider in listOf("claude", "codex", "omp", "zeroclaw", "unknown")) {
            val selected = item().copy(key = key.copy(provider = provider))
            assertEquals(provider == "omp" || provider == "zeroclaw", sessionControlsAvailable(selected, connected))
            assertEquals(provider == "omp", ompTools(provider))
            assertFalse(sessionControlsAvailable(selected.copy(sub = true), connected))
            assertFalse(sessionControlsAvailable(selected.copy(agentId = null), connected))
            assertFalse(sessionControlsAvailable(selected.copy(canMessage = false), connected))
            assertFalse(sessionControlsAvailable(selected, connected.copy(connection = "offline")))
            assertFalse(sessionControlsAvailable(selected, connected.copy(messagesAllowed = false)))
            assertFalse(sessionControlsAvailable(selected, connected.copy(messageGuard = false)))
        }
        assertFalse(supportsSessionControl(null))
        assertFalse(ompTools(null))
        assertEquals(emptyList(), deliveryModes(null))
    }

    @Test fun zeroClawDeliveryNormalizesOnlyAtSend() {
        assertEquals(listOf("followUp", "steer"), deliveryModes("zeroclaw"))
        for (preference in listOf("aside", "invalid", "followUp")) assertEquals("followUp", messageDelivery("zeroclaw", preference))
        assertEquals("steer", messageDelivery("zeroclaw", "steer"))
        assertEquals("aside", messageDelivery("omp", "aside"))
        assertNull(messageDelivery("claude", "aside"))
    }

    @Test fun bothContextPositionsRejectZeroAndPositiveZeroClawValues() {
        for (tokens in listOf(null, 0L, 120000L)) {
            assertFalse(reliableContext("zeroclaw", tokens, 200000))
        }
        assertTrue(reliableContext("omp", 0, 200000))
        assertTrue(reliableContext("omp", 120000, 200000))
    }

    @Test fun runtimeAndHttpSummaryFailClosedWithoutTiny() {
        assertEquals("zeroclaw", catalogRuntime("{\"provider\":\"zeroclaw\",\"models\":[],\"defaultModel\":\"\"}"))
        assertNull(catalogRuntime("{}"))
        assertNull(catalogRuntime("{\"provider\":\"unknown\"}"))
        assertTrue(summaryAvailable(httpSummaryAvailable("{\"summaryHttp\":true}"), null))
        assertFalse(summaryAvailable(httpSummaryAvailable("{}"), null))
        assertTrue(summaryAvailable(httpSummaryAvailable("{}"), mapOf("tiny" to "legacy/model")))
        assertFalse(ompTools(catalogRuntime("{\"provider\":\"zeroclaw\"}")))
    }

    @Test fun resumeNeverCrossesRuntimesAndPreservesIdentity() {
        val closed = item().copy(agentId = null)
        assertTrue(canResumeCast(closed, "zeroclaw"))
        assertFalse(canResumeCast(closed, "omp"))
        assertFalse(canResumeCast(closed, null))
        assertFalse(canResumeCast(item(), "zeroclaw"))
        assertFalse(canResumeCast(closed.copy(key = key.copy(provider = "omp")), "zeroclaw"))
        val owner = wireJson.parseToJsonElement("{\"id\":\"zelia\",\"account\":\"zeroclaw\",\"last\":{\"/fixture/projeto\":\"${key.sessionId}\"}}").jsonObject
        assertEquals(owner, castOwner(listOf(owner), key))
        assertNull(castOwner(listOf(owner), key.copy(account = "other")))
        assertEquals(key, SessionKey.deserialize(key.serialize()))
    }

    private val modelCatalog = """
        {"provider":"zeroclaw","models":[
            {"alias":"m_000000000001","model":"fixture/model-legivel"},
            {"alias":"m_000000000002","model":"fixture/model-alternativo"}
        ],"defaultModel":"m_000000000001"}
    """.trimIndent()

    @Test fun castCatalogParsesModelsAndOnlyOffersChoiceForMultipleZeroClawModels() {
        val catalog = parseCastModelCatalog(modelCatalog)
        assertEquals("zeroclaw", catalog.runtime)
        assertEquals(listOf(
            CastModel("m_000000000001", "fixture/model-legivel"),
            CastModel("m_000000000002", "fixture/model-alternativo"),
        ), catalog.models)
        assertEquals("m_000000000001", catalog.defaultModel)
        assertTrue(catalog.canChoose)
        assertFalse(catalog.copy(models = catalog.models.take(1)).canChoose)
        assertFalse(catalog.copy(models = emptyList()).canChoose)
        assertFalse(catalog.copy(runtime = "omp").canChoose)
        assertFalse(catalog.copy(runtime = null).canChoose)
        assertEquals(emptyList(), parseCastModelCatalog(modelCatalog.replace("zeroclaw", "omp")).models)
        assertEquals(CastModelCatalog(null, emptyList(), null), parseCastModelCatalog("{}"))
    }

    @Test fun castModelPreselectionResolvesAliasOrFullIdAndFallsBackToDefault() {
        val catalog = parseCastModelCatalog(modelCatalog)
        assertEquals("m_000000000002", preselectedCastModel(catalog, "m_000000000002"))
        assertEquals("m_000000000002", preselectedCastModel(catalog, "fixture/model-alternativo"))
        assertEquals("m_000000000001", preselectedCastModel(catalog, null))
        assertEquals("m_000000000001", preselectedCastModel(catalog, "unknown/model"))
        assertNull(preselectedCastModel(catalog.copy(defaultModel = "unknown"), null))
    }

    @Test fun newZeroClawStartSendsChosenAliasButOmpAndSmallCatalogsOmitModel() {
        val catalog = parseCastModelCatalog(modelCatalog)
        val alias = "m_000000000002"
        assertEquals("""{"model":"$alias"}""", newCastBody(catalog, false, alias).toString())
        assertEquals("""{"mode":"new","model":"$alias"}""", newCastBody(catalog, true, alias).toString())
        for (withoutChoice in listOf(
            catalog.copy(runtime = "omp"),
            catalog.copy(runtime = null),
            catalog.copy(models = catalog.models.take(1)),
            catalog.copy(models = emptyList()),
        )) {
            assertEquals("{}", newCastBody(withoutChoice, false, alias).toString())
            assertEquals("""{"mode":"new"}""", newCastBody(withoutChoice, true, alias).toString())
        }
        assertEquals("{}", newCastBody(catalog, false).toString())
        assertEquals("{}", newCastBody(catalog, false, "fixture/model-alternativo").toString())
        assertEquals("{}", newCastBody(catalog, false, "unknown").toString())
    }

    @Test fun newNavigationUsesSnapshotWithoutOldSessionAndRejectsOtherIdentity() {
        val next = item().copy(key = key.copy(sessionId = "new"))
        assertEquals(next, newSessionTarget(listOf(next), key, "agent"))
        assertNull(newSessionTarget(listOf(next.copy(key = next.key.copy(provider = "omp"))), key, "agent"))
        assertNull(newSessionTarget(listOf(next.copy(key = next.key.copy(account = "other"))), key, "agent"))
        assertNull(newSessionTarget(listOf(next.copy(key = next.key.copy(origin = "other"))), key, "agent"))
    }
}
