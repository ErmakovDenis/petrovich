"""Тексты бота (HTML-разметка Telegram) по макетам «Прототип Telegram-бота «Петрович»». Данные со стенда
(названия машин, описания событий) экранируются: они приходят из внешней системы."""

from collections import Counter
from datetime import UTC, date, datetime, timedelta
from html import escape

from .charts import Series, number

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
    "/export — выгрузить события в Excel\n"
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
         show: dict | None = None, repeats: int = 0, check: str | None = None) -> str:
    """Карточка события (макет 3): важность и машина, что и когда, затем по настройкам чата [show] (card стенда) —
    описание, параметр и значение, «Петрович помнит», повторная проверка [check], источник. С решением — коротко:
    решение, машина и событие, кто отметил."""
    show = show or {}
    today = local_now(offset, now_utc).date()
    if a.get("resolution"):
        head, _, tail = decision(a, offset, who=show.get("resolvedBy", True)).partition("\n")
        what = a["title"][:1].lower() + a["title"][1:]
        return "\n".join([head, f"{h(a['vehicleName'])}, {h(what)} {when(a['eventTime'], today)}.", tail])
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
    if show.get("recheck", True) and check:
        lines.append(check)
    if show.get("source", False) and a.get("source"):
        lines.append(f"Источник: {h(a['source'])}")
    return "\n".join(lines)


def recheck(series: Series | None, rpm: Series | None, start: datetime) -> str:
    """«✓ Проверено повторно» (макет 3): есть ли пропуски в данных вокруг события и работал ли двигатель."""
    if series is None or not series.points():
        return "⚠ Проверено повторно: данных по параметру за это время нет — возможен сбой связи с машиной."
    gaps = sum(v is None for v in series.values)
    data = "данные без пропусков" if gaps == 0 else f"пропуски в {gaps} из {len(series.values)} интервалов"
    engine = ""
    if rpm is not None and rpm.points():
        _, v = min(rpm.points(), key=lambda p: abs((p[0] - start).total_seconds()))
        turns = f"{round(v):,}".replace(",", "\u00a0")
        engine = f", двигатель работал ({turns} об/мин)" if v >= ENGINE_RUNNING_RPM else f", двигатель не работал ({turns} об/мин)"
    mark = "✓" if gaps == 0 else "⚠"
    return f"{mark} Проверено повторно: {data}{engine}."


def daily_analytics_note(r: dict) -> str:
    return "Предиктивная проверка недоступна — выводы только по правилам." if r.get("analyticsUnavailable") else ""


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


# Обороты, выше которых двигатель считается работающим (как engine_running в правилах стенда — от 500 об/мин).
ENGINE_RUNNING_RPM = 500

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
        note = daily_analytics_note(r)
        return f"<b>{head}</b>\n{where} событий не найдено — всё спокойно." + (f"\n{note}" if note else "")
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
    note = daily_analytics_note(r)
    if note:
        lines += ["", note]
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


def _span(frm: datetime, to: datetime) -> str:
    """«с 18:00 15 сентября до 18:00 16 сентября»."""
    return f"с {frm:%H:%M} {day_month(frm.date())} до {to:%H:%M} {day_month(to.date())}"


def data_summary(a: dict, series: Series, frm: datetime, to: datetime, until_now: bool) -> str:
    """«Откуда цифра» (макет 6): что было с параметром вокруг события, и само событие."""
    values = [v for _, v in series.points()]
    unit = f" {series.unit}" if series.unit else ""
    last = "сейчас" if until_now else "в конце"
    start = datetime.fromisoformat(a["eventTime"])
    lines = [f"{h(series.caption)} {_span(frm, to)}: от {number(min(values))} до {number(max(values))}{unit}, "
             f"в среднем {number(sum(values) / len(values))}{unit}, {last} {number(values[-1])}{unit}.",
             f"Событие: {h(a['title'].lower())} {when(a['eventTime'], start.date())}"
             + (f" — {h(_sentence(a['description']))}" if a.get("description") else ".")]
    return "\n".join(lines)


def data_caption(a: dict, series: Series, frm: datetime, to: datetime) -> str:
    """Подпись к графику: что на нём и по каким данным."""
    step = series.step_minutes()
    total = len(series.times)
    filled = len(series.points())
    days = f"{frm.day}–{day_month(to.date())}" if frm.date() != to.date() else day_month(to.date())
    coverage = f"данные по всем {total} интервалам" if filled == total else f"данные по {filled} из {total} интервалов"
    return (f"{h(series.caption)}, {h(a['vehicleName'])}, {days}. Красная точка — событие «{h(a['title'])}».\n"
            f"Параметр «{h(series.caption)}» ({h(series.name)})" + (f" · интервалы {step} мин" if step else "")
            + f" · {coverage}")


def no_data(a: dict, frm: datetime, to: datetime) -> str:
    return (f"По параметру «{h(a.get('parameterCaption') or a['parameterName'])}» у {h(a['vehicleName'])} "
            f"{_span(frm, to)} данных нет — AutoGRAPH их не вернул.")


def period_label(frm: datetime, to: datetime) -> str:
    """«за 7–13 сентября», «за 15–16 сентября»; конец периода не включается."""
    last = (to - timedelta(minutes=1)).date()
    if frm.date() == last:
        return f"за {day_month(last)}"
    if frm.month == last.month:
        return f"за {frm.day}–{day_month(last)}"
    return f"за {day_month(frm.date())} – {day_month(last)}"


def period_file(frm: datetime, to: datetime) -> str:
    """Часть имени файла: «07-13.09», «16.09», «17.08-16.09»."""
    last = (to - timedelta(minutes=1)).date()
    if frm.date() == last:
        return f"{last:%d.%m}"
    if frm.month == last.month:
        return f"{frm:%d}-{last:%d.%m}"
    return f"{frm:%d.%m}-{last:%d.%m}"


def export_summary(items: list[dict], vehicles_total: int, label: str) -> str:
    """Выгрузка (макет 10): сколько событий, по каким машинам и типам, решения."""
    if not items:
        return f"Готово. {label.capitalize()} событий нет — файл пустой, только заголовки и список машин."
    n = len(items)
    cars = len({a["vehicleId"] for a in items})
    kinds = Counter(a["title"] for a in items).most_common(3)
    confirmed = sum(a.get("resolution") == "CONFIRMED" for a in items)
    false_alarms = sum(a.get("resolution") == "FALSE_ALARM" for a in items)
    left = n - confirmed - false_alarms
    lines = [f"Готово. {label.capitalize()} — {n} {plural(n, 'событие', 'события', 'событий')} по {cars} "
             f"{plural(cars, 'машине', 'машинам', 'машинам')} из {vehicles_total}:",
             ", ".join(f"{h(t.lower())} — {c}" for t, c in kinds) + "."]
    lines.append("Решений по ним пока нет." if left == n else
                 f"Подтверждено {confirmed}, ложных тревог {false_alarms}, ждут решения {left}.")
    return "\n".join(lines)


def by_vehicles(rows: list[list], label: str) -> str:
    """«По машинам» (макет 10): события по каждой машине и машины без событий."""
    busy = [r for r in rows if r[1]]
    quiet = [r for r in rows if not r[1]]
    lines = [f"<b>По машинам {label}</b>"]
    lines += [f"• {h(r[0])} — {r[1]}, чаще всего {h(r[4].lower())}" + (f"; ждут решения {r[3]}" if r[3] else "")
              for r in busy]
    if quiet:
        lines += ["", f"Без событий — {len(quiet)}:"] + [f"• {h(r[0])}" for r in quiet]
    return "\n".join(lines)


def compare_weeks(cur: list[dict], prev: list[dict], cur_label: str, prev_label: str) -> str:
    """«Сравнить недели» (макет 8): прошлая неделя против позапрошлой — всего, по типам и где больше всего изменилось."""
    def delta(a: int, b: int) -> str:
        return "без изменений" if a == b else f"{'+' if a > b else '−'}{abs(a - b)}"

    lines = ["<b>Сравнение недель</b>",
             f"{cur_label.capitalize()}: {len(cur)} {plural(len(cur), 'событие', 'события', 'событий')}; "
             f"{prev_label}: {len(prev)} ({delta(len(cur), len(prev))})."]
    for field, title in (("title", "По типам"), ("vehicleName", "По машинам — где больше всего изменилось")):
        now, before = Counter(a[field] for a in cur), Counter(a[field] for a in prev)
        names = sorted(set(now) | set(before), key=lambda k: (-abs(now[k] - before[k]), -now[k], k))[:5]
        if names:
            lines += ["", f"{title}:"]
            lines += [f"• {h(k)} — {now[k]} (было {before[k]}, {delta(now[k], before[k])})" for k in names]
    return "\n".join(lines)


_TRANSLIT = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
                     ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t",
                      "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"], strict=True))


def slug(name: str) -> str:
    """Имя файла латиницей: «Колонна №1» → «kolonna-1»."""
    out = "".join(_TRANSLIT.get(ch, ch) for ch in name.lower())
    return "-".join("".join(ch if ch.isascii() and ch.isalnum() else " " for ch in out).split()) or "gruppa"


def group_choice(groups: list[tuple[str, int]], total: int, label: str) -> str:
    return f"Отчёт по какой группе машин? Период — {label}." if groups else f"Отчёт по всем машинам ({total}), {label}."


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
