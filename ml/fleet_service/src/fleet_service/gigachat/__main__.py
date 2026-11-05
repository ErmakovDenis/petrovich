"""python -m fleet_service.gigachat — запуск шлюза на порту 8000."""

import uvicorn

from ..config import Settings
from .app import create_gateway


def main() -> None:
    uvicorn.run(create_gateway(Settings()), host="0.0.0.0", port=8000)


if __name__ == "__main__":
    main()
