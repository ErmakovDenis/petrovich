package ru.petrovich.telemetry.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.chat.Author
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.ChatMessage
import ru.petrovich.telemetry.chat.ContextualChatAgent
import ru.petrovich.telemetry.chat.DraftState
import ru.petrovich.telemetry.chat.EmailActions
import ru.petrovich.telemetry.chat.EmailDraft
import ru.petrovich.telemetry.chat.meansAlreadySent
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.chat.SwitchingChatAgent
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.theme.Petrovich
import androidx.compose.ui.draw.clip
import ru.petrovich.telemetry.util.runCatchingCancellable

data class ChatUiState(val messages: List<ChatMessage>, val agentTyping: Boolean = false)

class ChatViewModel(
    private val agent: ChatAgent = ServiceLocator.chatAgent,
    private val emails: EmailActions = ServiceLocator.emails,
) : ViewModel() {
    private val greeting = ChatMessage(
        author = Author.AGENT,
        text = "Здравствуйте! Спросите про машины, водителей или топливо — отвечу, что происходит в парке.",
    )
    private val _state = MutableStateFlow(ChatUiState(listOf(greeting)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** false — отвечает заглушка; следует за переключателем «Ассистент через стенд». */
    val connected: StateFlow<Boolean> = (agent as? SwitchingChatAgent)?.connectedState ?: MutableStateFlow(agent.connected)

    /** [context] — аномалия, из карточки которой открыт чат; уходит агенту вместе с историей. */
    fun send(text: String, context: Anomaly? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.agentTyping) return
        _state.update { it.copy(messages = it.messages + ChatMessage(author = Author.USER, text = trimmed), agentTyping = true) }
        viewModelScope.launch {
            val history = _state.value.messages
            val reply = runCatchingCancellable {
                if (agent is ContextualChatAgent) agent.reply(history, context) else agent.reply(history)
            }
                .fold(
                    // Черновики писем — отдельными сообщениями после текста, каждый со своими кнопками.
                    onSuccess = { r -> listOf(ChatMessage(author = Author.AGENT, text = r.text)) + r.drafts.map { ChatMessage(author = Author.AGENT, text = "", draft = it) } },
                    onFailure = { listOf(ChatMessage(author = Author.AGENT, text = "Ошибка: ${it.message}", isError = true)) },
                )
            _state.update { it.copy(messages = it.messages + reply, agentTyping = false) }
        }
    }

    /**
     * «Отправить»: письмо уходит только отсюда. Отказ стенда (в том числе истёкший срок — его проверяет стенд по своим
     * часам) или ошибка почтового сервера — в сообщении, письмо не отправлено.
     */
    fun sendDraft(messageId: String) {
        val draft = draftOf(messageId) ?: return
        updateDraft(messageId) { it.copy(draftState = DraftState.SENDING, draftError = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { emails.send(draft.id) }
            val error = result.exceptionOrNull()
            if (error == null || (error is StandException && error.meansAlreadySent())) {
                updateDraft(messageId) { it.copy(draftState = DraftState.SENT, draftError = null) }
            } else {
                updateDraft(messageId) { it.copy(draftState = DraftState.FAILED, draftError = error.message ?: error.toString()) }
            }
        }
    }

    /** «Отменить»: черновик на стенде закрывается, отправить его потом нельзя. */
    fun cancelDraft(messageId: String) {
        val draft = draftOf(messageId) ?: return
        updateDraft(messageId) { it.copy(draftState = DraftState.CANCELLING, draftError = null) }
        viewModelScope.launch {
            runCatchingCancellable { emails.cancel(draft.id) }
                .onSuccess { updateDraft(messageId) { it.copy(draftState = DraftState.CANCELLED, draftError = null) } }
                .onFailure { e -> updateDraft(messageId) { it.copy(draftState = DraftState.PENDING, draftError = e.message ?: e.toString()) } }
        }
    }

    /** Черновик, с которым можно действовать (не отправляется, не отправлен и не отменён). */
    private fun draftOf(messageId: String): EmailDraft? = _state.value.messages
        .firstOrNull { it.id == messageId && it.draftState in setOf(DraftState.PENDING, DraftState.FAILED) }?.draft

    private fun updateDraft(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == messageId) transform(it) else it }) }
    }

    fun clear() {
        _state.value = ChatUiState(listOf(greeting))
    }
}

private val suggestions = listOf(
    "Всё ли в порядке с машинами?",
    "Какие аномалии за сутки?",
    "Проверь уровень топлива",
    "Состояние аккумуляторов",
)

private val contextSuggestions = listOf("Он раньше так делал?", "Как это доказать?")

/** [anomalyId] — если чат открыт из карточки аномалии, показываем контекст разговора. */
@Composable
fun ChatScreen(anomalyId: String?, onBack: (() -> Unit)?, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val context = anomalyId?.let { id -> anomalies.firstOrNull { it.id == id } }
    val connected by vm.connected.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val c = Petrovich.colors

    LaunchedEffect(state.messages.size, state.agentTyping) {
        val count = state.messages.size + if (state.agentTyping) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Петрович",
                subtitle = if (connected) null else "Агент не подключён · ответы-заглушки",
                onBack = onBack,
                actions = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, "Очистить чат") } },
            )
        },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (context != null) item(key = "ctx") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Контекст: ${context.title.lowercase()} · ${context.vehicleName}" + (context.time?.let { " · ${it.hhmm()}" } ?: ""),
                            Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = c.muted,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { m ->
                    if (m.draft != null) DraftCard(m, onSend = { vm.sendDraft(m.id) }, onCancel = { vm.cancelDraft(m.id) })
                    else MessageBubble(m)
                }
                if (state.agentTyping) item { TypingBubble() }
            }
            if (state.messages.size <= 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(if (context != null) contextSuggestions else suggestions) { s -> PChip(s, selected = false, onClick = { vm.send(s, context) }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Спросите Петровича") },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.bg, unfocusedContainerColor = c.bg,
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                    ),
                )
                FilledIconButton(
                    onClick = { vm.send(input, context); input = "" },
                    enabled = input.isNotBlank() && !state.agentTyping,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val mine = msg.author == Author.USER
    val c = Petrovich.colors
    val bg = when {
        msg.isError -> c.highSoft
        mine -> c.accent
        else -> c.surface
    }
    val fg = when {
        msg.isError -> c.high
        mine -> c.onAccent
        else -> c.ink
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = bg,
            contentColor = fg,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(msg.text, Modifier.padding(horizontal = 13.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Черновик письма: кому (имя, роль, адрес), тема, текст и кнопки. Письмо уходит только по «Отправить». */
@Composable
private fun DraftCard(msg: ChatMessage, onSend: () -> Unit, onCancel: () -> Unit) {
    val draft = msg.draft ?: return
    val c = Petrovich.colors
    Surface(color = c.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Черновик письма", style = MaterialTheme.typography.labelLarge, color = c.muted)
            draft.recipients.forEach { r ->
                Text("Кому: ${r.name}${if (r.role.isNotBlank()) ", ${r.role.lowercase()}" else ""} · ${r.email}", style = MaterialTheme.typography.bodySmall)
            }
            Text(draft.subject, style = MaterialTheme.typography.titleSmall)
            Text(draft.body, style = MaterialTheme.typography.bodyMedium)
            val status = when (msg.draftState) {
                DraftState.SENT -> "Отправлено"
                DraftState.CANCELLED -> "Отменено — письмо не отправлялось"
                DraftState.SENDING -> "Отправляем…"
                DraftState.CANCELLING -> "Отменяем…"
                DraftState.FAILED -> "Не отправлено: ${msg.draftError}"
                DraftState.PENDING -> msg.draftError?.let { "Не получилось: $it" }
                    ?: "Письмо уйдёт, только если вы нажмёте «Отправить»"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (msg.draftState == DraftState.FAILED || msg.draftError != null) c.high else c.muted)
            if (msg.draftState == DraftState.PENDING || msg.draftState == DraftState.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text("Отправить") }
                    OutlinedButton(onClick = onCancel) { Text("Отменить") }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Surface(color = Petrovich.colors.surface, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)) {
        Text("Петрович печатает…", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted)
    }
}
