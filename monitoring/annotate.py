"""Create a Grafana load-test interval: python3 monitoring/annotate.py START_MS END_MS 'Pool 10 ...'."""
import json
import sys

if __name__ == "__main__":
    import base64
    import os
    import urllib.request

    start, end = int(sys.argv[1]), int(sys.argv[2])
    if start <= 0 or end < start:
        raise ValueError("Require positive start and end >= start in Unix milliseconds")
    credentials = os.environ.get("GRAFANA_ADMIN_USER", "admin") + ":" + os.environ["GRAFANA_ADMIN_PASSWORD"]
    request = urllib.request.Request(
        os.environ.get("GRAFANA_URL", "http://127.0.0.1:3001") + "/api/annotations",
        data=json.dumps({"time": start, "timeEnd": end, "tags": ["loadtest"], "text": sys.argv[3]}).encode(),
        headers={"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode(),
                 "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        print(json.load(response))
