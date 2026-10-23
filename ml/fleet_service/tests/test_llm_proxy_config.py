import json

from fleet_service.llm_proxy import proxy_config, proxy_env, write

from .conftest import test_settings
from .fakes import FAKE_LLM_KEY


def test_config_from_settings():
    s = test_settings(llm_fallback_models="x/fallback, y/second", llm_retries=3, openrouter_provider_sort="price")
    config = proxy_config(s)
    primary, first, second = config["model_list"]
    assert primary["model_name"] == "assistant" and primary["model_info"]["id"] == "openrouter"
    params = primary["litellm_params"]
    assert params["model"] == "openrouter/fake/model"
    assert params["api_base"] == "http://fakes/openrouter/api/v1"
    assert params["extra_body"]["provider"]["require_parameters"] is True
    assert params["extra_body"]["provider"]["sort"] == "price"
    # Запасные модели — свои группы по порядку: по id копии видно, кто ответил.
    assert (first["model_name"], first["model_info"]["id"], first["litellm_params"]["model"]) == (
        "assistant-fallback-1", "fallback-1", "openrouter/x/fallback")
    assert second["model_info"]["id"] == "fallback-2"
    router = config["router_settings"]
    assert router["fallbacks"] == [{"assistant": ["assistant-fallback-1", "assistant-fallback-2"]}]
    assert router["num_retries"] == 3
    assert router["retry_policy"]["RateLimitErrorRetries"] == 3 and router["retry_policy"]["BadRequestErrorRetries"] == 0


def test_key_only_in_private_env_file(tmp_path):
    write(tmp_path, test_settings(smtp_password="smtp-secret", predictive_api_key="pa-secret"))
    text = (tmp_path / "litellm.yaml").read_text(encoding="utf-8")
    assert FAKE_LLM_KEY not in text
    assert json.loads(text)["model_list"][0]["litellm_params"]["api_key"] == "os.environ/FS_OPENROUTER_API_KEY"
    env = tmp_path / "litellm.env"
    assert env.stat().st_mode & 0o777 == 0o600
    # Прокси получает только ключ OpenRouter — не остальные секреты стенда.
    assert env.read_text() == (
        "INITIAL_RETRY_DELAY='1'\nMAX_RETRY_DELAY='10'\nLITELLM_LOCAL_MODEL_COST_MAP='True'\n"
        f"FS_OPENROUTER_API_KEY='{FAKE_LLM_KEY}'\n"
    )


def test_env_file_quotes_values_for_sh(tmp_path):
    write(tmp_path, test_settings(openrouter_api_key="sk-or-a'b$c"))
    assert "FS_OPENROUTER_API_KEY='sk-or-a'\\''b$c'\n" in (tmp_path / "litellm.env").read_text()


def test_without_fallbacks_one_model_and_no_chain():
    config = proxy_config(test_settings())
    [model] = config["model_list"]
    assert "models" not in model["litellm_params"]["extra_body"]
    assert "fallbacks" not in config["router_settings"]


def test_reserve_is_last_in_chain_and_its_key_only_in_env(tmp_path):
    s = test_settings(llm_fallback_models="x/fallback", llm_reserve_base_url="http://reserve_llm:8000/v1/",
                      llm_reserve_model="Qwen/Qwen2.5-7B-Instruct", llm_reserve_api_key="reserve-secret")
    config = proxy_config(s)
    reserve = config["model_list"][-1]
    assert reserve["model_name"] == "assistant-reserve" and reserve["model_info"]["id"] == "reserve"
    assert reserve["litellm_params"]["model"] == "openai/Qwen/Qwen2.5-7B-Instruct"
    assert reserve["litellm_params"]["api_base"] == "http://reserve_llm:8000/v1"
    assert reserve["litellm_params"]["api_key"] == "os.environ/FS_LLM_RESERVE_API_KEY"
    assert config["router_settings"]["fallbacks"] == [{"assistant": ["assistant-fallback-1", "assistant-reserve"]}]
    write(tmp_path, s)
    assert "reserve-secret" not in (tmp_path / "litellm.yaml").read_text()
    assert "FS_LLM_RESERVE_API_KEY='reserve-secret'" in (tmp_path / "litellm.env").read_text()


def test_reserve_without_key_and_without_address():
    s = test_settings(llm_reserve_base_url="http://reserve_llm:8000/v1", llm_reserve_model="m")
    assert proxy_config(s)["model_list"][-1]["litellm_params"]["api_key"] == "no-key"
    assert "FS_LLM_RESERVE_API_KEY" not in proxy_env(s)
    # Адрес без модели (или наоборот) — резерва нет.
    assert len(proxy_config(test_settings(llm_reserve_base_url="http://reserve_llm:8000/v1"))["model_list"]) == 1


def test_proxy_cooldown_is_off_breaker_is_on_the_stand():
    # Cooldown LiteLLM (раз на запрос, без охлаждения запасных групп) выключен: breaker OpenRouter — на стенде.
    assert proxy_config(test_settings())["router_settings"]["disable_cooldowns"] is True


def test_without_model_no_models():
    assert proxy_config(test_settings(llm_model=""))["model_list"] == []


def test_retry_delays():
    env = proxy_env(test_settings(llm_retry_base_seconds=0.5, llm_retry_max_seconds=4, openrouter_api_key=None))
    assert env["INITIAL_RETRY_DELAY"] == "0.5" and env["MAX_RETRY_DELAY"] == "4"
    assert "FS_OPENROUTER_API_KEY" not in env
