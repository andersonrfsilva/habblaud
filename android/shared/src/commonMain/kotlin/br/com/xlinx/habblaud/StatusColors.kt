package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Laranja de alerta legível nos dois temas (contraste ≥ 4,5:1 sobre o fundo de cada um). */
@Composable
internal fun warningColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFB870) else Color(0xFF8A5100)

/** Cor do ponto de conexão: tema para conectado e desconectado, alerta enquanto conecta. */
@Composable
internal fun connectionColor(connection: String?): Color = when (connection) {
    "connected" -> MaterialTheme.colorScheme.primary
    "connecting" -> warningColor()
    else -> MaterialTheme.colorScheme.error
}
