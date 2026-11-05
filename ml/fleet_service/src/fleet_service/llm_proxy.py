"""Конфигурация прокси LiteLLM (сервис llm_proxy в docker compose) из настроек стенда FS_*.

    python -m fleet_service.llm_proxy /config

пишет в каталог два файла: `litellm.yaml` — модели и маршрутизация (JSON — это тоже YAML, PyYAML не нужен) и
`litellm.env` (права 600) — окружение процесса прокси: паузы между повторами (их нельзя задать в конфигурации) и
ключи, на которые ссылается конфигурация (`os.environ/FS_OPENROUTER_API_KEY` в режиме openrouter). Остальные секреты
стенда (SMTP, ключ шифрования доступа, ключ аналитики, ключ GigaChat) прокси — стороннему образу — не передаются: весь
.env получает только этот одноразовый шаг на образе стенда.

Группа `assistant` — основная модель активного провайдера (FS_LLM_PROVIDER). Каждая запасная модель — своя группа
`assistant-fallback-<n>` в цепочке `fallbacks`: прокси переходит к ней, когда повторы основной исчерпаны; последняя
в цепочке — резервная модель вне провайдера (`assistant-reserve`, FS_LLM_RESERVE_*), если задана. Не поле
`models` OpenRouter, как при прямом обращении: через прокси иначе не видно, какая модель ответила (прокси подменяет
`model` ответа именем группы), а по id копии в заголовке x-litellm-model-id — видно.

В режиме gigachat копии ведут в шлюз gigachat_gateway как в обычный OpenAI-совместимый адрес: ключ GigaChat есть
только у шлюза, прокси его не видит.
"""

import json
import sys
from pathlib import Path
from typing import Any

from .agent.llm import FALLBACK_ID, PRIMARY_ID, PROXY_MODEL, RESERVE_GROUP, RESERVE_ID, provider_preferences
from .config import Settings
from .envfile import secret_value as _secret
from .envfile import write_env_file


def _openrouter(s: Settings, name: str, model: str, deployment_id: str) -> dict[str, Any]:
    return {
        "model_name": name,
        "litellm_params": {
            "model": f"openrouter/{model}",
            "api_base": s.openrouter_base_url.rstrip("/"),
            "api_key": "os.environ/FS_OPENROUTER_API_KEY",
            "timeout": s.llm_timeout_seconds,
            # Повторяет роутер прокси по своей политике, клиент OpenAI внутри — нет.
            "max_retries": 0,
            "extra_body": {"provider": provider_preferences(s)},
        },
        "model_info": {"id": deployment_id},
    }


def _openai_compatible(
    s: Settings, name: str, model: str, base_url: str, api_key: str, deployment_id: str
) -> dict[str, Any]:
    """Копия на OpenAI-совместимом адресе (шлюз GigaChat, резерв). Клиенту OpenAI нужен непустой ключ, даже если адрес
    его не проверяет."""
    return {
        "model_name": name,
        "litellm_params": {
            "model": f"openai/{model}",
            "api_base": base_url.strip().rstrip("/"),
            "api_key": api_key,
            "timeout": s.llm_timeout_seconds,
            "max_retries": 0,
        },
        "model_info": {"id": deployment_id},
    }


def _gigachat(s: Settings, name: str, model: str, deployment_id: str) -> dict[str, Any]:
    # Шлюз ключ не проверяет: его порт не опубликован.
    return _openai_compatible(s, name, model, s.gigachat_gateway_url, "no-key", deployment_id)


def proxy_config(s: Settings) -> dict[str, Any]:
    models: list[dict[str, Any]] = []
    chain: list[str] = []
    deployment = _gigachat if s.llm_provider == "gigachat" else _openrouter
    if s.active_model:
        models.append(deployment(s, PROXY_MODEL, s.active_model, PRIMARY_ID))
        for n, model in enumerate(s.active_fallback_models, start=1):
            name = f"{PROXY_MODEL}-fallback-{n}"
            models.append(deployment(s, name, model, FALLBACK_ID.format(n=n)))
            chain.append(name)
        if s.reserve_configured:
            name = RESERVE_GROUP
            # Любой OpenAI-совместимый адрес (vLLM, LiteLLM, другой провайдер).
            key = "os.environ/FS_LLM_RESERVE_API_KEY" if _secret(s.llm_reserve_api_key) else "no-key"
            models.append(_openai_compatible(s, name, s.llm_reserve_model.strip(), s.llm_reserve_base_url, key, RESERVE_ID))
            chain.append(name)
    retries = s.llm_retries
    router: dict[str, Any] = {
        "num_retries": retries,
        "timeout": s.llm_timeout_seconds,
        # Cooldown LiteLLM выключен: он считает сбой раз на запрос, окно у него — срок cooldown, а запасные группы
        # в цепочке не охлаждает (проверено на этой версии). Breaker модели — на стенде (agent/llm.py): открыт —
        # стенд обращается сразу к группе резерва, без запросов к провайдеру.
        "disable_cooldowns": True,
        # 400/401/403 и отказ модерации повторять бесполезно; 408, 429, 5xx и сеть — повторяем.
        "retry_policy": {
            "BadRequestErrorRetries": 0,
            "AuthenticationErrorRetries": 0,
            "ContentPolicyViolationErrorRetries": 0,
            "TimeoutErrorRetries": retries,
            "RateLimitErrorRetries": retries,
            "InternalServerErrorRetries": retries,
        },
    }
    if chain:
        router["fallbacks"] = [{PROXY_MODEL: chain}]
    return {
        "model_list": models,
        "router_settings": router,
        "litellm_settings": {
            # Параметры запроса, которые провайдер не поддерживает, — ошибка, а не молчаливый пропуск (tools).
            "drop_params": False,
            "request_timeout": s.llm_timeout_seconds,
        },
    }


def proxy_env(s: Settings) -> dict[str, str]:
    """Окружение прокси. Пауза перед повтором в LiteLLM: INITIAL_RETRY_DELAY · 2^n, не больше MAX_RETRY_DELAY
    (litellm/constants.py)."""
    env = {
        "INITIAL_RETRY_DELAY": f"{s.llm_retry_base_seconds:g}",
        "MAX_RETRY_DELAY": f"{s.llm_retry_max_seconds:g}",
        # Таблица цен моделей — из образа, без загрузки из интернета при старте.
        "LITELLM_LOCAL_MODEL_COST_MAP": "True",
    }
    if s.llm_provider == "openrouter" and _secret(s.openrouter_api_key):
        env["FS_OPENROUTER_API_KEY"] = _secret(s.openrouter_api_key)
    if s.reserve_configured and _secret(s.llm_reserve_api_key):
        env["FS_LLM_RESERVE_API_KEY"] = _secret(s.llm_reserve_api_key)
    return env


def write(directory: Path, s: Settings) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "litellm.yaml").write_text(json.dumps(proxy_config(s), ensure_ascii=False, indent=2), encoding="utf-8")
    write_env_file(directory / "litellm.env", proxy_env(s))


def main(argv: list[str]) -> None:
    if len(argv) != 1:
        sys.exit("использование: python -m fleet_service.llm_proxy <каталог>")
    s = Settings()
    write(Path(argv[0]), s)
    fallbacks = ", ".join(s.active_fallback_models) or "нет"
    reserve = s.llm_reserve_model if s.reserve_configured else "нет"
    print(f"конфигурация прокси LiteLLM записана в {argv[0]}: провайдер {s.llm_provider}, модель {s.active_model or 'не задана'}, "
          f"запасные модели: {fallbacks}, резерв: {reserve}, повторов {s.llm_retries}")


if __name__ == "__main__":
    main(sys.argv[1:])
