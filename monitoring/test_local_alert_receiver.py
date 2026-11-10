import contextlib
import importlib.util
import io
import json
from http.server import HTTPServer
from pathlib import Path
import threading
import unittest
from urllib.request import Request, urlopen


class ReceiverTest(unittest.TestCase):
    def test_notifications_are_received_and_sensitive_fields_are_not_retained(self):
        spec = importlib.util.spec_from_file_location("receiver", Path(__file__).with_name("local_alert_receiver.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with HTTPServer(("127.0.0.1", 0), module.Receiver) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = f"http://127.0.0.1:{server.server_port}/alerts"
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    for status in ("firing", "resolved"):
                        notification = {"status": status, "alerts": [{"status": status,
                            "labels": {"alertname": "ProfilingPublishBlocked", "secret": "not-retained"}}]}
                        with urlopen(Request(url, json.dumps(notification).encode(), method="POST"), timeout=3) as response:
                            self.assertEqual(response.status, 200)
                with urlopen(url, timeout=3) as response:
                    content = response.read().decode()
                    self.assertEqual([row["status"] for row in json.loads(content)], ["firing", "resolved"])
                    self.assertNotIn("not-retained", content)
            finally:
                server.shutdown()
                thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
