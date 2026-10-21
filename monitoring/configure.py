"""Render Prometheus/MySQL configuration from exported environment variables (stdlib only)."""
import json
import os
import re
from pathlib import Path


def required(name):
    value = os.environ.get(name, "")
    if not value or any(char in value for char in "\r\n\0"):
        raise ValueError(f"{name} must be set and contain no line breaks")
    return value


def configure():
    password = required("MONITORING_PASSWORD")
    if len(password) < 32:
        raise ValueError("MONITORING_PASSWORD must contain at least 32 characters")
    interval = os.environ.get("PROMETHEUS_SCRAPE_INTERVAL", "15s")
    if not re.fullmatch(r"[1-9][0-9]*(ms|s|m)", interval):
        raise ValueError("Invalid PROMETHEUS_SCRAPE_INTERVAL")
    port = int(os.environ.get("MYSQL_EXPORTER_PORT", "3306"))
    if not 1 <= port <= 65535:
        raise ValueError("Invalid MYSQL_EXPORTER_PORT")
    target = os.environ.get("APP_METRICS_TARGET", "host.docker.internal:8081")
    host = os.environ.get("MYSQL_EXPORTER_HOST", "host.docker.internal")
    user = required("MYSQL_EXPORTER_USER")
    mysql_password = required("MYSQL_EXPORTER_PASSWORD")
    if any(char in target + host for char in "\r\n\0"):
        raise ValueError("Targets must contain no line breaks")

    runtime = Path(__file__).resolve().parent / "runtime.tmp"
    runtime.mkdir(mode=0o700, exist_ok=True)
    # JSON is valid YAML, so no YAML dependency or shell interpolation is needed.
    jobs = [
        {"job_name": "spring", "metrics_path": "/actuator/prometheus",
         "basic_auth": {"username": "prometheus", "password_file": "/etc/prometheus/monitoring-password"},
         "static_configs": [{"targets": [target]}]},
        {"job_name": "mysql", "static_configs": [{"targets": ["mysqld-exporter:9104"]}]},
        {"job_name": "cadvisor", "static_configs": [{"targets": ["cadvisor:8080"]}],
         "metric_relabel_configs": [{"action": "labeldrop", "regex": "container_label_.*"}]},
        {"job_name": "prometheus", "static_configs": [{"targets": ["localhost:9090"]}]},
    ]
    amount, unit = re.fullmatch(r"([1-9][0-9]*)(ms|s|m)", interval).groups()
    timeout_ms = min(int(amount) * {"ms": 1, "s": 1000, "m": 60000}[unit], 5000)
    config = {"global": {"scrape_interval": interval, "scrape_timeout": f"{timeout_ms}ms"}, "scrape_configs": jobs}
    (runtime / "prometheus.yml").write_text(json.dumps(config, indent=2) + "\n")
    (runtime / "monitoring-password").write_text(password)
    # MySQL option-file quoting: backslashes and double quotes must be escaped.
    def quote(value):
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    (runtime / "exporter.cnf").write_text(
        f"[client]\nhost={quote(host)}\nport={port}\nuser={quote(user)}\npassword={quote(mysql_password)}\n")
    # Individual read-only bind mounts must be readable by exporter container UIDs.
    for name in ("prometheus.yml", "monitoring-password", "exporter.cnf"):
        (runtime / name).chmod(0o644)
    print("Generated monitoring/runtime.tmp configuration; passwords were not printed.")


if __name__ == "__main__":
    configure()
