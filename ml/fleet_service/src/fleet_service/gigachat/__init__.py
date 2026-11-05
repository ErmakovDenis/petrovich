"""Шлюз GigaChat (сервис gigachat_gateway): OpenAI-совместимый POST /v1/chat/completions поверх API GigaChat.

Прокси LiteLLM и стенд обращаются к нему как к обычной модели. Токен, проверка TLS и преобразование сообщений — здесь:
встроенный провайдер gigachat в LiteLLM отключает проверку сертификата, понижает лишние system до user и теряет
functions_state_id. Запуск: python -m fleet_service.gigachat
"""
