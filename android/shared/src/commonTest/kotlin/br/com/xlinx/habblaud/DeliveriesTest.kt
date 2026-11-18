package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlin.test.*

class DeliveriesTest {
    @Test fun parsesValidEntriesAndFiltersTheFullSessionKeyInServerOrder() {
        val all = parseDeliveries("""{"deliveries":[
            {"id":"new","provider":"claude","account":"a","sessionId":"s","request":"Pedido","headline":"Pronto","summary":"Resumo","text":"Resposta","finishedAt":1794348237574,"attachments":[{"id":"f","name":"arquivo","kind":"file","caption":"Legenda"},{"id":"nameless"},{"name":"idless"}]},
            {"id":"other-session","provider":"claude","account":"a","sessionId":"other"},
            {"id":"old","account":"a","sessionId":"s"},
            {"id":"other-account","provider":"claude","account":"b","sessionId":"s"},
            {"id":"other-provider","provider":"omp","account":"a","sessionId":"s"},
            {"account":"a","sessionId":"s"},
            {"id":"no-account","sessionId":"s"},
            {"id":"no-session","account":"a"}
        ]}""")
        assertEquals(5, all.size)
        assertEquals(Delivery("new", "claude", "a", "s", "Pedido", "Pronto", "Resumo", "Resposta", 1794348237574,
            listOf(DeliveryAttachment("f", "arquivo", "file", "Legenda"))), all.first())
        assertEquals(Delivery("old", "claude", "a", "s", "", "", null, "", 0, emptyList()), all[2])
        assertEquals(listOf("new", "old"), deliveriesOf(all, SessionKey("http://server", "claude", "a", "s")).map { it.id })
    }

    @Test fun rejectsNonObject() {
        assertFailsWith<IllegalArgumentException> { parseDeliveries("[]") }
    }
}
