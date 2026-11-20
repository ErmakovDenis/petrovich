package ru.petrovich.telemetry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import ru.petrovich.telemetry.chat.*
import ru.petrovich.telemetry.ui.chat.ChatViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationViewModelTest {
    @Test fun switchingProfileClearsHistoryAndRejectsLateReply() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val profile = MutableStateFlow("a")
            val delayed = CompletableDeferred<Unit>()
            var oldHistory = true
            val agent = object : ChatAgent {
                override val conversationEpoch = profile
                override suspend fun history() = if (oldHistory) listOf(ChatMessage(author = Author.AGENT, text = "История A")) else emptyList()
                override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?): ChatReply {
                    withContext(NonCancellable) { delayed.await() }
                    return ChatReply("Поздний ответ A")
                }
            }
            val vm = ChatViewModel(agent, object : EmailActions {
                override suspend fun send(draftId: String): EmailResult = error("Не вызывается")
                override suspend fun cancel(draftId: String): EmailResult = error("Не вызывается")
            })
            assertTrue(vm.state.value.messages.any { it.text == "История A" })
            vm.send("Вопрос A", null)
            oldHistory = false
            profile.value = "b"
            assertFalse(vm.state.value.messages.any { it.text.contains(" A") })
            delayed.complete(Unit)
            assertFalse(vm.state.value.messages.any { it.text.contains(" A") })
            assertFalse(vm.state.value.agentTyping)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newAndDeleteUseDistinctAgentActions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<String>()
            val agent = object : ChatAgent {
                override suspend fun history() = emptyList<ChatMessage>()
                override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?) = ChatReply("Ответ")
                override suspend fun newConversation(): Boolean { calls += "new"; return true }
                override suspend fun deleteConversation(): Boolean { calls += "delete"; return true }
            }
            val vm = ChatViewModel(agent, object : EmailActions {
                override suspend fun send(draftId: String): EmailResult = error("Не вызывается")
                override suspend fun cancel(draftId: String): EmailResult = error("Не вызывается")
            })
            vm.clear()
            vm.deleteConversation()
            assertEquals(listOf("new", "delete"), calls)
        } finally { Dispatchers.resetMain() }
    }
}
