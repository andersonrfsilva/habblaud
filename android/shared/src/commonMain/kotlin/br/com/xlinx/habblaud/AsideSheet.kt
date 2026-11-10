package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor

/**
 * Bate-papo paralelo (server/asides): perguntas ao agente do omp sobre o que ele está fazendo, sem interromper nem
 * entrar na conversa dele. Mostra uma conversa por vez; o seletor troca entre as da sessão ou abre uma nova.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AsideSheet(asides: AsideController, onClose: () -> Unit) {
    val state by asides.state.collectAsState()
    val topic = state.topic
    val turns = topic?.turns.orEmpty()
    val list = rememberLazyListState()
    var following by remember(topic?.id) { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list, topic?.id) {
        var wasScrolling = false
        snapshotFlow { Triple(list.isScrollInProgress, autoScrolling, list.canScrollForward) }.collect { (scrolling, automatic, canScroll) ->
            if (!automatic && (scrolling || wasScrolling)) following = !canScroll
            wasScrolling = scrolling && !automatic
        }
    }
    // Pergunta nova ou resposta crescendo: mostra o fim só enquanto o usuário o acompanha.
    LaunchedEffect(topic?.id, turns.size, turns.lastOrNull()?.answer?.length) {
        if (following && !list.isScrollInProgress && turns.isNotEmpty()) {
            autoScrolling = true
            try { list.animateScrollToItem(turns.lastIndex, Int.MAX_VALUE) }
            finally { autoScrolling = false }
        }
    }
    var draft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.aside_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.aside_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TopicPicker(state, asides)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.topics.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    turns.isEmpty() -> Text(stringResource(Res.string.aside_empty), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxSize().testTag("aside-turns"), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(turns, key = { it.id }) { AsideTurnView(it) }
                    }
                }
            }
            state.error?.let { Text(if (it == "network") stringResource(Res.string.reading_offline) else it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val busy = turns.any { it.running }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    draft, { if (it.length <= ASIDE_QUESTION_MAX) draft = it },
                    Modifier.weight(1f).testTag("aside-input"),
                    placeholder = { Text(stringResource(Res.string.aside_input)) },
                    maxLines = 4, shape = RoundedCornerShape(24.dp), colors = pillFieldColors(),
                )
                if (busy) {
                    val stopLabel = stringResource(Res.string.aside_stop)
                    FilledIconButton(onClick = asides::cancel, modifier = Modifier.semantics { contentDescription = stopLabel }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                    }
                } else {
                    val sendLabel = stringResource(Res.string.aside_send)
                    FilledIconButton(onClick = { asides.ask(draft); draft = "" }, enabled = draft.isNotBlank() && !state.sending, modifier = Modifier.testTag("aside-send").semantics { contentDescription = sendLabel }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** Seletor das conversas da sessão (a primeira pergunta e a hora) e "Nova conversa". */
@Composable
private fun TopicPicker(state: AsideState, asides: AsideController) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = stringResource(Res.string.aside_topics)
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Text(stringResource(Res.string.aside_topics_count, state.topics.size))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.aside_new)) }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }, onClick = { asides.startNew(); open = false })
            state.topics.forEach { topic ->
                val first = topic.turns.firstOrNull()?.question.orEmpty().replace(Regex("\\s+"), " ").trim()
                DropdownMenuItem(
                    text = { Text("$first · ${entryTime(topic.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { RadioButton(selected = topic.id == state.topic?.id, onClick = null) },
                    onClick = { asides.choose(topic.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun AsideTurnView(turn: AsideTurn) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.align(Alignment.End).fillMaxWidth(0.85f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            SelectionContainer { Text(turn.question, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        if (turn.answer.isNotEmpty()) SelectionContainer {
            Markdown(turn.answer, modifier = Modifier.fillMaxWidth(), colors = markdownColor(text = MaterialTheme.colorScheme.onSurface), components = markdownComponents(image = {}))
        }
        val status = when (turn.status) {
            "queued" -> stringResource(Res.string.aside_queued)
            "running" -> if (turn.answer.isEmpty()) stringResource(Res.string.aside_thinking) else null
            "cancelled" -> stringResource(Res.string.aside_cancelled)
            "failed" -> stringResource(Res.string.aside_failed, turn.error.orEmpty())
            else -> null
        }
        status?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (turn.running) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(it, style = MaterialTheme.typography.labelMedium, color = if (turn.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
