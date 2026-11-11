import contextlib
import importlib.util
import io
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class ConfigureTest(unittest.TestCase):
    def test_rabbitmq_scrapes_and_payment_discord_receiver_are_opt_in(self):
        spec = importlib.util.spec_from_file_location("configure", Path(__file__).with_name("configure.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MONITORING_PASSWORD": "x" * 32, "MYSQL_EXPORTER_USER": "monitoring",
            "MYSQL_EXPORTER_PASSWORD": "test-only", "PROFILING_MONITORING_ENABLED": "true",
            "RABBITMQ_METRICS_TARGET": "rabbitmq:15692", "RABBITMQ_VHOST": "profiling",
            "ALERTMANAGER_DISCORD_WEBHOOK_URL": "https://discord.com/api/webhooks/123/test-token",
        }, clear=True):
            module.__file__ = str(Path(directory) / "configure.py")
            with contextlib.redirect_stdout(io.StringIO()):
                module.configure()
            runtime = Path(directory) / "runtime.tmp"
            config = json.loads((runtime / "prometheus.yml").read_text())
            jobs = {job["job_name"]: job for job in config["scrape_configs"]}
            profiling_params = jobs["rabbitmq-queues"]["params"]
            self.assertEqual(profiling_params["vhost"], ["profiling"])
            self.assertEqual(profiling_params["family"], ["queue_coarse_metrics", "queue_consumer_count"])
            self.assertRegex("profiling.request", re.compile(profiling_params["queue"][0]))
            payment_params = jobs["rabbitmq-payment-queues"]["params"]
            self.assertEqual(payment_params["vhost"], ["gift"])
            self.assertEqual(payment_params["family"], [
                "queue_coarse_metrics", "queue_consumer_count", "queue_delivery_metrics",
                "queue_metrics", "exchange_metrics",
            ])
            queue_filter = re.compile(payment_params["queue"][0])
            for queue in [
                "gift.payment.approval.recovery.q",
                "gift.payment.cancellation.recovery.dlq", "gift.receipt.email.dlq",
            ]:
                self.assertRegex(queue, queue_filter)
            self.assertEqual(config["rule_files"], [
                "/etc/prometheus/profiling-alerts.yml",
                "/etc/prometheus/payment-rabbitmq-alerts.yml",
            ])
            alertmanager_text = (runtime / "alertmanager.yml").read_text()
            receiver = json.loads(alertmanager_text)
            receivers = {item["name"]: item for item in receiver["receivers"]}
            self.assertTrue(receivers["local-verification"]["webhook_configs"][0]["send_resolved"])
            self.assertEqual(
                receivers["be-error-discord"]["discord_configs"][0]["webhook_url_file"],
                "/etc/alertmanager/discord-webhook",
            )
            self.assertTrue(receivers["be-error-discord"]["discord_configs"][0]["send_resolved"])
            self.assertIn('alertname=~"PaymentRabbitDLQ.*|PaymentRabbitDeliveryLimitExceeded"',
                          receiver["route"]["routes"][0]["matchers"])
            self.assertNotIn("test-token", alertmanager_text)
            self.assertEqual(
                (runtime / "alertmanager-discord-webhook").read_text(),
                "https://discord.com/api/webhooks/123/test-token",
            )
            self.assertEqual(runtime.stat().st_mode & 0o777, 0o700)
            self.assertEqual((runtime / "alertmanager-discord-webhook").stat().st_mode & 0o777, 0o644)
            for url in ["https://example.com/alerts", "http://user:pass@localhost/alerts",
                        "http://localhost/alerts?token=secret"]:
                with self.assertRaises(ValueError):
                    module.alertmanager_config(url)
            for url in ["http://discord.com/api/webhooks/123/token",
                        "https://example.com/api/webhooks/123/token",
                        "https://discord.com/not-a-webhook"]:
                with self.assertRaises(ValueError):
                    module.validate_discord_webhook(url)
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
