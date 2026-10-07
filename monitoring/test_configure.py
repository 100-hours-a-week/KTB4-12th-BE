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
