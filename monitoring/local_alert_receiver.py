"""Local-only Alertmanager verification receiver; retains the latest 100 sanitized notifications."""
from collections import deque
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

events = deque(maxlen=100)


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/alerts":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024 * 1024:
                raise ValueError()
            notification = json.loads(self.rfile.read(length))
            if notification["status"] not in {"firing", "resolved"} or not isinstance(notification["alerts"], list):
                raise ValueError()
            event = {"status": notification["status"], "alerts": [
                {"status": alert["status"], "alertname": alert.get("labels", {}).get("alertname")}
                for alert in notification["alerts"]]}
        except (ValueError, KeyError, TypeError, AttributeError):
            self.send_error(400)
            return
        events.append(event)
        print(json.dumps(event), flush=True)
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        if self.path != "/alerts":
            self.send_error(404)
            return
        body = json.dumps(list(events)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    print("Local alert verification receiver: http://127.0.0.1:19093/alerts", flush=True)
    HTTPServer(("127.0.0.1", 19093), Receiver).serve_forever()
