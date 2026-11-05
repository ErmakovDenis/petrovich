"""Окружение шлюза gigachat_gateway из настроек стенда FS_*.

    python -m fleet_service.gigachat.config /config

пишет в каталог `gigachat.env` (права 600): только то, что нужно шлюзу. Весь .env получает этот одноразовый шаг, а
контейнер шлюза видит ключ GigaChat, но не SMTP, ключ шифрования доступа и ключи других моделей. Том прокси здесь не
подходит: образ прокси сторонний, а ключа GigaChat у него быть не должно.
"""

import sys
from pathlib import Path

from ..config import Settings
from ..envfile import secret_value, write_env_file


def gateway_env(s: Settings) -> dict[str, str]:
    env = {
        "FS_GIGACHAT_SCOPE": s.gigachat_scope,
        "FS_GIGACHAT_BASE_URL": s.gigachat_base_url,
        "FS_GIGACHAT_AUTH_URL": s.gigachat_auth_url,
        "FS_GIGACHAT_MODEL": s.gigachat_model,
        "FS_GIGACHAT_CONCURRENCY": str(s.gigachat_concurrency),
        "FS_GIGACHAT_TIMEOUT_SECONDS": f"{s.gigachat_timeout_seconds:g}",
        "FS_GIGACHAT_ARGUMENTS_FORMAT": s.gigachat_arguments_format,
        "FS_LOG_LEVEL": s.log_level,
        "FS_METRICS_ENABLED": "true" if s.metrics_enabled else "false",
    }
    if secret_value(s.gigachat_credentials):
        env["FS_GIGACHAT_CREDENTIALS"] = secret_value(s.gigachat_credentials)
    if s.gigachat_ca_bundle is not None:
        env["FS_GIGACHAT_CA_BUNDLE"] = str(s.gigachat_ca_bundle)
    return env


def write(directory: Path, s: Settings) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    write_env_file(directory / "gigachat.env", gateway_env(s))


def main(argv: list[str]) -> None:
    if len(argv) != 1:
        sys.exit("использование: python -m fleet_service.gigachat.config <каталог>")
    s = Settings()
    write(Path(argv[0]), s)
    key = "задан" if secret_value(s.gigachat_credentials) else "не задан"
    print(f"окружение шлюза GigaChat записано в {argv[0]}: ключ {key}, модель {s.gigachat_model}, "
          f"потоков {s.gigachat_concurrency}")


if __name__ == "__main__":
    main(sys.argv[1:])
