from __future__ import annotations

import os
import yaml
from pathlib import Path
from typing import Any


class Config:
    """Hierarchical YAML configuration with environment variable overrides.

    Load order (later overrides earlier):
        1. config.yml (main config)
        2. overrides.yml (optional, gitignored, for local dev)
        3. Environment variables (e.g., LDBC_SCALE_FACTOR)
    """

    ENV_MAPPINGS: dict[str, tuple[str, ...]] = {
        "YTDB_REPO_PATH": ("paths", "ytdb_repo"),
        "DRIVER_REPO_PATH": ("paths", "driver_repo"),
        "LOADER_REPO_PATH": ("paths", "loader_repo"),
        "DRIVER_PARAMS_DIR": ("paths", "driver_params"),
        "BENCH_DB_HOST": ("hosts", "db", "ssh_alias"),
        "BENCH_DB_IP": ("hosts", "db", "public_ip"),
        "YTDB_SHA": ("ytdb", "sha"),
        "DRIVER_SHA": ("driver", "sha"),
        "LOADER_SHA": ("loader", "sha"),
        "LDBC_SCALE_FACTOR": ("ldbc", "scale_factor"),
        "LDBC_VALIDATION_PARAMS_LIMIT": ("ldbc", "validation_params_limit"),
        "LDBC_TIME_COMPRESSION_RATIO": ("ldbc", "time_compression_ratio"),
        "LDBC_IGNORE_SCHEDULED_START_TIMES": ("ldbc", "ignore_scheduled_start_times"),
    }

    SENSITIVE_PATHS: list[tuple[str, ...]] = [
        ("hosts", "db", "public_ip"),
        ("hosts", "db", "ssh_alias"),
        ("database", "password"),
        ("database", "root_password"),
    ]

    def __init__(self, config_path: Path) -> None:
        self.config_path = config_path
        self.data = self._load(config_path)
        self._apply_env_overrides()

    def _load(self, config_path: Path) -> dict[str, Any]:
        config_dir = config_path.parent
        config: dict[str, Any] = {}

        for path in [
            config_path,
            config_dir / "overrides.yml",
        ]:
            if path.exists():
                self._merge(config, yaml.safe_load(path.read_text()) or {})

        return config

    def _apply_env_overrides(self) -> None:
        for env_var, path in self.ENV_MAPPINGS.items():
            if value := os.environ.get(env_var):
                self._set_nested(path, value)

    def _merge(self, base: dict[str, Any], override: dict[str, Any]) -> None:
        for key, value in override.items():
            if key in base and isinstance(base[key], dict) and isinstance(value, dict):
                self._merge(base[key], value)
            else:
                base[key] = value

    def _set_nested(self, path: tuple[str, ...], value: str) -> None:
        *intermediate_keys, final_key = path

        current = self.data
        for key in intermediate_keys:
            current = current.setdefault(key, {})
        current[final_key] = value

    def get(self, *keys: str, default: Any = None) -> Any:
        current: Any = self.data
        for key in keys:
            if isinstance(current, dict) and key in current:
                current = current[key]
            else:
                return default
        return current

    def to_safe_dict(self) -> dict[str, Any]:
        import copy
        result = copy.deepcopy(self.data)

        for path in self.SENSITIVE_PATHS:
            *intermediate_keys, final_key = path
            current = result
            for key in intermediate_keys:
                if key not in current or not isinstance(current[key], dict):
                    break
                current = current[key]
            else:
                current.pop(final_key, None)

        return result
