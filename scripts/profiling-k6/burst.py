"""Continuous baseline -> burst -> baseline load plan and offline recovery measurement."""
import math
from measurement import quantile


def load_plan(args):
    plan, start, offset = [], 0, 0
    for name, rate, duration in [('baseline', args.rate, args.baseline_seconds),
                                  ('burst', args.burst_rate, args.burst_seconds),
                                  ('recovery', args.rate, args.recovery_seconds)]:
        count = math.ceil(rate * duration / 60) + 1
        plan.append({'name': name, 'rate': rate, 'duration': duration, 'start_seconds': start,
                     'offset': offset, 'fixture_count': count})
        start += duration
        offset += count
    return plan


def counts_match_plan(published, iterations, plan):
    if len(published) != iterations:
        return False
    if any(item.get('stage') not in {stage['name'] for stage in plan} for item in published):
        return False
    return all(stage['fixture_count'] - 1 <= sum(item.get('stage') == stage['name'] for item in published)
               <= stage['fixture_count'] for stage in plan)


def burst_statistics(published, events, plan, started_ms):
    attempts = {item['request_id']: item for item in published if item['routed']}
    results = {}
    for event in events:
        if event['event'] == 'result':
            results.setdefault(event['request_id'], event)

    def pending(at):
        return sum(item['sent_at_ms'] <= at and (rid not in results or results[rid]['at_ms'] > at)
                   for rid, item in attempts.items())

    def successful(cohort):
        return {rid for rid in cohort & results.keys() if results[rid]['outcome'] == 'SUCCEEDED'}

    rows = []
    for stage in plan:
        begin = started_ms + stage['start_seconds'] * 1000
        end = begin + stage['duration'] * 1000
        cohort = {rid for rid, item in attempts.items() if item.get('stage') == stage['name']}
        latencies = [(results[rid]['at_ms'] - attempts[rid]['sent_at_ms']) / 1000 for rid in successful(cohort)]
        samples = [event for event in events if event['event'] == 'queues' and begin <= event['at_ms'] < end]
        rows.append({'stage': stage['name'], 'target_rate_per_min': stage['rate'], 'duration_seconds': stage['duration'],
                     'published': len(cohort), 'succeeded': len(successful(cohort)), 'missing': len(cohort - results.keys()),
                     'completion_p95_s': quantile(latencies, .95), 'completion_p99_s': quantile(latencies, .99),
                     'pending_at_end': pending(end),
                     'ready_peak_sampled': max((event['profiling_request_ready'] for event in samples), default=None),
                     'ready_bytes_peak_sampled': max((event['profiling_request_ready_bytes'] for event in samples
                                                     if 'profiling_request_ready_bytes' in event), default=None)})
    recovery_start = started_ms + plan[2]['start_seconds'] * 1000
    baseline_end = started_ms + plan[1]['start_seconds'] * 1000
    baseline_begin = baseline_end - 60000
    baseline_cohort = {rid for rid, item in attempts.items() if item.get('stage') == 'baseline'}
    baseline_latencies = [(results[rid]['at_ms'] - attempts[rid]['sent_at_ms']) / 1000
                          for rid in successful(baseline_cohort) if baseline_begin <= results[rid]['at_ms'] < baseline_end]
    baseline_p95 = quantile(baseline_latencies, .95)
    pending_reference = quantile([pending(at) for at in range(int(baseline_begin), int(baseline_end), 5000)], .95)
    recovered_after = None
    enough_reference = plan[0]['duration'] >= 60 and len(baseline_latencies) >= 10
    if enough_reference:
        for offset in range(60, plan[2]['duration'] + 1, 5):
            end = recovery_start + offset * 1000
            begin = end - 60000
            recovery_latencies = [(result['at_ms'] - attempts[rid]['sent_at_ms']) / 1000
                                  for rid, result in results.items() if rid in attempts and
                                  attempts[rid].get('stage') == 'recovery' and result['outcome'] == 'SUCCEEDED' and
                                  begin <= result['at_ms'] < end]
            p95 = quantile(recovery_latencies, .95)
            small_pending = all(pending(at) <= pending_reference + 1 for at in range(int(begin), int(end) + 1, 5000))
            if len(recovery_latencies) >= 10 and p95 <= baseline_p95 * 1.1 and small_pending:
                recovered_after = offset
                break
    return {'stages': rows, 'baseline_completion_p95_s': baseline_p95,
            'baseline_completion_samples': len(baseline_latencies),
            'baseline_pending_p95': pending_reference, 'recovery_window_seconds': 60,
            'recovery_latency_multiplier': 1.1, 'recovery_pending_margin': 1,
            'recovery_under_arrivals_seconds': recovered_after,
            'recovery_observation': ('INSUFFICIENT_BASELINE' if not enough_reference else
                                     'RECOVERED_UNDER_ARRIVALS' if recovered_after is not None else 'NOT_RECOVERED_IN_WINDOW')}
