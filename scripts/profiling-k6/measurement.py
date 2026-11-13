"""Pure calculations for phased, sustained load measurements."""
import math


def quantile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def phase_events(events, phase):
    return [event for event in events if event.get('phase', 'measurement') == phase]


def sustained_statistics(published, events, started_ms, finished_ms):
    attempts = {item['request_id']: item for item in published if item['routed']}
    results = {}
    for event in events:
        if event['event'] == 'result':
            results.setdefault(event['request_id'], event)

    def pending(at_ms):
        arrived = {rid for rid, item in attempts.items() if item['sent_at_ms'] <= at_ms}
        ended = {rid for rid in arrived & results.keys() if results[rid]['at_ms'] <= at_ms}
        return len(arrived - ended)

    duration = (finished_ms - started_ms) / 1000
    cutoff = finished_ms - 300000
    samples = [event for event in events if event['event'] == 'queues' and started_ms <= event['at_ms'] <= finished_ms]
    analysis_starts = {}
    for event in events:
        if event['event'] == 'analysis_start':
            analysis_starts.setdefault(event['request_id'], event['at_ms'])
    waits = [(analysis_starts[rid] - attempts[rid]['sent_at_ms']) / 1000
             for rid in attempts.keys() & analysis_starts.keys()]
    boundaries = [started_ms] + sorted(event['at_ms'] for event in samples) + [finished_ms]
    max_gap = max((b - a) / 1000 for a, b in zip(boundaries, boundaries[1:]))
    intervals = []
    for minute in range(math.ceil(duration / 60)):
        begin = started_ms + minute * 60000
        end = min(finished_ms, begin + 60000)
        cohort = {rid for rid, item in attempts.items() if begin <= item['sent_at_ms'] < end}
        latencies = [(results[rid]['at_ms'] - attempts[rid]['sent_at_ms']) / 1000
                     for rid in cohort & results.keys() if results[rid]['outcome'] == 'SUCCEEDED']
        intervals.append({'minute': minute + 1, 'published': len(cohort),
                          'completed_during_interval': sum(begin <= result['at_ms'] < end and
                                                          result['outcome'] == 'SUCCEEDED'
                                                          for rid, result in results.items() if rid in attempts),
                          'pending_at_end': pending(end), 'completion_samples': len(latencies),
                          'completion_p95_s': quantile(latencies, .95),
                          'completion_p99_s': quantile(latencies, .99)})
    return {
        'last5_pending_start': pending(cutoff) if duration >= 300 else None,
        'last5_pending_end': pending(finished_ms) if duration >= 300 else None,
        'last5_pending_growth_per_min': (pending(finished_ms) - pending(cutoff)) / 5 if duration >= 300 else None,
        'sample_max_gap_s': max_gap, 'measurement_samples_valid': bool(samples) and max_gap <= 15,
        'max_db_checked_out': max((event['db_checked_out'] for event in samples if 'db_checked_out' in event), default=None),
        'request_ready_peak_sampled': max((event['profiling_request_ready'] for event in samples if 'profiling_request_ready' in event), default=None),
        'request_unacked_peak_sampled': max((event['profiling_request_unacked'] for event in samples if 'profiling_request_unacked' in event), default=None),
        'request_ready_bytes_peak_sampled': max((event['profiling_request_ready_bytes'] for event in samples if 'profiling_request_ready_bytes' in event), default=None),
        'preanalysis_wait_p95_s': quantile(waits, .95), 'preanalysis_wait_samples': len(waits),
        'request_payload_mean_bytes': (sum(item['payload_bytes'] for item in published) / len(published)
                                       if published and all('payload_bytes' in item for item in published) else None),
        'intervals': intervals,
    }


def evaluate_sustained(result):
    if result.get('warmup_seconds', 0) < 120 or result['duration_seconds'] < 600:
        return 'INSUFFICIENT_DATA', ['require warmup >= 120s and measurement >= 600s']
    if result['verdict'] != 'PASS':
        return 'INVALID_MEASUREMENT', ['request/result/DB validation failed']
    if not result['measurement_samples_valid']:
        return 'INVALID_MEASUREMENT', ['measurement sampling gap exceeds 15s or no samples']
    growth = result['last5_pending_growth_per_min']
    return ('ACCUMULATION_OBSERVED' if growth > 0 else 'NO_NET_GROWTH_OBSERVED'), []
