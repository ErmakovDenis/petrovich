package ru.petrovich.telemetry.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatUiState(val messages: List<ChatMessage>, val agentTyping: Boolean = false)

class ChatViewModel(private val agent: ChatAgent = ServiceLocator.chatAgent) : ViewModel() {
    private val greeting = ChatMessage(
        author = Author.AGENT,
        text = "Здравствуйте! Я помогу разобраться, всё ли в порядке с машинами и какие замечены отклонения.",
    )
    private val _state = MutableStateFlow(ChatUiState(listOf(greeting)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.agentTyping) return
        _state.update { it.copy(messages = it.messages + ChatMessage(author = Author.USER, text = trimmed), agentTyping = true) }
        viewModelScope.launch {
            val reply = runCatchingCancellable { agent.reply(_state.value.messages) }
                .fold(
                    onSuccess = { ChatMessage(author = Author.AGENT, text = it) },
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
    "Всё ли в порядке с машинами?",
    "Какие аномалии за сутки?",
    "Проверь уровень топлива",
    "Состояние аккумуляторов",
)

@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.agentTyping) {
        val count = state.messages.size + if (state.agentTyping) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "ИИ-ассистент",
                subtitle = "Агент не подключён · демо-интерфейс",
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                if (state.agentTyping) item { TypingBubble() }
            }
            if (state.messages.size <= 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(suggestions) { s -> AssistChip(onClick = { vm.send(s) }, label = { Text(s) }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, "Очистить чат") }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Спросите о состоянии машин…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )
                FilledIconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && !state.agentTyping,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val mine = msg.author == Author.USER
    val colors = MaterialTheme.colorScheme
    val bg = when {
        msg.isError -> colors.errorContainer
        mine -> colors.primaryContainer
        else -> colors.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine) Text("Ассистент", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    timeFormat.format(Date(msg.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Text("Ассистент печатает…", Modifier.padding(12.dp).background(MaterialTheme.colorScheme.surfaceVariant), style = MaterialTheme.typography.bodySmall)
    }
}
