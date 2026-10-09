package ru.petrovich.telemetry.ui.chat

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import ru.petrovich.telemetry.chat.SwitchingChatAgent
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.EmailDraftCard
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.rememberVoiceInput
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable

data class ChatUiState(val messages: List<ChatMessage>, val agentTyping: Boolean = false)

class ChatViewModel(private val agent: ChatAgent = ServiceLocator.chatAgent) : ViewModel() {
    // Текст первой реплики — как в макете (3.11): «Здравствуйте! Я слежу за парком. Спросите голосом
    // или выберите вопрос ниже.»
    private val greeting = ChatMessage(
        author = Author.AGENT,
        text = "Здравствуйте! Я слежу за парком. Спросите голосом или выберите вопрос ниже.",
    )
    private val _state = MutableStateFlow(ChatUiState(listOf(greeting)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** false — локальный движок без внешней модели; следует за переключателем «Ассистент через стенд». */
    val connected: StateFlow<Boolean> = (agent as? SwitchingChatAgent)?.connectedState ?: MutableStateFlow(agent.connected)

    fun send(text: String, contextAnomalyId: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.agentTyping) return
        _state.update { it.copy(messages = it.messages + ChatMessage(author = Author.USER, text = trimmed), agentTyping = true) }
        viewModelScope.launch {
            val reply = runCatchingCancellable { agent.reply(_state.value.messages, contextAnomalyId) }
                .fold(
                    onSuccess = { ChatMessage(author = Author.AGENT, text = it.text, openAnomalyId = it.openAnomalyId, actionLabel = it.actionLabel, emailDraft = it.emailDraft, assignable = it.assignable) },
                    onFailure = { ChatMessage(author = Author.AGENT, text = "Ошибка: ${it.message}", isError = true) },
                )
            _state.update { it.copy(messages = it.messages + reply, agentTyping = false) }
        }
    }

    fun clear() {
        _state.value = ChatUiState(listOf(greeting))
    }
}

private val suggestions = listOf(
    "Что у меня не так?",
    "Аномалии за неделю",
    "Кто больше всех жжёт топливо?",
    "Есть проблемы со связью?",
)

private val contextSuggestions = listOf("Он раньше так делал?", "Как это доказать?")

/** [anomalyId] — если чат открыт из карточки аномалии, показываем контекст разговора. */
@Composable
fun ChatScreen(anomalyId: String?, onBack: (() -> Unit)?, onOpenCard: (String) -> Unit, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val context = anomalyId?.let { id -> anomalies.firstOrNull { it.id == id } }
    val connected by vm.connected.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val c = Petrovich.colors
    val appContext = LocalContext.current
    val voiceInput = rememberVoiceInput(
        onResult = { input = it },
        onUnavailable = { Toast.makeText(appContext, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_SHORT).show() },
    )

    LaunchedEffect(state.messages.size, state.agentTyping) {
        val count = state.messages.size + if (state.agentTyping) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Петрович",
                subtitle = if (connected) null else "Работает по данным парка, без внешней ИИ-модели",
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
                items(state.messages, key = { it.id }) { MessageBubble(it, onOpenCard) }
                if (state.agentTyping) item { TypingBubble() }
            }
            // Подсказки видны всегда, как в макете — не только пока диалог пуст.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(if (context != null) contextSuggestions else suggestions) { s -> PChip(s, selected = false, onClick = { vm.send(s, anomalyId) }) }
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
                // При вводе текста микрофон меняется на «Отправить» — одна кнопка, а не две (3.11).
                val canSend = input.isNotBlank() && !state.agentTyping
                FilledIconButton(
                    onClick = { if (canSend) { vm.send(input, anomalyId); input = "" } else voiceInput() },
                    enabled = !state.agentTyping,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (canSend) c.accent else c.surface2,
                        contentColor = if (canSend) c.onAccent else c.ink,
                    ),
                ) {
                    if (canSend) Icon(Icons.AutoMirrored.Filled.Send, "Отправить") else Icon(Icons.Filled.Mic, "Сказать голосом")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, onOpenCard: (String) -> Unit) {
    val c = Petrovich.colors
    // Сообщения владельца — пузырём справа; ответ Петровича — без пузыря, с аватаром (3.11).
    if (msg.author == Author.USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = c.accent,
                contentColor = c.onAccent,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(msg.text, Modifier.padding(horizontal = 13.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PetrovichAvatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = if (msg.isError) c.high else c.ink)
            if (msg.openAnomalyId != null) {
                SoftButton(
                    msg.actionLabel ?: "Открыть карточку", onClick = { onOpenCard(msg.openAnomalyId) },
                    container = c.accent, content = c.onAccent,
                )
            }
            if (msg.assignable != null) AssignChip(msg.assignable)
            msg.emailDraft?.let { draft -> EmailDraftCard(draft, Modifier.fillMaxWidth()) }
        }
    }
}

/** Круглый аватар «П» — по макету сопровождает ответы Петровича в чате. */
@Composable
private fun PetrovichAvatar() {
    val c = Petrovich.colors
    Box(Modifier.size(32.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun AssignChip(suggestion: ru.petrovich.telemetry.chat.AssignSuggestion) {
    val scope = rememberCoroutineScope()
    var done by remember(suggestion.anomalyId) { mutableStateOf(false) }
    val c = Petrovich.colors
    if (done) {
        Text("✓ Передано «${suggestion.assigneeName}»", style = MaterialTheme.typography.labelMedium, color = c.muted)
    } else {
        SoftButton(
            "Передать «${suggestion.assigneeName}»",
            onClick = { scope.launch { ServiceLocator.anomalyStore.markInProgress(suggestion.anomalyId, "${suggestion.assigneeName} · ${suggestion.assigneeRole}") }; done = true },
        )
    }
}

@Composable
private fun TypingBubble() {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PetrovichAvatar()
        Text("Петрович печатает…", style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
