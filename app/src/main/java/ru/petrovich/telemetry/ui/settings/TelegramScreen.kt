package ru.petrovich.telemetry.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.TELEGRAM_CATEGORIES
import ru.petrovich.telemetry.data.TELEGRAM_KINDS
import ru.petrovich.telemetry.data.TelegramCardSettings
import ru.petrovich.telemetry.data.TelegramListSettings
import ru.petrovich.telemetry.data.TelegramReportSettings
import ru.petrovich.telemetry.data.TelegramChat
import ru.petrovich.telemetry.data.TelegramChats
import ru.petrovich.telemetry.data.TelegramSubscription
import ru.petrovich.telemetry.data.TelegramVehicle
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Варианты как в боте (/settings): лимит в день и тихие часы. */
private val LIMITS = listOf<Int?>(3, 5, 10, null)
private val QUIET = listOf<Pair<Int?, Int?>>(22 to 7, 23 to 7, null to null)
private val DATE = DateTimeFormatter.ofPattern("d.MM.yyyy")

/**
 * Telegram-бот: подключение (код из бота или ссылка на этом телефоне) и что присылать в каждый подключённый чат.
 * Подписка хранится на стенде — те же настройки видны в боте (/settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramScreen(onBack: () -> Unit, onOpenConnection: () -> Unit) {
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var data by remember { mutableStateOf<TelegramChats?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var unlinking by remember { mutableStateOf<TelegramChat?>(null) }

    fun reload() = scope.launch {
        loading = true
        runCatchingCancellable { ServiceLocator.telegram.chats() }
            .onSuccess { data = it; error = null }
            .onFailure { error = "Не получилось загрузить: ${it.message}" }
        loading = false
    }

    fun open(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    val ready = settings?.let { it.backgroundOnServer && it.standAccessGranted } == true
    LaunchedEffect(ready) { if (ready) reload() }

    Scaffold(topBar = { AppTopBar(title = "Telegram", onBack = onBack) }) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Бот «Петровича» присылает события парка, даёт принять решение, спросить ассистента и написать " +
                    "сотруднику прямо в Telegram. Он работает по доступу фоновой проверки — пароль в Telegram не передаётся",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ready) {
                Text(
                    if (!s.backgroundOnServer) "Сначала включите «Фоновая проверка на стенде» в разделе «Подключение и данные»: " +
                        "бот работает по её доступу к AutoGRAPH"
                    else "Ждём, пока стенд получит доступ фоновой проверки, — обычно это занимает несколько секунд",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenConnection) { Text("Открыть «Подключение и данные»") }
                return@Column
            }
            val d = data
            when {
                d == null && loading -> CircularProgressIndicator()
                d == null -> {
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { reload() }) { Text("Повторить") }
                }
                !d.configured -> Text(
                    "Telegram-бот на стенде не настроен (FS_TELEGRAM_BOT_USERNAME и FS_TELEGRAM_SERVICE_TOKEN)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Text(if (d.chats.isEmpty()) "Подключить бота" else "Подключить ещё один чат", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. Откройте бота в Telegram — на этом телефоне или любом другом устройстве — и отправьте /start.\n" +
                            "2. Введите здесь код, который пришлёт бот.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    d.botUrl?.let { url ->
                        OutlinedButton(onClick = { if (!open(url)) status = "Откройте в Telegram: $url" }) {
                            Text("Открыть бота ${url.substringAfterLast('/').let { "@$it" }}")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.uppercase().take(12) },
                            label = { Text("Код из бота") }, placeholder = { Text("XXXX-XXXX") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = code.count { it.isLetterOrDigit() } >= 8 && !busy,
                            onClick = {
                                busy = true
                                status = null
                                scope.launch {
                                    runCatchingCancellable { ServiceLocator.telegram.pair(s.deviceId, s.schemaName, code) }
                                        .onSuccess { chat ->
                                            code = ""
                                            status = "Подключено: ${chat.label()}. Бот уже поздоровался в чате"
                                            reload()
                                        }
                                        .onFailure { status = it.message }
                                    busy = false
                                }
                            },
                        ) { Text("Подключить") }
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                status = runCatchingCancellable { ServiceLocator.telegram.link(s.deviceId, s.schemaName) }.fold(
                                    { link ->
                                        if (open(link.url)) "Нажмите «Начать» в боте — чат подключится сам, затем обновите этот экран"
                                        else "Не нашлось Telegram на этом телефоне — подключитесь кодом из бота"
                                    },
                                    { "Не получилось: ${it.message}" },
                                )
                                busy = false
                            }
                        },
                    ) { Text("Telegram на этом телефоне? Подключить по ссылке") }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                    if (d.chats.isNotEmpty()) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Что присылать", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { reload() }, enabled = !loading) { Text("Обновить") }
                        }
                        Text(
                            "«Критично» по выбранным машинам приходит всегда, кроме выключенных событий. Основное можно " +
                                "поменять и в боте командой /settings",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        d.vehiclesError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        d.chats.forEach { chat ->
                            ChatCard(
                                chat, d.vehicles,
                                onChange = { sub ->
                                    // Сразу на экране; отказ стенда — возвращаем как было.
                                    data = d.copy(chats = d.chats.map { if (it.chatId == chat.chatId) it.copy(subscription = sub) else it })
                                    scope.launch {
                                        runCatchingCancellable { ServiceLocator.telegram.setSubscription(chat.chatId, sub) }
                                            .onSuccess { error = null }
                                            .onFailure { error = "Не сохранилось: ${it.message}"; reload() }
                                    }
                                },
                                onUnlink = { unlinking = chat },
                            )
                        }
                    }
                }
            }
        }
    }

    unlinking?.let { chat ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text("Отключить ${chat.label()}?") },
            text = { Text("Уведомления и вопросы в этом чате перестанут работать. Подключиться снова можно в любой момент") },
            confirmButton = {
                TextButton(onClick = {
                    unlinking = null
                    scope.launch {
                        runCatchingCancellable { ServiceLocator.telegram.unlink(chat.chatId) }
                            .onSuccess { data = it; status = "${chat.label()} отключён" }
                            .onFailure { error = "Не получилось отключить: ${it.message}" }
                    }
                }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { unlinking = null }) { Text("Отмена") } },
        )
    }
}

private fun TelegramChat.label(): String = title ?: "чат $chatId"

@Composable
private fun ChatCard(chat: TelegramChat, vehicles: List<TelegramVehicle>, onChange: (TelegramSubscription) -> Unit, onUnlink: () -> Unit) {
    val sub = chat.subscription
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chat.label(), style = MaterialTheme.typography.titleSmall)
            val since = Instant.ofEpochMilli(chat.linkedAt).atZone(ZoneId.systemDefault()).format(DATE)
            Text("Подключён $since · ${chat.userName}, «${chat.schemaName}»", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section("Машины", vehiclesSummary(sub, vehicles)) { VehiclesSettings(sub, vehicles, onChange) }
            Section("События", eventsSummary(sub)) { EventsSettings(sub, onChange) }
            Section("Когда присылать", whenSummary(sub)) { WhenSettings(sub, onChange) }
            Section("Доклад и сводка", reportsSummary(sub.reports)) { ReportsSettings(sub, onChange) }
            Section("Списки «Проблемы» и «Парк»", listsSummary(sub.lists)) { ListsSettings(sub, onChange) }
            Section("Карточка события", cardSummary(sub.card)) { CardSettingsEditor(sub, onChange) }
            TextButton(onClick = onUnlink) { Text("Отключить этот чат") }
        }
    }
}

/** Раздел настроек: заголовок и краткое «что сейчас», по нажатию — сами настройки. */
@Composable
private fun Section(title: String, summary: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
    if (open) Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

private fun vehiclesSummary(sub: TelegramSubscription, vehicles: List<TelegramVehicle>): String =
    sub.vehicleIds?.let { "Выбрано ${it.size} из ${vehicles.size}" } ?: "Все машины, и новые тоже"

private fun eventsSummary(sub: TelegramSubscription): String {
    val total = TELEGRAM_KINDS.values.sumOf { it.size }
    val on = total - TELEGRAM_KINDS.values.flatten().count { it.first in sub.mutedKinds }
    val levels = listOfNotNull("критичные", "предупреждения".takeIf { sub.warnings }, "инфо".takeIf { sub.info })
    return "$on из $total событий · ${levels.joinToString(", ")}"
}

private fun whenSummary(sub: TelegramSubscription): String = listOfNotNull(
    sub.dailyLimit?.let { "не больше $it в день" } ?: "без лимита",
    quietLabel(sub)?.let { "тихие часы $it" },
    sub.repeatHours?.let { "повторы раз в $it ч" },
).joinToString(" · ")

private fun quietLabel(sub: TelegramSubscription): String? {
    val from = sub.quietFromHour ?: return null
    val to = sub.quietToHour ?: return null
    return if (from == to) null else "%02d–%02d".format(from, to)
}

private fun reportsSummary(r: TelegramReportSettings): String = listOf(
    if (r.daily) "доклад в ${r.dailyTime}${if (r.weekdaysOnly) " по будням" else ""}" else "без доклада",
    if (r.weekly) "сводка: ${WEEKDAYS[(r.weeklyDay - 1).coerceIn(0, 6)].second.lowercase()}, ${r.weeklyTime}" else "без сводки",
).joinToString(" · ")

@Composable
private fun ReportsSettings(sub: TelegramSubscription, onChange: (TelegramSubscription) -> Unit) {
    val r = sub.reports
    fun set(next: TelegramReportSettings) = onChange(sub.copy(reports = next))
    ToggleRow("Утренний доклад", "События за сутки по вашим машинам и событиям", r.daily) { set(r.copy(daily = it)) }
    if (r.daily) {
        TimeChips(r.dailyTime, DAILY_TIMES) { set(r.copy(dailyTime = it)) }
        Choice("Дни", listOf(false to "Каждый день", true to "По будням"), r.weekdaysOnly) { set(r.copy(weekdaysOnly = it)) }
        ToggleRow("Если событий не было", "Прислать короткое «всё спокойно»", r.whenEmpty) { set(r.copy(whenEmpty = it)) }
    }
    ToggleRow("Недельная сводка", "Итоги прошлой недели: события, решения, машины", r.weekly) { set(r.copy(weekly = it)) }
    if (r.weekly) {
        Choice("День", WEEKDAYS, r.weeklyDay) { set(r.copy(weeklyDay = it)) }
        TimeChips(r.weeklyTime, DAILY_TIMES) { set(r.copy(weeklyTime = it)) }
    }
    Note("Время — по часовому поясу телефона. Доклад и сводку можно запросить и сразу: /report и /week в боте")
}

/** Время из готовых вариантов; если в боте выбрано другое — оно тоже в списке. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeChips(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (options + selected).distinct().sorted().forEach { t ->
            FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(t) })
        }
    }
}

private val DAILY_TIMES = listOf("07:00", "08:00", "09:00", "09:30", "10:00", "12:00", "17:00", "18:00")
private val WEEKDAYS = listOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс")

private fun listsSummary(lists: TelegramListSettings): String = listOfNotNull(
    if (lists.subscribedOnly) "только мои машины" else "все машины",
    SEVERITIES.firstOrNull { it.first == lists.minSeverity && it.first != "INFO" }?.second?.lowercase(),
    PERIODS.firstOrNull { it.first == lists.periodHours && it.first != null }?.second?.lowercase(),
    "по ${lists.pageSize}",
    if (lists.order == "severity") "сначала критичные" else "сначала новые",
).joinToString(" · ")

private fun cardSummary(card: TelegramCardSettings): String {
    val on = CARD_LINES.count { it.get(card) } + CARD_BUTTONS.count { it.get(card) }
    return "Показано $on из ${CARD_LINES.size + CARD_BUTTONS.size}"
}

@Composable
private fun VehiclesSettings(sub: TelegramSubscription, vehicles: List<TelegramVehicle>, onChange: (TelegramSubscription) -> Unit) {
    ToggleRow("Все машины", "И новые, когда появятся", sub.vehicleIds == null) { all ->
        onChange(sub.copy(vehicleIds = if (all) null else vehicles.map { it.id }))
    }
    val chosen = sub.vehicleIds ?: return
    vehicles.forEach { v ->
        CheckRow(v.name, v.id in chosen) { on -> onChange(sub.copy(vehicleIds = if (on) chosen + v.id else chosen - v.id)) }
    }
    if (chosen.isEmpty()) Note("Не выбрано ни одной машины — уведомлений не будет")
}

@Composable
private fun EventsSettings(sub: TelegramSubscription, onChange: (TelegramSubscription) -> Unit) {
    Note("Выключенное событие не приходит совсем, даже критичное, и не показывается в «Проблемах» и «Парке» бота. " +
        "То же — в боте: /settings → «События»")
    TELEGRAM_KINDS.forEach { (category, kinds) ->
        Text(TELEGRAM_CATEGORIES.getValue(category), style = MaterialTheme.typography.labelLarge)
        kinds.forEach { (kind, name) ->
            CheckRow(name, kind !in sub.mutedKinds) { on ->
                onChange(sub.copy(mutedKinds = if (on) sub.mutedKinds - kind else sub.mutedKinds + kind))
            }
        }
    }
    ToggleRow("Предупреждения", "Не только критичное", sub.warnings) { onChange(sub.copy(warnings = it)) }
    ToggleRow("Инфо", "Справочные события", sub.info) { onChange(sub.copy(info = it)) }
}

@Composable
private fun WhenSettings(sub: TelegramSubscription, onChange: (TelegramSubscription) -> Unit) {
    Choice("Не больше в день, кроме критичных", LIMITS.map { it to (it?.toString() ?: "Нет") }, sub.dailyLimit) {
        onChange(sub.copy(dailyLimit = it))
    }
    val quiet = quietLabel(sub)?.let { sub.quietFromHour to sub.quietToHour } ?: (null to null)
    Choice("Тихие часы (кроме критичных)", QUIET.map { it to (if (it.first == null) "Выкл" else "%02d–%02d".format(it.first, it.second)) }, quiet) {
        onChange(sub.copy(quietFromHour = it.first, quietToHour = it.second))
    }
    if (quiet !in QUIET) Note("Сейчас: %02d:00–%02d:00".format(quiet.first, quiet.second))
    Choice("Повтор того же события на машине", REPEATS, sub.repeatHours) { onChange(sub.copy(repeatHours = it)) }
    Note("Пропущенные повторы бот сосчитает в следующей карточке: «повторялось ещё 2 раза»")
}

@Composable
private fun ListsSettings(sub: TelegramSubscription, onChange: (TelegramSubscription) -> Unit) {
    val lists = sub.lists
    fun set(next: TelegramListSettings) = onChange(sub.copy(lists = next))
    Note("Выключенные в «Событиях» не показываются и здесь")
    ToggleRow("Только мои машины", "Как в уведомлениях; выключено — все машины, что вам видны", lists.subscribedOnly) {
        set(lists.copy(subscribedOnly = it))
    }
    Choice("Важность", SEVERITIES, lists.minSeverity) { set(lists.copy(minSeverity = it)) }
    Choice("За какой период", PERIODS, lists.periodHours) { set(lists.copy(periodHours = it)) }
    Choice("Сколько строк в «Проблемах»", PAGE_SIZES.map { it to it.toString() }, lists.pageSize) { set(lists.copy(pageSize = it)) }
    Choice("Порядок", ORDERS, lists.order) { set(lists.copy(order = it)) }
}

@Composable
private fun CardSettingsEditor(sub: TelegramSubscription, onChange: (TelegramSubscription) -> Unit) {
    val card = sub.card
    Text("Что показывать", style = MaterialTheme.typography.labelLarge)
    CARD_LINES.forEach { line -> CheckRow(line.title, line.get(card)) { onChange(sub.copy(card = line.set(card, it))) } }
    Text("Кнопки", style = MaterialTheme.typography.labelLarge)
    CARD_BUTTONS.forEach { button -> CheckRow(button.title, button.get(card)) { onChange(sub.copy(card = button.set(card, it))) } }
    if (!card.resolveButtons) Note("Решение по событию тогда принимается в приложении")
}

/** Строка или кнопка карточки: подпись и как её прочитать и поменять в настройках. */
private class CardPart(
    val title: String,
    val get: (TelegramCardSettings) -> Boolean,
    val set: (TelegramCardSettings, Boolean) -> TelegramCardSettings,
)

private val CARD_LINES = listOf(
    CardPart("Описание", { it.description }, { c, v -> c.copy(description = v) }),
    CardPart("Параметр и значение", { it.parameter }, { c, v -> c.copy(parameter = v) }),
    CardPart("«Петрович помнит» — когда такое уже было", { it.memory }, { c, v -> c.copy(memory = v) }),
    CardPart("Источник: правила или аналитика", { it.source }, { c, v -> c.copy(source = v) }),
    CardPart("Кто и когда принял решение", { it.resolvedBy }, { c, v -> c.copy(resolvedBy = v) }),
    CardPart("«Проверено повторно» — пропуски в данных и двигатель", { it.recheck }, { c, v -> c.copy(recheck = v) }),
)
private val CARD_BUTTONS = listOf(
    CardPart("«Разобраться с Петровичем»", { it.reviewButton }, { c, v -> c.copy(reviewButton = v) }),
    CardPart("«График» — данные вокруг события", { it.dataButton }, { c, v -> c.copy(dataButton = v) }),
    CardPart("«Подтвердить», «Ложная тревога», «Вернуть в работу»", { it.resolveButtons }, { c, v -> c.copy(resolveButtons = v) }),
)
private val REPEATS = listOf<Pair<Int?, String>>(null to "Всегда", 1 to "1 ч", 3 to "3 ч", 6 to "6 ч", 12 to "12 ч")
private val SEVERITIES = listOf("INFO" to "Все", "WARNING" to "От предупр.", "CRITICAL" to "Критичные")
private val PERIODS = listOf<Pair<Int?, String>>(24 to "Сутки", 72 to "3 дня", 168 to "Неделя", null to "Всё")
private val PAGE_SIZES = listOf(5, 10, 20)
private val ORDERS = listOf("newest" to "Сначала новые", "severity" to "Сначала критичные")

/** Выбор одного из вариантов: подпись и сегменты. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
