package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ReadingState
import br.com.xlinx.habblaud.data.conversationConnected
import br.com.xlinx.habblaud.data.streamFailure
import org.junit.Assert.*
import org.junit.Test

class ReadingConnectionTest {
    @Test fun conversationFailureDoesNotDisconnectSnapshotStream() {
        val state = ReadingState(connection = "connected", messagesAllowed = true, messageGuard = true)
        val failed = state.streamFailure(null, conversation = true)
        assertEquals("connected", failed.connection)
        assertEquals("network", failed.readingError)
        assertTrue(failed.messagesAllowed && failed.messageGuard)
        val reconnected = failed.conversationConnected()
        assertEquals("connected", reconnected.connection)
        assertNull(reconnected.readingError)
    }

    @Test fun terminalForbiddenStaysSeparateFromSnapshotFailure() {
        val state = ReadingState(connection = "connected").streamFailure(403, conversation = true)
        assertEquals("connected", state.connection)
        assertEquals("forbidden", state.readingError)
        assertEquals("forbidden", state.conversationConnected().readingError)
        val disconnected = state.streamFailure(null, conversation = false)
        assertEquals("offline", disconnected.connection)
        assertEquals("network", disconnected.error)
        assertEquals("forbidden", disconnected.readingError)
    }
}
