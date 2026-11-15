"""Preserve scraped float samples and replay them in a separate portfolio Grafana."""
from __future__ import annotations

import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from datetime import UTC, datetime
from urllib.parse import urlencode
from urllib.request import urlopen
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent
PROMETHEUS = 'http://127.0.0.1:29090'
IMAGE = 'prom/prometheus:v3.15.0'


def validate_series(series, run_id):
    UUID(run_id)
    count = 0
    for item in series:
        labels = item['metric']
        if labels.get('run_id') != run_id or not re.fullmatch(r'profiling_bench_[a-zA-Z0-9_:]+', labels.get('__name__', '')):
            raise ValueError('Unexpected run ID or metric name in archive')
        if item.get('histograms'):
            raise ValueError('Native histograms require a separate archival format')
        for key, value in labels.items():
            if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*', key) or not isinstance(value, str):
                raise ValueError('Invalid metric label')
        for timestamp, value in item.get('values', []):
            if not math.isfinite(float(timestamp)):
                raise ValueError('Invalid timestamp')
            float(value)
            count += 1
    if count == 0:
        raise ValueError('No stored samples found; retention may have expired or scraping was unavailable')
    return count


def read_archive(directory):
    manifest = json.loads((directory / 'manifest.json').read_text())
    for name, checksum in manifest['sha256'].items():
        if Path(name).name != name or hashlib.sha256((directory / name).read_bytes()).hexdigest() != checksum:
            raise ValueError(f'Archive checksum mismatch: {name}')
    with gzip.open(directory / 'metrics.json.gz', 'rt') as stream:
        payload = json.load(stream)
    validate_series(payload['data']['result'], manifest['run_id'])
    return manifest, payload


def archive_run(directory):
    directory = Path(directory)
    destination = directory / 'prometheus'
    if destination.exists():
        manifest, _ = read_archive(destination)
        return manifest
    result = json.loads((directory / 'summary.json').read_text())
    run_id = str(UUID(result['run_id']))
    times = [result['started_at_ms'] / 1000, result['load_ended_at_ms'] / 1000]
    with (directory / 'events.jsonl').open() as stream:
        for line in stream:
            times.append(json.loads(line)['at_ms'] / 1000)
    start = min(times) - 60
    end = min(time.time(), max(times) + 30)
    selector = f'{{__name__=~"profiling_bench_.*",run_id="{run_id}"}}'
    query = f'{selector}[{math.ceil(end - start)}s]'
    url = PROMETHEUS + '/api/v1/query?' + urlencode({'query': query, 'time': f'{end:.3f}'})
    with urlopen(url, timeout=60) as response:
        payload = json.load(response)
    if payload.get('status') != 'success' or payload.get('warnings') or payload.get('infos'):
        raise ValueError('Prometheus export returned an error or warnings; not marking as archived')
    if payload['data']['resultType'] != 'matrix':
        raise ValueError('Expected raw range-vector samples')
    series = payload['data']['result']
    count = validate_series(series, run_id)
    temporary = Path(tempfile.mkdtemp(prefix='.prometheus-export-', dir=directory))
    try:
        with gzip.open(temporary / 'metrics.json.gz', 'wt', encoding='utf-8') as stream:
            json.dump(payload, stream, ensure_ascii=False)
        shutil.copyfile(ROOT / 'dashboard.json', temporary / 'dashboard.json')
        shutil.copyfile(ROOT / 'prometheus.yml', temporary / 'prometheus.yml')
        stamps = [float(at) for item in series for at, _ in item['values']]
        files = ['metrics.json.gz', 'dashboard.json', 'prometheus.yml']
        manifest = {'format_version': 1, 'run_id': run_id, 'exported_at': time.time(),
                    'query': query, 'query_start': start, 'query_end': end,
                    'sample_start': min(stamps), 'sample_end': max(stamps),
                    'series_count': len(series), 'sample_count': count,
                    'dashboard_capture': 'configuration at export time; may differ from original run',
                    'scope': 'scraped profiling_bench_* float samples only; not all Prometheus data or a TSDB snapshot',
                    'limitations': 'no stale markers, exemplars, native histograms or unsampled application events',
                    'sha256': {name: hashlib.sha256((temporary / name).read_bytes()).hexdigest() for name in files}}
        (temporary / 'manifest.json').write_text(json.dumps(manifest, indent=2))
        try:
            temporary.rename(destination)
        except OSError:
            if not destination.exists():
                raise
            manifest, _ = read_archive(destination)
        return manifest
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def save_run_archive(directory):
    """An export failure must not erase or change a valid load-test result."""
    directory = Path(directory)
    try:
        manifest = archive_run(directory)
        status = {'status': 'SAVED', 'run_id': manifest['run_id'], 'sample_count': manifest['sample_count']}
        print(f'Prometheus archive saved: {directory / "prometheus"} ({manifest["sample_count"]} samples)', flush=True)
    except Exception as error:
        status = {'status': 'ERROR', 'error': type(error).__name__, 'message': str(error)}
        print(f'WARNING: Prometheus archive not saved for {directory.name}: {error}', flush=True)
    (directory / 'prometheus-export-status.json').write_text(json.dumps(status, indent=2))
    return status['status'] == 'SAVED'


def archive_tree(root):
    root = Path(root).resolve()
    summaries = [root / 'summary.json'] if (root / 'summary.json').exists() else sorted(root.rglob('summary.json'))
    saved = failed = skipped = 0
    for summary in summaries:
        directory = summary.parent
        # warmup/summary.json belongs to its parent run and has no independent run ID.
        if not (directory / 'manifest.json').exists() or not (directory / 'events.jsonl').exists():
            skipped += 1
            continue
        if save_run_archive(directory):
            saved += 1
        else:
            failed += 1
    print(f'Archive: saved={saved}, failed={failed}, skipped={skipped}', flush=True)
    return 0 if saved and not failed else 1


def write_openmetrics(archives, target):
    samples = {}
    start, end = math.inf, -math.inf
    for directory in archives:
        manifest, payload = read_archive(directory)
        start, end = min(start, manifest['sample_start']), max(end, manifest['sample_end'])
        for item in payload['data']['result']:
            labels = dict(item['metric'])
            metric = labels.pop('__name__')
            suffix = '{' + ','.join(f'{key}={json.dumps(value, ensure_ascii=False)}' for key, value in sorted(labels.items())) + '}'
            for timestamp, value in item['values']:
                key = (metric + suffix, round(float(timestamp) * 1000))
                number = float(value)
                normalized = 'NaN' if math.isnan(number) else '+Inf' if number == math.inf else '-Inf' if number == -math.inf else value
                if key in samples and samples[key] != normalized:
                    raise ValueError('Conflicting values at the same series/timestamp')
                samples[key] = normalized
    if not samples:
        raise ValueError('No archived samples to restore')
    with target.open('w') as stream:
        for (metric, timestamp), value in sorted(samples.items()):
            stream.write(f'{metric} {value} {timestamp / 1000:.3f}\n')
        stream.write('# EOF\n')
    return start, end


def archive_compose(*args, environment=None):
    subprocess.run(['docker', 'compose', '--project-name', 'gift-profiling-portfolio', '--env-file', '/dev/null',
                    '-f', str(ROOT / 'compose.archive.yaml'), *args], env=environment, check=True)


def restore_archive(root):
    root = Path(root).resolve()
    archives = [root] if (root / 'metrics.json.gz').exists() else sorted(path.parent for path in root.rglob('metrics.json.gz'))
    if not archives:
        raise ValueError('No archives found; run archive before retention expires')
    runtime = ROOT / '.archive-runtime' / uuid4().hex[:12]
    runtime.mkdir(parents=True)
    data = runtime / 'tsdb'
    data.mkdir()
    start, end = write_openmetrics(archives, runtime / 'samples.openmetrics')
    dashboard = json.loads((archives[-1] / 'dashboard.json').read_text())
    dashboard['time'] = {'from': datetime.fromtimestamp(start - 30, UTC).isoformat(),
                         'to': datetime.fromtimestamp(end + 30, UTC).isoformat()}
    dashboard['refresh'] = ''
    for variable in dashboard.get('templating', {}).get('list', []):
        variable['current'] = {'text': 'All', 'value': '$__all'}
    (runtime / 'dashboard.json').write_text(json.dumps(dashboard, indent=2))
    # All archives use the same metric names/labels, so the standard panels can query imported blocks.
    (runtime / 'prometheus.yml').write_text('global:\n  scrape_interval: 5s\nscrape_configs: []\n')
    subprocess.run(['docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}',
                    '--volume', f'{runtime}:/data', '--entrypoint', 'promtool', IMAGE,
                    'tsdb', 'create-blocks-from', 'openmetrics', '/data/samples.openmetrics', '/data/tsdb'], check=True)
    environment = {**os.environ, 'ARCHIVE_RUNTIME': str(runtime), 'ARCHIVE_UID': str(os.getuid()), 'ARCHIVE_GID': str(os.getgid())}
    archive_compose('up', '-d', '--wait', '--wait-timeout', '90', environment=environment)
    (ROOT / '.archive-runtime' / 'latest.json').write_text(json.dumps({'runtime': str(runtime), 'start': start, 'end': end}))
    print('Portfolio Grafana: http://127.0.0.1:23002/d/profiling-bench (bench / bench-local-only)', flush=True)
    return 0


def archive_down():
    latest = json.loads((ROOT / '.archive-runtime' / 'latest.json').read_text())
    environment = {**os.environ, 'ARCHIVE_RUNTIME': latest['runtime'], 'ARCHIVE_UID': str(os.getuid()), 'ARCHIVE_GID': str(os.getgid())}
    archive_compose('down', environment=environment)
    return 0
