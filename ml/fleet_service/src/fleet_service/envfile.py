"""Файлы окружения для соседних контейнеров (прокси, шлюз GigaChat): права 600, значения читает sh через `. файл`."""

from pathlib import Path

from pydantic import SecretStr


def secret_value(value: SecretStr | None) -> str:
    return value.get_secret_value() if value is not None else ""


def _env_line(name: str, value: str) -> str:
    # Значение в одинарных кавычках: файл читает sh, спецсимволы ключа не раскрываются.
    return f"{name}='" + value.replace("'", "'\\''") + "'\n"


def write_env_file(path: Path, env: dict[str, str]) -> None:
    # Права ставятся до записи: файл не бывает с ключом и открытыми правами.
    path.touch(mode=0o600)
    path.chmod(0o600)
    path.write_text("".join(_env_line(k, v) for k, v in env.items()), encoding="utf-8")
