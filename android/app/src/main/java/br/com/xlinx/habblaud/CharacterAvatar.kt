package br.com.xlinx.habblaud

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import br.com.xlinx.habblaud.data.Avatar
import br.com.xlinx.habblaud.data.AvatarCache
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.SessionItem

@Composable
internal fun CharacterAvatar(api: HabblaudApi, session: SessionItem, size: Dp) {
    CharacterAvatar(api, session.avatar, session.characterName ?: session.title, size)
}

@Composable
internal fun CharacterAvatar(api: HabblaudApi, avatar: Avatar?, name: String, size: Dp) {
    val context = LocalContext.current
    val cache = remember(api) { AvatarCache(context, api) }
    val image by produceState<android.graphics.Bitmap?>(null, avatar, api) {
        value = null
        value = avatar?.let { cache.load(it) }
    }
    val colors = listOf(Color(0xFFD3EEE3), Color(0xFFDCE7F7), Color(0xFFF7E4D6), Color(0xFFEAE0F4), Color(0xFFF5EABF))
    Box(Modifier.size(size).clip(CircleShape).background(colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]), contentAlignment = Alignment.Center) {
        if (image == null) Text(name.take(1).uppercase(), color = Color(0xFF002117), style = MaterialTheme.typography.titleMedium)
        else Image(image!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), filterQuality = FilterQuality.None)
    }
}
