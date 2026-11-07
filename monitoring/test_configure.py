import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class ConfigureTest(unittest.TestCase):
    def test_profiling_targets_and_local_receiver_are_opt_in(self):
        spec = importlib.util.spec_from_file_location("configure", Path(__file__).with_name("configure.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MONITORING_PASSWORD": "x" * 32, "MYSQL_EXPORTER_USER": "monitoring",
            "MYSQL_EXPORTER_PASSWORD": "test-only", "PROFILING_MONITORING_ENABLED": "true",
            "RABBITMQ_METRICS_TARGET": "rabbitmq:15692", "RABBITMQ_VHOST": "profiling",
        }, clear=True):
            module.__file__ = str(Path(directory) / "configure.py")
            with contextlib.redirect_stdout(io.StringIO()):
                module.configure()
            runtime = Path(directory) / "runtime.tmp"
            config = json.loads((runtime / "prometheus.yml").read_text())
            jobs = {job["job_name"]: job for job in config["scrape_configs"]}
            self.assertEqual(jobs["rabbitmq-queues"]["params"]["vhost"], ["profiling"])
            self.assertEqual(config["rule_files"], ["/etc/prometheus/profiling-alerts.yml"])
            receiver = json.loads((runtime / "alertmanager.yml").read_text())
            self.assertTrue(receiver["receivers"][0]["webhook_configs"][0]["send_resolved"])
            for url in ["https://example.com/alerts", "http://user:pass@localhost/alerts",
                        "http://localhost/alerts?token=secret"]:
                with self.assertRaises(ValueError):
                    module.alertmanager_config(url)
            os.environ["PROFILING_MONITORING_ENABLED"] = "false"
            with contextlib.redirect_stdout(io.StringIO()):
                module.configure()
            config = json.loads((runtime / "prometheus.yml").read_text())
            self.assertNotIn("alerting", config)
            self.assertNotIn("rabbitmq", {job["job_name"] for job in config["scrape_configs"]})

    def test_render_quotes_secrets_and_rejects_missing_or_multiline_values(self):
        spec = importlib.util.spec_from_file_location("configure", Path(__file__).with_name("configure.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MONITORING_PASSWORD": "x" * 32,
            "MYSQL_EXPORTER_USER": "monitoring",
            "MYSQL_EXPORTER_PASSWORD": 'a"b\\c',
            "PROMETHEUS_SCRAPE_INTERVAL": "1s",
        }, clear=True):
            module.__file__ = str(Path(directory) / "configure.py")
            with contextlib.redirect_stdout(io.StringIO()):
                module.configure()
            runtime = Path(directory) / "runtime.tmp"
            config = json.loads((runtime / "prometheus.yml").read_text())
            self.assertEqual(config["global"]["scrape_timeout"], "1000ms")
            self.assertNotIn("x" * 32, (runtime / "prometheus.yml").read_text())
            self.assertIn('password="a\\"b\\\\c"', (runtime / "exporter.cnf").read_text())
            for bad_value in ["", "bad\nvalue"]:
                os.environ["MYSQL_EXPORTER_USER"] = bad_value
                with self.assertRaises(ValueError):
                    module.configure()


if __name__ == "__main__":
    unittest.main()
