package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ReadingState
import kotlin.test.*

class ReadingActionsTest {
    @Test fun connectedServerActionsStayAvailableWhenAnotherServerIsOffline() {
        val states = mapOf(
            "https://a.example" to ReadingState(connection = "connected", terminalAllowed = true),
            "https://b.example" to ReadingState(connection = "offline"),
        )
        val actionOrigin = "https://a.example"
        val needsLogin = emptySet<String>()
        assertTrue(actionsAvailable(states.getValue(actionOrigin), actionOrigin in needsLogin, selectedPermission = false))
    }

    @Test fun offlineServerActionsAreUnavailable() {
        val state = ReadingState(connection = "offline", terminalAllowed = true)
        assertFalse(actionsAvailable(state, loginRequired = false, selectedPermission = true))
    }

    @Test fun serverRequiringLoginCannotUseStaleConnectedState() {
        val state = ReadingState(connection = "connected", terminalAllowed = true)
        assertFalse(actionsAvailable(state, loginRequired = true, selectedPermission = true))
    }

    @Test fun connectedServerWithoutTerminalOrSelectedPermissionIsUnavailable() {
        val state = ReadingState(connection = "connected", terminalAllowed = false)
        assertFalse(actionsAvailable(state, loginRequired = false, selectedPermission = false))
    }

    @Test fun selectedPermissionAllowsActionsWithoutTerminalAccess() {
        val state = ReadingState(connection = "connected", terminalAllowed = false)
        assertTrue(actionsAvailable(state, loginRequired = false, selectedPermission = true))
    }
}
