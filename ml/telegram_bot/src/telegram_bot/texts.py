"""Тексты бота (HTML-разметка Telegram) по макетам «Прототип Telegram-бота «Петрович»». Данные со стенда
(названия машин, описания событий) экранируются: они приходят из внешней системы."""

from datetime import UTC, date, datetime, timedelta
from html import escape

SEVERITY = {"CRITICAL": "Критично", "WARNING": "Предупреждение", "INFO": "Инфо"}
# События, которые можно выключить (KINDS стенда, schemas/telegram.py), в том же порядке, что в приложении.
KINDS = {
    "drain": "Слив топлива", "drop": "Резкое падение уровня топлива", "overheat": "Перегрев двигателя",
    "oil": "Низкое давление масла", "volt": "Напряжение вне нормы", "power": "Пропадание питания",
    "brake": "Низкое давление в тормозной системе",
}
FALSE_ALARM_REASONS = ["Штатная работа", "Ошибка датчика", "Уже известно"]
_MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября",
           "декабря"]

INTRO = (
    "<b>Петрович</b> — помощник для вашего автопарка. Стенд каждые 15 минут проверяет телеметрию машин, а я сообщаю о "
    "сливах и резком падении уровня топлива, перегреве, низком давлении масла и в тормозах, напряжении вне нормы и "
    "пропадании питания.\n\n"
    "Отвечаю на вопросы о парке и помогаю разобраться с событием: подтвердить, отметить ложную тревогу, написать "
    "сотруднику.\n\n"
    "Один бот для всей команды: каждый сам выбирает, какие события и по каким машинам получать."
)
HOW_TO_CONNECT = (
    "Чтобы подключиться, отправьте /start — я пришлю код. Введите его в приложении «Петрович»: Настройки → Telegram → "
    "«Ввести код из бота»."
)
HELP = (
    "<b>Что я умею</b>\n"
    "/problems — события, которые ждут решения\n"
    "/park — машины и где есть неразобранное\n"
    "/report — доклад за сутки, /week — сводка за прошлую неделю\n"
    "/settings — что и по каким машинам присылать, когда доклад\n"
    "/new — начать разговор заново\n\n"
    "Можно просто спросить текстом: «Что у меня не так?», «Что с топливом у КАМАЗа за сутки?»"
)
ASK_PLACEHOLDER = "Спросите Петровича"
WHATS_WRONG = "Что у меня не так?"
VOICE_LATER = "Голосовые сообщения пока не понимаю — напишите, пожалуйста, текстом."
STALE_BUTTON = "Эта кнопка устарела — откройте /problems."
REVIEW_FIRST_QUESTION = "Что случилось и было ли такое раньше?"
REVIEW_EMAIL_REQUEST = "Подготовь письмо ответственному сотруднику по этому событию"
NEW_CHAT = "Начнём сначала. О чём спросить?"


def h(value: object) -> str:
    return escape(str(value), quote=False)


def local_now(offset_minutes: int, now_utc: datetime | None = None) -> datetime:
    now_utc = now_utc or datetime.now(UTC).replace(tzinfo=None)
    return now_utc + timedelta(minutes=offset_minutes)


def ms_local(ms: int, offset_minutes: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000, UTC).replace(tzinfo=None) + timedelta(minutes=offset_minutes)


def day_month(d: date) -> str:
    return f"{d.day} {_MONTHS[d.month - 1]}"


def when(local_iso: str, today: date) -> str:
    """«в 11:40» сегодня, иначе «15 сентября в 15:40»."""
    t = datetime.fromisoformat(local_iso)
    return f"в {t:%H:%M}" if t.date() == today else f"{day_month(t.date())} в {t:%H:%M}"


def plural(n: int, one: str, few: str, many: str) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return one
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return few
    return many


def _sentence(text: str) -> str:
    text = text.strip()
    return text if not text or text[-1] in ".!?…" else text + "."


def _number(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".").replace(".", ",")


def card(a: dict, offset: int, previous: list[str] | None = None, now_utc: datetime | None = None,
         show: dict | None = None, repeats: int = 0) -> str:
    """Карточка события (макет 3): важность и машина, что и когда, затем по настройкам чата [show] (card стенда) —
    описание, параметр и значение, «Петрович помнит», источник, решение и кто его принял."""
    show = show or {}
    today = local_now(offset, now_utc).date()
    lines = [f"<b>{h(SEVERITY.get(a['severity'], a['severity']))} · {h(a['vehicleName'])}</b>",
             f"{h(a['title'])} {when(a['eventTime'], today)}."]
    if show.get("description", True) and a.get("description"):
        lines.append(h(_sentence(a["description"])))
    if show.get("parameter", False) and a.get("value") is not None:
        lines.append(f"{h(a.get('parameterCaption') or a.get('parameterName') or 'Значение')}: {_number(a['value'])}")
    if show.get("memory", True) and previous:
        dates = ", ".join(when(p, today) for p in previous[:3])
        lines.append(f"Петрович помнит: на этой машине такое уже было {h(dates)}.")
    if repeats:
        lines.append(f"С прошлого уведомления повторялось ещё {repeats} {plural(repeats, 'раз', 'раза', 'раз')}.")
    if show.get("source", False) and a.get("source"):
        lines.append(f"Источник: {h(a['source'])}")
    status = decision(a, offset, who=show.get("resolvedBy", True))
    if status:
        lines += ["", status]
    return "\n".join(lines)


def decision(a: dict, offset: int, who: bool = True) -> str:
    """Строка решения: кто и когда отметил — решение общее со всеми, в том числе с приложением."""
    resolution = a.get("resolution")
    if not resolution:
        return ""
    head = "Подтверждено" if resolution == "CONFIRMED" else "Ложная тревога"
    if resolution == "FALSE_ALARM" and a.get("falseAlarmReason"):
        head += f" · {h(a['falseAlarmReason'])}"
    by = []
    if who and a.get("resolvedBy"):
        by.append(h(a["resolvedBy"]))
    if who and a.get("resolvedAt"):
        by.append(f"{ms_local(a['resolvedAt'], offset):%H:%M}")
    tail = f"Отметил: {', '.join(by)}. " if by else ""
    return f"<b>{head}</b>\n{tail}Решение видно всем, в том числе в приложении."


def problem_button(a: dict, today: date) -> str:
    """Подпись кнопки события в /problems (без HTML: текст кнопки не размечается)."""
    t = datetime.fromisoformat(a["eventTime"])
    at = f"{t:%H:%M}" if t.date() == today else f"{t:%d.%m} {t:%H:%M}"
    mark = "‼️" if a["severity"] == "CRITICAL" else "⚠️" if a["severity"] == "WARNING" else "ℹ️"
    return f"{mark} {a['vehicleName']} · {a['title']}, {at}"


def list_filter(lists: dict | None) -> str:
    """Какие настройки списков действуют (приложение → Настройки → Telegram); пусто — показано всё."""
    lists = lists or {}
    parts = []
    if lists.get("subscribedOnly"):
        parts.append("только мои машины")
    severity = lists.get("minSeverity") or "INFO"
    if severity == "WARNING":
        parts.append("от предупреждений")
    elif severity == "CRITICAL":
        parts.append("только критичные")
    hours = lists.get("periodHours")
    if hours:
        parts.append({24: "за сутки", 72: "за 3 дня", 168: "за неделю"}.get(hours, f"за {hours} ч"))
    if lists.get("order") == "severity":
        parts.append("сначала критичные")
    return f"Фильтр: {', '.join(parts)}. Меняется в приложении: Настройки → Telegram." if parts else ""


def problems(total: int, shown: int, lists: dict | None = None) -> str:
    note = list_filter(lists)
    if total == 0:
        text = "Всё в порядке: событий, которые ждут решения, нет."
        return f"{text}\n{note}" if note else text
    text = f"<b>Ждут решения: {total}</b>\nНажмите на событие, чтобы открыть карточку."
    if total > shown:
        text += f"\nЗдесь {shown}, остальные — в приложении «Петрович»."
    return f"{text}\n{note}" if note else text


def pair_code(code: str, minutes: int) -> str:
    """Чат не подключён: код для ввода в приложении."""
    return (
        f"Код для подключения: <code>{h(code)}</code>\n\n"
        "Откройте приложение «Петрович» → Настройки → Telegram → «Ввести код из бота» и введите его. "
        f"Код действует {minutes} {plural(minutes, 'минуту', 'минуты', 'минут')}; новый — по /start.\n\n"
        "Если Telegram на том же телефоне, что и приложение, можно нажать там «Открыть бота» — подключится само."
    )


def greeting(me: dict) -> str:
    """Подключение (макет 2)."""
    n = len(me.get("vehicles") or [])
    lines = ["Здравствуйте! Я Петрович.",
             f"Вы подключены к схеме «{h(me['schemaName'])}» как {h(me['userName'])}."]
    if me.get("vehiclesError"):
        lines.append(h(me["vehiclesError"]))
    else:
        lines.append(f"Машин: {n}. Присылаю важные события — что именно и по каким машинам, можно выбрать в /settings.")
    lines += ["", f"Спросите текстом, например: «{WHATS_WRONG}»", "Команды — в меню внизу."]
    return "\n".join(lines)


def park(me: dict, open_items: list[dict], total_open: int, lists: dict | None = None) -> str:
    vehicles = me.get("vehicles") or []
    chosen = me["subscription"].get("vehicleIds")
    if (lists or {}).get("subscribedOnly") and chosen is not None:
        vehicles = [v for v in vehicles if v["id"] in chosen]
    if not vehicles:
        return h(me.get("vehiclesError") or "Машин в схеме не видно.")
    counts: dict[str, int] = {}
    for a in open_items:
        counts[a["vehicleId"]] = counts.get(a["vehicleId"], 0) + 1
    lines = [f"<b>Парк: {len(vehicles)} {plural(len(vehicles), 'машина', 'машины', 'машин')}</b>"
             f" · схема «{h(me['schemaName'])}»"]
    busy = sorted((v for v in vehicles if v["id"] in counts), key=lambda v: (-counts[v["id"]], v["name"]))
    if busy:
        lines += ["", "Ждут решения:"]
        lines += [f"• {h(v['name'])} — {counts[v['id']]}" for v in busy]
        if total_open > len(open_items):
            lines.append(f"(посчитаны последние {len(open_items)} событий из {total_open})")
    quiet = len(vehicles) - len(busy)
    lines += ["", f"Без неразобранных событий: {quiet} из {len(vehicles)}."]
    note = list_filter(lists)
    if note:
        lines.append(note)
    return "\n".join(lines)


def _hours(sub: dict) -> str:
    a, b = sub.get("quietFromHour"), sub.get("quietToHour")
    return "выкл" if a is None or b is None or a == b else f"{a:02d}:00–{b:02d}:00"


def utc_offset(minutes: int) -> str:
    sign = "+" if minutes >= 0 else "−"
    hours, rest = divmod(abs(minutes), 60)
    return f"UTC{sign}{hours}" + (f":{rest:02d}" if rest else "")


def vehicles_label(sub: dict, vehicles: list[dict]) -> str:
    ids = sub.get("vehicleIds")
    if ids is None:
        return f"все ({len(vehicles)})" if vehicles else "все"
    return f"выбрано {len(ids)} из {len(vehicles)}" if vehicles else f"выбрано {len(ids)}"


def events_label(sub: dict) -> str:
    muted = [k for k in KINDS if k in (sub.get("mutedKinds") or [])]
    if not muted:
        return f"все ({len(KINDS)})"
    return f"{len(KINDS) - len(muted)} из {len(KINDS)}, выключены: " + ", ".join(KINDS[k].lower() for k in muted)


def settings(me: dict) -> str:
    """Настройки и подписки (макет 11)."""
    sub = me["subscription"]
    limit = sub.get("dailyLimit")
    lines = [
        "<b>Настройки</b>",
        "Выберите, что и по каким машинам присылать. «Критично» по выбранным машинам приходит всегда, кроме "
        "выключенных событий.",
        "",
        f"Мои машины: {vehicles_label(sub, me.get('vehicles') or [])}",
        f"События: {events_label(sub)}",
        f"Предупреждения: {'вкл' if sub.get('warnings') else 'выкл'}",
        f"Инфо: {'вкл' if sub.get('info') else 'выкл'}",
        f"Не больше {limit} в день, кроме критичных" if limit else "Без лимита в день",
        f"Тихие часы: {_hours(sub)}",
        f"Часовой пояс: {utc_offset(me['utcOffsetMinutes'])} (как в приложении)",
        f"Доклад: {daily_label(sub)}, {days_label(sub)}",
        f"Недельная сводка: {weekly_label(sub)}",
        f"Схема: «{h(me['schemaName'])}», {h(me['userName'])}",
        "",
        "Те же настройки — в приложении: Настройки → Telegram. Там же повторы, списки и вид карточки.",
    ]
    return "\n".join(lines)


WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
_WEEKDAYS_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]


def daily_label(sub: dict) -> str:
    r = sub.get("reports") or {}
    return r.get("dailyTime", "09:30") if r.get("daily", True) else "выкл"


def days_label(sub: dict) -> str:
    return "по будням" if (sub.get("reports") or {}).get("weekdaysOnly") else "каждый день"


def weekly_label(sub: dict, short: bool = False) -> str:
    r = sub.get("reports") or {}
    if not r.get("weekly", True):
        return "выкл"
    day = (_WEEKDAYS_SHORT if short else WEEKDAYS)[r.get("weeklyDay", 1) - 1]
    return f"{day}{'' if short else ','} {r.get('weeklyTime', '09:30')}"


def _event_time(local_iso: str, today: date) -> str:
    """«00:55» сегодня, иначе «15.09 в 20:05» — как в макете доклада."""
    t = datetime.fromisoformat(local_iso)
    return f"{t:%H:%M}" if t.date() == today else f"{t:%d.%m} в {t:%H:%M}"


def daily_report(r: dict, offset: int, now_utc: datetime | None = None) -> str:
    """Утренний доклад (макет 7): сколько событий за сутки, по типам, по каждому — машина и время."""
    now = local_now(offset, now_utc)
    since = datetime.fromisoformat(r["since"])
    head = "Доброе утро! Доклад за сутки" if now.hour < 12 else "Доклад за сутки"
    n, total = r["vehicles"], r["total"]
    where = f"С {since:%H:%M} {day_month(since.date())} по {n} {plural(n, 'машине', 'машинам', 'машинам')}"
    if total == 0:
        return f"<b>{head}</b>\n{where} событий не найдено — всё спокойно."
    found = f"{where} найдено {total} {plural(total, 'событие', 'события', 'событий')}"
    critical = r["critical"]
    if critical == total:
        found += " — все критичные" if total > 1 else " — критичное"
    elif critical:
        found += f", из них критичных — {critical}"
    left = r["open"]
    state = ("все ждут решения" if total > 1 else "ждёт решения") if left == total else \
        f"ждут решения {left}" if left else "все разобраны"
    lines = [f"<b>{head}</b>", f"{found}; {state}."]
    for g in r["groups"]:
        lines += ["", f"<b>{h(g['title'])} — {g['count']}:</b>"]
        for e in g["items"]:
            mark = " ✓" if e["resolved"] else ""
            lines.append(f"• {h(e['vehicleName'])}, {_event_time(e['eventTime'], now.date())}{mark}")
        if g["count"] > len(g["items"]):
            lines.append(f"• и ещё {g['count'] - len(g['items'])}")
    return "\n".join(lines)


def _week(r: dict) -> str:
    a, b = date.fromisoformat(r["weekFrom"]), date.fromisoformat(r["weekTo"])
    return f"{a.day}–{day_month(b)}" if a.month == b.month else f"{day_month(a)} – {day_month(b)}"


def weekly_report(r: dict) -> str:
    """Недельная сводка (макет 8): события против прошлой недели, частые типы, решения, машины."""
    total = r["total"]
    lines = [f"<b>Сводка за неделю {_week(r)}</b>",
             f"Событий: {total} (на прошлой неделе {r['previousTotal']})."]
    if total == 0:
        lines.append("Неделя прошла спокойно.")
        return "\n".join(lines)
    lines.append(", ".join(f"{h(k['name'])} — {k['count']}" for k in r["kinds"]).capitalize() + ".")
    decided = r["confirmed"] + r["falseAlarms"]
    if decided == 0:
        lines.append(f"Решений за неделю не принимали: ждут все {total}." if total > 1 else
                     "Решений за неделю не принимали.")
    else:
        lines.append(f"Подтверждено {r['confirmed']}, ложных тревог {r['falseAlarms']}, ждут решения {r['open']}.")
    if r["topVehicles"]:
        lines += ["", "Чаще всего:"]
        lines += [f"{i}. {h(v['name'])} — {v['count']}, {h((v.get('detail') or '').lower())}."
                  for i, v in enumerate(r["topVehicles"], 1)]
    q, n = r["quietVehicles"], r["vehicles"]
    lines += ["", f"Без событий: {q} {plural(q, 'машина', 'машины', 'машин')} из {n}."]
    return "\n".join(lines)


def draft(d: dict, offset: int) -> str:
    """Черновик письма (макет 5): уходит, только когда пользователь нажмёт «Отправить»."""
    to = "\n".join(f"{h(r['name'])}, {h(r['role'])} — {h(r['email'])}" for r in d["recipients"])
    until = ms_local(d["expiresAt"], offset)
    return (f"<b>Кому</b>\n{to}\n<b>Тема</b>: {h(d['subject'])}\n\n{h(d['body'])}\n\n"
            f"<i>Черновик действует до {until:%H:%M}. Письмо уйдёт, только когда вы нажмёте «Отправить».</i>")


def review_intro(a: dict) -> str:
    return (f"<b>Разбор</b> · {h(a['title'])} · {h(a['vehicleName'])}\n"
            "Спрашивайте, что нужно, — отвечаю по данным стенда. Закончить — «Завершить разбор».")


def split(text: str, limit: int = 4000) -> list[str]:
    """Telegram принимает до 4096 символов в сообщении: длинный ответ — частями по абзацам."""
    parts: list[str] = []
    while len(text) > limit:
        cut = text.rfind("\n", 0, limit)
        cut = cut if cut > limit // 2 else limit
        parts.append(text[:cut])
        text = text[cut:].lstrip("\n")
    return parts + [text] if text else parts
