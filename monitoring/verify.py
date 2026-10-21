"""Verify real targets, datasource, dashboard provisioning and every dashboard query."""
import base64
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path


def api(path):
    credentials = os.environ.get("GRAFANA_ADMIN_USER", "admin") + ":" + os.environ["GRAFANA_ADMIN_PASSWORD"]
    request = urllib.request.Request(
        os.environ.get("GRAFANA_URL", "http://127.0.0.1:3001") + path,
        headers={"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode()})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def verify():
    proxy = "/api/datasources/proxy/uid/gift-prometheus"
    assert api("/api/datasources/uid/gift-prometheus/health")["status"] == "OK"
    targets = api(proxy + "/api/v1/targets")["data"]["activeTargets"]
    failures = []
    for target in targets:
        print(target["labels"]["job"], target["health"], target["lastError"])
        if target["health"] != "up":
            failures.append(target["labels"]["job"])
    assert {t["labels"]["job"] for t in targets} >= {"spring", "mysql", "cadvisor", "prometheus"}
    for expression in ["mysql_up", "mysql_exporter_collector_success"]:
        result = api(proxy + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))["data"]["result"]
        if not result or any(float(row["value"][1]) != 1 for row in result):
            failures.append(expression)
    substitutions = {"$__rate_interval": "1m", "$__range": "15m", "$mysql_instance": ".*",
                     "$instance": ".*", "$uri": ".*", "$container": ".*"}
    for path in sorted((Path(__file__).parent / "grafana/dashboards").glob("*.json")):
        dashboard = api("/api/dashboards/uid/" + json.loads(path.read_text())["uid"])["dashboard"]
        for panel in dashboard["panels"]:
            for target in panel.get("targets", []):
                expression = target["expr"]
                for key, value in substitutions.items():
                    expression = expression.replace(key, value)
                result = api(proxy + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))["data"]["result"]
                if not result:
                    failures.append(dashboard["title"] + ": " + panel["title"] + " / " + target["refId"])
        print(dashboard["title"], "provisioned")
    if failures:
        print("Missing/failed collection (not zero):", *failures, sep="\n")
        return 1
    print("All targets, DB collectors, five dashboards and panel queries verified.")
    print("Queue depth and exact restart counts require additional instrumentation; OOM behavior was not induced.")
    return 0


if __name__ == "__main__":
    sys.exit(verify())
