package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import br.com.xlinx.habblaud.data.Avatar
import br.com.xlinx.habblaud.data.AvatarCache
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.modelBrand
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp

/** Avatar da sessão; a partir de 32 dp, com o símbolo pequeno da família do modelo no canto inferior direito. */
@Composable
internal fun CharacterAvatar(api: HabblaudApi, session: SessionItem, size: Dp) {
    val brand = if (size >= 32.dp) modelBrand(session.model) else null
    if (brand == null) return CharacterAvatar(api, session.avatar, session.characterName ?: session.title, size)
    val (icon, tint) = when (brand) {
        "claude" -> Res.drawable.ic_model_claude to Color(0xFFD97757)
        "gemini" -> Res.drawable.ic_model_gemini to Color(0xFF8E75B2)
        else -> Res.drawable.ic_model_openai to MaterialTheme.colorScheme.onSurface
    }
    val badge = size * 0.42f
    Box(Modifier.size(size)) {
        CharacterAvatar(api, session.avatar, session.characterName ?: session.title, size)
        Box(Modifier.align(Alignment.BottomEnd).size(badge).clip(CircleShape).background(MaterialTheme.colorScheme.surface).testTag("model-badge:$brand"), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = session.model, Modifier.size(badge * 0.7f), tint = tint)
        }
    }
}

@Composable
internal fun CharacterAvatar(api: HabblaudApi, avatar: Avatar?, name: String, size: Dp) {
    val cache = remember(api) { AvatarCache(api) }
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, avatar, api) {
        value = null
        value = avatar?.let { cache.load(it) }
    }
    val colors = listOf(Color(0xFFD3EEE3), Color(0xFFDCE7F7), Color(0xFFF7E4D6), Color(0xFFEAE0F4), Color(0xFFF5EABF))
    Box(Modifier.size(size).clip(CircleShape).background(colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]), contentAlignment = Alignment.Center) {
        if (image == null) Text(name.take(1).uppercase(), color = Color(0xFF002117), style = MaterialTheme.typography.titleMedium)
        else Image(image!!, contentDescription = null, modifier = Modifier.size(size), filterQuality = FilterQuality.None)
    }
}
