"""Local-only k6 -> RabbitMQ -> production MQ runtime/DB -> durable result ledger.

Analysis alone is synthetic; no model, catalog search, BE or paid API is called.
"""
from __future__ import annotations

import argparse
import base64
import csv
import fcntl
import json
import logging
import math
import multiprocessing
import os
import platform
from pathlib import Path
import random
import resource
import shutil
import subprocess
import sys
import threading
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from uuid import UUID, uuid4, uuid5

from reporting import write_reports
from measurement import evaluate_sustained, phase_events, sustained_statistics
from prometheus_archive import archive_down, archive_tree, restore_archive, save_run_archive
from burst import burst_statistics, counts_match_plan, load_plan

ROOT = Path(__file__).resolve().parent
AI = ROOT.parents[2] / 'KTB4-12th-AI-mq-consumer' / 'profiling'
AMQP = 'amqp://bench:bench-local-only@127.0.0.1:25672/profiling-bench'
DB = 'postgresql+psycopg://bench:bench-local-only@127.0.0.1:25432/profiling_bench'
QUEUES = ('profiling.request', 'profiling.result', 'profiling.request.failed')
AUTH = 'Basic ' + base64.b64encode(b'bench:bench-local-only').decode()


def compose(*args):
    subprocess.run(['docker', 'compose', '--project-name', 'gift-profiling-bench',
                    '--env-file', '/dev/null', '-f', str(ROOT / 'compose.yaml'), *args], check=True)


def broker_api(path):
    request = Request('http://127.0.0.1:25673/api' + path, headers={'Authorization': AUTH})
    with urlopen(request, timeout=5) as response:
        return json.load(response)


def migrate(engine):
    from alembic import command
    from alembic.config import Config
    config = Config(str(AI / 'alembic.ini'))
    config.set_main_option('script_location', str(AI / 'alembic'))
    with engine.begin() as connection:
        config.attributes['connection'] = connection
        command.upgrade(config, 'head')


def declare_queues():
    import pika
    with pika.BlockingConnection(pika.URLParameters(AMQP)) as connection:
        channel = connection.channel()
        channel.exchange_declare('profiling.exchange', exchange_type='direct', durable=True)
        for queue in QUEUES:
            channel.queue_declare(queue, durable=True, arguments={'x-queue-type': 'quorum'})
            channel.queue_bind(queue, 'profiling.exchange', routing_key=queue)


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def peak_rss_bytes():
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return rss if sys.platform == 'darwin' else rss * 1024


def counts_match(iterations, recorded, rate, duration):
    nominal = math.ceil(rate * duration / 60)
    # k6 may schedule one more iteration on the duration boundary.
    return recorded == iterations and nominal <= iterations <= nominal + 1


def read_publish_events(path):
    events = []
    for line in path.read_text().splitlines():
        if line.startswith('{'):
            line = json.loads(line)['msg']
        elif 'msg="' in line:
            line = json.JSONDecoder().raw_decode(line.split('msg=', 1)[1])[0]
        if 'LOAD_EVENT ' in line:
            payload = line.split('LOAD_EVENT ', 1)[1]
            events.append(json.JSONDecoder().raw_decode(payload)[0])
    return events


def summarize(published, events, duration, started_ms, finished_ms, settled, k6_code, dropped):
    attempts = {event['request_id']: event for event in published}
    accepted = {rid for rid, event in attempts.items() if event['routed']}
    results = {}
    for event in events:
        if event['event'] == 'result':
            results.setdefault(event['request_id'], event)
    successes = {rid for rid, result in results.items() if result['outcome'] == 'SUCCEEDED'}
    failures = {rid for rid, result in results.items() if result['outcome'] != 'SUCCEEDED'}
    latencies = [(results[rid]['at_ms'] - attempts[rid]['sent_at_ms']) / 1000
                 for rid in successes & accepted]
    analyses = [event['seconds'] for event in events if event['event'] == 'analysis_end']
    in_window = sum(started_ms <= results[rid]['at_ms'] <= finished_ms for rid in successes & accepted)
    missing = accepted - results.keys()
    arrived = {rid for rid in accepted if attempts[rid]['sent_at_ms'] <= finished_ms}
    terminal = {rid for rid in arrived & results.keys() if results[rid]['at_ms'] <= finished_ms}
    reasons = []
    if not attempts:
        reasons.append('no requests attempted')
    if k6_code or dropped or len(accepted) != len(attempts):
        reasons.append('k6/publish failure or dropped iterations')
    if missing or failures or accepted != settled or results.keys() - accepted:
        reasons.append('missing/failed/unsettled/ambiguous results')
    return {
        'verdict': 'PASS' if not reasons else 'INCOMPLETE_OR_FAILED', 'reasons': reasons,
        'attempted': len(attempts), 'published': len(accepted), 'succeeded': len(successes & accepted),
        'publish_failed': len(attempts) - len(accepted),
        'publish_errors': [event for event in published if not event['routed']],
        'failed': len(failures), 'missing': len(missing), 'settled': len(settled),
        'duplicate_results': sum(e['event'] == 'result' for e in events) - len(results),
        'dropped_iterations': dropped, 'success_per_min_during_load': in_window * 60 / duration,
        'completion_ratio': len(successes & accepted) / len(accepted) if accepted else 0,
        'attempt_success_ratio': len(successes & accepted) / len(attempts) if attempts else 0,
        'completion_p50_s': percentile(latencies, .5), 'completion_p95_s': percentile(latencies, .95),
        'completion_p99_s': percentile(latencies, .99), 'completion_samples': len(latencies),
        'analysis_p95_s': percentile(analyses, .95), 'analysis_samples': len(analyses),
        'analysis_mean_s': sum(analyses) / len(analyses) if analyses else None,
        'backlog_at_load_end': len(arrived - terminal),
        'last_result_after_load_s': None if missing else max(0, (
            max((results[rid]['at_ms'] for rid in accepted & results.keys()), default=finished_ms) - finished_ms) / 1000),
    }


class Ledger:
    def __init__(self, directory):
        self.lock = threading.RLock()
        self.events = []
        self.active = 0
        self.peak = 0
        self.queue_samples = {}
        self.error = None
        self.phase = 'measurement'
        self.target_rate_per_min = 0
        self.stream = (directory / 'events.jsonl').open('w')

    def record(self, event, **fields):
        with self.lock:
            item = {'event': event, 'at_ms': time.time() * 1000, 'phase': self.phase, **fields}
            self.events.append(item)
            self.stream.write(json.dumps(item) + '\n')
            self.stream.flush()
            if event == 'result':
                os.fsync(self.stream.fileno())


def synthetic_analyzer(ledger, by_user, mean_seconds, seed):
    from profiling.types import ProfileOutcome, RunStatus, SearchResult

    def analyze(request):
        item = by_user[request.recipient_user_id]
        rid = item['requestId']
        # shortcut: synthetic service time, validate actual v3 separately before choosing production capacity.
        rng = random.Random(seed + item['sequence'])
        delay = min(12.8, rng.lognormvariate(math.log(mean_seconds) - .36 ** 2 / 2, .36))
        with ledger.lock:
            ledger.active += 1
            ledger.peak = max(ledger.peak, ledger.active)
        ledger.record('analysis_start', request_id=rid)
        start = time.monotonic()
        try:
            time.sleep(delay)
            return ProfileOutcome(recipient_user_id=request.recipient_user_id, source_version=request.source_version,
                                  status=RunStatus.RESULT_READY, input_hash='synthetic',
                                  search=SearchResult(product_ids=[11, 7, 3], query_text='', catalog_version_id=UUID(int=1)))
        finally:
            ledger.record('analysis_end', request_id=rid, seconds=time.monotonic() - start)
            with ledger.lock:
                ledger.active -= 1
    return analyze


def collect_results(ledger, expected, stop, ready):
    import pika
    try:
        with pika.BlockingConnection(pika.URLParameters(AMQP)) as connection:
            channel = connection.channel()
            ready.set()
            while not stop.is_set():
                method, properties, body = channel.basic_get('profiling.result', auto_ack=False)
                if method is None:
                    connection.process_data_events(time_limit=.05)
                    continue
                result = json.loads(body)
                rid = result['requestId']
                if rid not in expected or properties.message_id != rid or properties.correlation_id != rid:
                    raise RuntimeError('Unexpected result identity; message left unacknowledged')
                identity = expected[rid]['payload']
                if any(result['payload'][key] != identity[key] for key in ('recipientUserId', 'sourceVersion')):
                    raise RuntimeError('Result recipient/version mismatch')
                ledger.record('result', request_id=rid, outcome=result['payload']['outcome'])
                channel.basic_ack(method.delivery_tag)
    except Exception as error:
        ledger.error = type(error).__name__ + ': ' + str(error)
        ready.set()


def metrics_server(ledger, run_id, args):
    class Metrics(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/metrics':
                self.send_error(404)
                return
            with ledger.lock:
                labels = f'run_id="{run_id}",workers="{args.workers}",prefetch="{args.prefetch}",phase="{ledger.phase}"'
                observed = phase_events(ledger.events, ledger.phase)
                results = {e['request_id'] for e in observed if e['event'] == 'result' and e['outcome'] == 'SUCCEEDED'}
                analysis = [e['seconds'] for e in observed if e['event'] == 'analysis_end']
                samples = {'active': ledger.active, 'completed_total': len(results),
                           'process_peak_rss_bytes': peak_rss_bytes(),
                           'target_rate_per_min': ledger.target_rate_per_min}
                usage = resource.getrusage(resource.RUSAGE_SELF)
                samples['process_cpu_seconds_total'] = usage.ru_utime + usage.ru_stime
                if analysis:
                    samples['analysis_p95_seconds'] = percentile(analysis, .95)
                samples.update(ledger.queue_samples)
            payload = ''.join(f'profiling_bench_{key}{{{labels}}} {value}\n' for key, value in samples.items()).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4')
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('0.0.0.0', 28765), Metrics)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def sample_queues(ledger, engine):
    samples = {}
    for queue in QUEUES:
        row = broker_api('/queues/profiling-bench/' + queue)
        for source, suffix in [('messages_ready', 'ready'), ('messages_unacknowledged', 'unacked'), ('consumers', 'consumers')]:
            samples[queue.replace('.', '_') + '_' + suffix] = row[source]
        if 'message_bytes_ready' in row:
            samples[queue.replace('.', '_') + '_ready_bytes'] = row['message_bytes_ready']
    samples['db_checked_out'] = engine.pool.checkedout()
    usage = resource.getrusage(resource.RUSAGE_SELF)
    samples['process_cpu_seconds'] = usage.ru_utime + usage.ru_stime
    samples['process_peak_rss_bytes'] = peak_rss_bytes()
    with ledger.lock:
        ledger.queue_samples = samples
    ledger.record('queues', **samples)


def drive_phase(args, directory, ledger, runtime, engine, *, duration, phase):
    import sqlalchemy as sa
    plan = load_plan(args) if getattr(args, 'action', None) == 'burst' and phase == 'measurement' else None
    with ledger.lock:
        ledger.phase = phase
        ledger.peak = 0
    ledger.record('phase_start')
    counters_before = runtime.stats.snapshot()
    cpu_before = resource.getrusage(resource.RUSAGE_SELF)
    wall_before = time.monotonic()
    process = None
    try:
        started_ms = time.time() * 1000
        finished_ms = started_ms + duration * 1000
        environment = {**os.environ, 'FIXTURE': str(directory / 'requests.json'), 'RATE_PER_MIN': str(args.rate),
                       'DURATION_SECONDS': str(duration)}
        environment.pop('LOAD_PLAN', None)
        if plan:
            environment['LOAD_PLAN'] = json.dumps(plan)
            (directory / 'load-plan.json').write_text(json.dumps(plan, indent=2))
        command = ['k6', 'run', '--log-format', 'json', '--console-output', str(directory / 'publish.log'), '--summary-export',
                   str(directory / 'k6-summary.json'), str(ROOT / 'consumer.js')]
        print(f'[{phase}] Running W={args.workers} P={args.prefetch}, {args.rate}/min for {duration}s\nResults: {directory}', flush=True)
        with (directory / 'k6.log').open('w') as output:
            process = subprocess.Popen(command, env=environment, stdout=output, stderr=subprocess.STDOUT)
            while process.poll() is None:
                if time.time() * 1000 > finished_ms + 45000:
                    raise RuntimeError('k6 exceeded its deadline')
                if ledger.error:
                    raise RuntimeError(ledger.error)
                elapsed = (time.time() * 1000 - started_ms) / 1000
                with ledger.lock:
                    ledger.target_rate_per_min = (next((stage['rate'] for stage in plan if
                        stage['start_seconds'] <= elapsed < stage['start_seconds'] + stage['duration']), 0)
                        if plan else args.rate)
                sample_queues(ledger, engine)
                time.sleep(1)
        published = read_publish_events(directory / 'publish.log')
        if published:
            started_ms = (min(item['scenario_start_ms'] for item in published if item.get('stage') == 'baseline')
                          if plan and any(item.get('stage') == 'baseline' for item in published)
                          else min(item['sent_at_ms'] for item in published))
            finished_ms = started_ms + duration * 1000
        accepted = {item['request_id'] for item in published if item['routed']}
        drain_started = time.monotonic()
        deadline = drain_started + args.drain_timeout
        settled = set()
        while time.monotonic() < deadline:
            if ledger.error:
                raise RuntimeError(ledger.error)
            with engine.connect() as connection:
                settled = set(connection.execute(sa.text(
                    "select request_id::text from ai_profile.mq_deliveries where request_id::text = any(:ids) and state = 'SETTLED'"
                ), {'ids': list(accepted)}).scalars())
            with ledger.lock:
                received = {e['request_id'] for e in ledger.events if e['event'] == 'result'}
            if accepted <= received and accepted <= settled and runtime.consumer.status()['inflight'] == 0:
                break
            sample_queues(ledger, engine)
            time.sleep(1)
        sample_queues(ledger, engine)
        k6_summary = json.loads((directory / 'k6-summary.json').read_text())
        dropped = k6_summary.get('metrics', {}).get('dropped_iterations', {}).get('count', 0)
        with ledger.lock:
            result = summarize(published, phase_events(ledger.events, phase), duration, started_ms, finished_ms, settled, process.returncode, dropped)
        iterations = k6_summary.get('metrics', {}).get('iterations', {}).get('count', 0)
        valid_counts = (counts_match_plan(published, iterations, plan) if plan else
                        counts_match(iterations, len(published), args.rate, duration))
        if not valid_counts:
            result['verdict'] = 'INCOMPLETE_OR_FAILED'
            result['reasons'].append('iteration/ledger/planned request count mismatch')
        with ledger.lock:
            observed = phase_events(ledger.events, phase)
        result.update(sustained_statistics(published, observed, started_ms, finished_ms))
        if plan:
            result['burst'] = burst_statistics(published, observed, plan, started_ms)
            with (directory / 'burst-stages.csv').open('w', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(result['burst']['stages'][0]))
                writer.writeheader()
                writer.writerows(result['burst']['stages'])
        cpu_after = resource.getrusage(resource.RUSAGE_SELF)
        elapsed = time.monotonic() - wall_before
        result.update(peak_active=ledger.peak, drain_seconds=time.monotonic() - drain_started,
                      counters={key: value - counters_before.get(key, 0) for key, value in runtime.stats.snapshot().items()},
                      peak_rss_bytes=peak_rss_bytes(), started_at_ms=started_ms, load_ended_at_ms=finished_ms,
                      phase=phase, duration_seconds=duration,
                      phase_cpu_cores_including_drain=(cpu_after.ru_utime + cpu_after.ru_stime - cpu_before.ru_utime - cpu_before.ru_stime) / elapsed)
        ledger.record('phase_end', verdict=result['verdict'])
        with ledger.lock:
            ledger.target_rate_per_min = 0
        with (directory / 'intervals.csv').open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(result['intervals'][0]))
            writer.writeheader()
            writer.writerows(result['intervals'])
        return result
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def run_phases(args, directory, ledger, runtime, engine):
    warmup_seconds = getattr(args, 'warmup', 0)
    if warmup_seconds:
        warmup_result = drive_phase(args, directory / 'warmup', ledger, runtime, engine,
                                   duration=warmup_seconds, phase='warmup')
        (directory / 'warmup' / 'summary.json').write_text(json.dumps(warmup_result, indent=2))
        if warmup_result['verdict'] != 'PASS':
            raise RuntimeError('Warmup did not fully complete; measurement was not started')
    return drive_phase(args, directory, ledger, runtime, engine, duration=args.duration, phase='measurement')


def run_once(args, directory=None):
    import pika
    import sqlalchemy as sa
    from profiling.mq.config import MqConfig
    from profiling.mq.runtime import MqRuntime
    from profiling.mq.store import DbMqDeliveryStore
    from profiling.stores import DbProfileRunStore

    with pika.BlockingConnection(pika.URLParameters(AMQP)) as connection:
        channel = connection.channel()
        for queue in QUEUES:
            row = channel.queue_declare(queue, passive=True).method
            if row.message_count or row.consumer_count:
                raise RuntimeError(f'{queue} is not empty/idle; inspect the dedicated broker. No purge is performed.')
    run_id = str(uuid4())
    directory = directory or ROOT / 'results' / (datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ') + f'-w{args.workers}-p{args.prefetch}-' + run_id[:8])
    directory.mkdir(parents=True)
    namespace = UUID(run_id)
    plan = load_plan(args) if getattr(args, 'action', None) == 'burst' else None
    count = sum(stage['fixture_count'] for stage in plan) if plan else math.ceil(args.rate * args.duration / 60) + 1
    user_base = 1_000_000_000_000 + namespace.int % 1_000_000_000_000
    by_id, by_user = {}, {}
    phase_counts = [('measurement', count)]
    if getattr(args, 'warmup', 0):
        phase_counts.insert(0, ('warmup', math.ceil(args.rate * args.warmup / 60) + 1))
    for phase, phase_count in phase_counts:
        phase_directory = directory / 'warmup' if phase == 'warmup' else directory
        phase_directory.mkdir(exist_ok=True)
        fixtures = [{'schemaVersion': 1, 'requestId': str(uuid5(namespace, f'{phase}:{i}')), 'createdAt': '',
                     'payload': {'recipientUserId': user_base + len(by_id) + i, 'sourceVersion': 1,
                                 'dislikedCategories': [], 'giftPreference': '커피를 좋아합니다', 'reviews': []}}
                    for i in range(phase_count)]
        (phase_directory / 'requests.json').write_text(json.dumps(fixtures, ensure_ascii=False))
        by_id.update({item['requestId']: item for item in fixtures})
        by_user.update({item['payload']['recipientUserId']: {**item, 'sequence': i} for i, item in enumerate(fixtures)})
    manifest = {'run_id': run_id, 'mode': 'synthetic-analysis-real-mq-db', 'workers': args.workers,
                'prefetch': args.prefetch, 'rate_per_min': args.rate, 'duration_seconds': args.duration,
                'drain_timeout_seconds': args.drain_timeout, 'mean_analysis_seconds': args.mean_seconds,
                'seed': args.seed, 'warmup_seconds': getattr(args, 'warmup', 0), 'db_pool_size': 24,
                'python': platform.python_version(), 'platform': platform.platform(),
                'k6': subprocess.check_output(['k6', 'version'], text=True).strip(),
                'scope': 'consumer only; no BE, catalog search, real AI or batch-size test'}
    if plan:
        manifest['load_plan'] = plan
    for name, location in [('backend_commit', ROOT.parents[1]), ('ai_commit', AI)]:
        manifest[name] = subprocess.check_output(['git', '-C', str(location), 'rev-parse', 'HEAD'], text=True).strip()
        manifest[name + '_dirty'] = bool(subprocess.check_output(
            ['git', '-C', str(location), 'status', '--porcelain'], text=True).strip())
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    ledger = Ledger(directory)
    server = None
    stop, sink_ready = threading.Event(), threading.Event()
    sink = threading.Thread(target=collect_results, args=(ledger, by_id, stop, sink_ready), daemon=True)
    engine = sa.create_engine(DB, pool_size=24, max_overflow=0, pool_timeout=10)
    config = MqConfig(AMQP, args.prefetch, args.workers, 1, 15, 5, .2, 3, 1, 5, 3600, 3600)
    runtime = MqRuntime(config, deliveries=DbMqDeliveryStore(engine), runs=DbProfileRunStore(engine),
                        analyze=synthetic_analyzer(ledger, by_user, args.mean_seconds, args.seed), ready=lambda: True)
    try:
        server = metrics_server(ledger, run_id, args)
        sink.start()
        if not sink_ready.wait(15) or ledger.error:
            raise RuntimeError(ledger.error or 'Result collector did not start')
        runtime.start()
        deadline = time.monotonic() + 20
        while not (runtime.consumer.status()['consuming'] and runtime.publisher.connected):
            if time.monotonic() > deadline:
                raise RuntimeError('MQ runtime did not start')
            time.sleep(.1)
        result = run_phases(args, directory, ledger, runtime, engine)
        result.update(manifest)
        if plan:
            result['load_observation'] = (result['burst']['recovery_observation'] if result['verdict'] == 'PASS'
                                           and result['measurement_samples_valid'] else 'INVALID_MEASUREMENT')
            result['observation_notes'] = []
        else:
            result['load_observation'], result['observation_notes'] = evaluate_sustained(result)
        (directory / 'summary.json').write_text(json.dumps(result, indent=2))
        with (directory / 'results.csv').open('w') as stream:
            row = {key: value for key, value in result.items() if not isinstance(value, (dict, list))}
            writer = csv.DictWriter(stream, fieldnames=list(row))
            writer.writeheader()
            writer.writerow(row)
        write_reports(directory, [result], expected_runs=1)
        print(json.dumps({key: result[key] for key in ('verdict', 'reasons', 'attempted', 'published', 'publish_failed', 'succeeded', 'missing',
                          'success_per_min_during_load', 'completion_p95_s', 'peak_active', 'last5_pending_growth_per_min',
                          'load_observation', 'observation_notes')}, indent=2), flush=True)
        return 0 if result['verdict'] == 'PASS' else 1
    except BaseException as error:
        (directory / 'failure.json').write_text(json.dumps({'error': type(error).__name__, 'message': str(error)}))
        raise
    finally:
        runtime.stop()
        runtime.consumer._pool.shutdown(wait=True, cancel_futures=True)
        stop.set()
        if sink.ident:
            sink.join(timeout=10)
        if server:
            server.shutdown()
            server.server_close()
        engine.dispose()
        ledger.stream.close()
        if (directory / 'summary.json').exists():
            save_run_archive(directory)


def isolated_run(args, directory):
    raise SystemExit(run_once(args, directory))


def spawn_run(args, directory):
    # Each comparison uses its own process so peak RSS does not carry over from earlier runs.
    process = multiprocessing.get_context('spawn').Process(target=isolated_run, args=(args, directory))
    process.start()
    try:
        process.join()
    except BaseException:
        process.terminate()
        process.join()
        raise
    return process.exitcode


def prefetch_cases(repeat):
    for round_index in range(repeat):
        order = (8, 16, 32)
        order = order[round_index % 3:] + order[:round_index % 3]
        for prefetch in order:
            yield round_index + 1, prefetch


def run_prefetch(args, *, directory=None, runner=spawn_run):
    action = getattr(args, 'action', None)
    sustained = action in ('sustained', 'burst')
    stage = (f'burst-rate{args.burst_rate}-seconds{args.burst_seconds}' if action == 'burst' else
             f'sustained-rate{args.rate}' if sustained else 'prefetch')
    directory = directory or ROOT / 'results' / (datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ') + f'-{stage}-' + uuid4().hex[:8])
    directory.mkdir(parents=True)
    cases = [(index + 1, 8) for index in range(args.repeat)] if sustained else list(prefetch_cases(args.repeat))
    expected = len(cases)
    summaries = []
    manifest = {**vars(args), 'workers': 8, 'prefetch_values': [8] if sustained else [8, 16, 32],
                'expected_runs': expected, 'warmup_seconds': getattr(args, 'warmup', 0), 'process_isolation': True}
    (directory / 'suite-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(f'{stage}: W=8, P={manifest["prefetch_values"]}\nCombined report: {directory / "report.md"}', flush=True)
    write_reports(directory, summaries, expected_runs=expected)
    try:
        for round_number, prefetch in cases:
            args.workers, args.prefetch = 8, prefetch
            child_directory = directory / f'r{round_number:02d}-w8-p{prefetch}'
            code = runner(args, child_directory)
            summary_path = child_directory / 'summary.json'
            if summary_path.exists():
                result = json.loads(summary_path.read_text())
                result.update(round=round_number, artifact_directory=child_directory.name)
                summaries.append(result)
            if code or not summary_path.exists() or summaries[-1]['verdict'] != 'PASS':
                write_reports(directory, summaries, expected_runs=expected,
                              interruption=f'중단: round={round_number}, prefetch={prefetch}, exit={code}. 해당 실행 원본을 확인한다.')
                return 1
            write_reports(directory, summaries, expected_runs=expected)
    except BaseException as error:
        write_reports(directory, summaries, expected_runs=expected, interruption=f'중단: {type(error).__name__}')
        raise
    print(f'Completed {expected} runs. Report: {directory / "report.md"}', flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['up', 'run', 'matrix', 'prefetch', 'sustained', 'burst', 'down', 'check',
                                         'archive', 'restore', 'archive-down'])
    parser.add_argument('--results-dir', type=str, help='completed result folder or archive root; default results/')
    parser.add_argument('--workers', type=int)
    parser.add_argument('--prefetch', type=int)
    parser.add_argument('--rate', type=int, help='messages/min; sustained/burst default 88, prefetch 120, otherwise 30')
    parser.add_argument('--duration', type=int, help='measurement seconds; sustained default 600, prefetch 60, otherwise 20')
    parser.add_argument('--warmup', type=int, help='warmup seconds; sustained/burst default 120, otherwise 0')
    parser.add_argument('--baseline-seconds', type=int, default=180)
    parser.add_argument('--burst-rate', type=int, default=220)
    parser.add_argument('--burst-seconds', type=int, default=30)
    parser.add_argument('--recovery-seconds', type=int, default=300)
    parser.add_argument('--drain-timeout', type=int, default=900)
    parser.add_argument('--mean-seconds', type=float, default=4.1)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--repeat', type=int, help='prefetch/sustained/burst default 3, otherwise 1')
    args = parser.parse_args()
    if args.action in ('archive', 'restore', 'archive-down'):
        with (ROOT / '.archive.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.action == 'archive-down':
                return archive_down()
            action = archive_tree if args.action == 'archive' else restore_archive
            archive_root = Path(args.results_dir) if args.results_dir else ROOT / 'results'
            if not archive_root.is_absolute():
                archive_root = Path(os.environ.get('PROFILING_BENCH_CALLER_DIR', os.getcwd())) / archive_root
            return action(archive_root)
    args.rate = args.rate if args.rate is not None else {'sustained': 88, 'burst': 88, 'prefetch': 120}.get(args.action, 30)
    if args.action == 'burst':
        if args.duration is not None:
            parser.error('burst uses --baseline-seconds/--burst-seconds/--recovery-seconds instead of --duration')
        if min(args.baseline_seconds, args.burst_rate, args.burst_seconds, args.recovery_seconds) <= 0 or args.burst_rate <= args.rate:
            parser.error('burst durations must be positive and burst-rate must exceed baseline rate')
        args.duration = args.baseline_seconds + args.burst_seconds + args.recovery_seconds
    args.duration = args.duration if args.duration is not None else {'sustained': 600, 'prefetch': 60}.get(args.action, 20)
    args.warmup = args.warmup if args.warmup is not None else (120 if args.action in ('sustained', 'burst') else 0)
    args.repeat = args.repeat if args.repeat is not None else (3 if args.action in ('prefetch', 'sustained', 'burst') else 1)
    if args.action in ('prefetch', 'sustained', 'burst'):
        if args.workers not in (None, 8) or args.prefetch is not None:
            parser.error('comparison actions fix workers/prefetch; omit --workers/--prefetch')
        args.workers, args.prefetch = 8, 8
    else:
        args.workers = args.workers if args.workers is not None else 1
        args.prefetch = args.prefetch if args.prefetch is not None else 1
    if not (1 <= args.workers <= 8 and args.workers <= args.prefetch <= 32):
        parser.error('require 1 <= workers <= 8 and workers <= prefetch <= 32')
    if min(args.rate, args.duration, args.drain_timeout, args.repeat) <= 0 or not 0 < args.mean_seconds <= 12.8:
        parser.error('rate/duration/drain/repeat must be positive; 0 < mean-seconds <= 12.8')
    if args.warmup < 0:
        parser.error('warmup must be non-negative')
    planned = (sum(stage['fixture_count'] for stage in load_plan(args)) if args.action == 'burst'
               else math.ceil(args.rate * args.duration / 60) + 1)
    if planned + math.ceil(args.rate * args.warmup / 60) > 10000:
        parser.error('local run limited to 10000 planned requests')
    if args.action == 'check':
        subprocess.run(['python', '-m', 'unittest', 'discover', '-s', str(ROOT), '-p', 'test_*.py'], check=True)
        return 0
    with (ROOT / '.bench.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error('another profiling benchmark command is running')
        if args.action == 'up':
            import sqlalchemy as sa
            compose('up', '-d', '--wait', '--wait-timeout', '120')
            engine = sa.create_engine(DB)
            try:
                migrate(engine)
            finally:
                engine.dispose()
            declare_queues()
            print('Ready. Grafana: http://127.0.0.1:23001 (bench / bench-local-only)')
            return 0
        if args.action == 'down':
            compose('down')
            return 0
        if not shutil.which('k6'):
            parser.error('k6 is required; install k6 first')
        logging.basicConfig(filename=str(ROOT / 'results-runtime.log'), level=logging.WARNING)
        if args.action in ('prefetch', 'sustained', 'burst'):
            return run_prefetch(args)
        if args.action == 'matrix':
            for _ in range(args.repeat):
                for workers in (1, 2, 4, 8):
                    args.workers, args.prefetch = workers, 8
                    if run_once(args):
                        return 1
            return 0
        return run_once(args)


if __name__ == '__main__':
    raise SystemExit(main())
