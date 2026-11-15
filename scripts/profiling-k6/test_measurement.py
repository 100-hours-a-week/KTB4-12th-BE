import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bench import run_phases, run_prefetch, summarize
from measurement import evaluate_sustained, phase_events, sustained_statistics
from reporting import write_reports
from test_bench import sample_summary


class MeasurementTest(unittest.TestCase):
    def test_warmup_results_and_times_are_excluded(self):
        events = [{'phase': 'warmup', 'event': 'analysis_end', 'seconds': 100},
                  {'phase': 'warmup', 'event': 'result', 'request_id': 'warm', 'at_ms': 1000, 'outcome': 'SUCCEEDED'},
                  {'phase': 'measurement', 'event': 'analysis_end', 'seconds': 2},
                  {'phase': 'measurement', 'event': 'result', 'request_id': 'main', 'at_ms': 5000, 'outcome': 'SUCCEEDED'}]
        result = summarize([{'request_id': 'main', 'sent_at_ms': 3000, 'routed': True}],
                           phase_events(events, 'measurement'), 60, 3000, 63000, {'main'}, 0, 0)
        self.assertEqual(result['verdict'], 'PASS')
        self.assertEqual(result['analysis_p95_s'], 2)
        self.assertEqual(result['succeeded'], 1)

    def test_last_five_minutes_and_cohort_latency_include_drain(self):
        published = [{'request_id': 'one', 'sent_at_ms': 1000, 'routed': True, 'payload_bytes': 300},
                     {'request_id': 'late', 'sent_at_ms': 350000, 'routed': True, 'payload_bytes': 500}]
        events = [{'event': 'queues', 'at_ms': at, 'db_checked_out': 7, 'profiling_request_ready': 3,
                   'profiling_request_unacked': 8, 'profiling_request_ready_bytes': 900}
                  for at in range(0, 600001, 10000)]
        events += [{'event': 'result', 'request_id': 'one', 'at_ms': 2000, 'outcome': 'SUCCEEDED'},
                   {'event': 'result', 'request_id': 'late', 'at_ms': 610000, 'outcome': 'SUCCEEDED'},
                   {'event': 'analysis_start', 'request_id': 'late', 'at_ms': 360000}]
        result = sustained_statistics(published, events, 0, 600000)
        self.assertEqual(result['last5_pending_start'], 0)
        self.assertEqual(result['last5_pending_end'], 1)
        self.assertEqual(result['last5_pending_growth_per_min'], .2)
        self.assertEqual(result['intervals'][5]['completion_p95_s'], 260)
        self.assertEqual(result['preanalysis_wait_p95_s'], 10)
        self.assertEqual(result['request_payload_mean_bytes'], 400)
        self.assertEqual(result['max_db_checked_out'], 7)
        self.assertTrue(result['measurement_samples_valid'])

    def test_terminal_failure_is_not_counted_as_pending(self):
        result = sustained_statistics([{'request_id': 'one', 'sent_at_ms': 1000, 'routed': True}],
                                      [{'event': 'result', 'request_id': 'one', 'at_ms': 2000, 'outcome': 'FAILED'}],
                                      0, 600000)
        self.assertEqual(result['last5_pending_end'], 0)
        self.assertFalse(result['measurement_samples_valid'])

    def test_overload_is_an_observation_not_a_failed_experiment(self):
        result = {'warmup_seconds': 120, 'duration_seconds': 600, 'verdict': 'PASS',
                  'measurement_samples_valid': True, 'last5_pending_growth_per_min': 50}
        self.assertEqual(evaluate_sustained(result), ('ACCUMULATION_OBSERVED', []))
        result['last5_pending_growth_per_min'] = 0
        self.assertEqual(evaluate_sustained(result), ('NO_NET_GROWTH_OBSERVED', []))
        result['measurement_samples_valid'] = False
        self.assertEqual(evaluate_sustained(result)[0], 'INVALID_MEASUREMENT')

    def test_short_run_does_not_report_five_minute_growth_as_zero(self):
        result = sustained_statistics([], [], 0, 60000)
        self.assertIsNone(result['last5_pending_growth_per_min'])
        self.assertFalse(result['measurement_samples_valid'])

    def test_warmup_finishes_before_measurement_on_same_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'warmup').mkdir()
            args = argparse.Namespace(warmup=120, duration=600)
            runtime, ledger, engine = object(), object(), object()
            with patch('bench.drive_phase', side_effect=[{'verdict': 'PASS'}, {'verdict': 'PASS', 'marker': 'main'}]) as driver:
                result = run_phases(args, directory, ledger, runtime, engine)
                self.assertEqual(result['marker'], 'main')
                self.assertEqual([call.kwargs['phase'] for call in driver.call_args_list], ['warmup', 'measurement'])
                self.assertTrue(all(call.args[3] is runtime for call in driver.call_args_list))
            with patch('bench.drive_phase', return_value={'verdict': 'INCOMPLETE_OR_FAILED'}) as driver:
                with self.assertRaises(RuntimeError):
                    run_phases(args, directory, ledger, runtime, engine)
                self.assertEqual(driver.call_count, 1)

    def test_sustained_suite_records_all_repetitions_despite_accumulation(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            directory = Path(temporary) / 'suite'
            args = argparse.Namespace(action='sustained', rate=88, repeat=3, workers=8, prefetch=8, warmup=120)
            calls = []

            def fake_runner(args, child):
                calls.append((args.workers, args.prefetch))
                child.mkdir()
                result = sample_summary()
                result.update(warmup_seconds=120, duration_seconds=600, load_observation='ACCUMULATION_OBSERVED')
                (child / 'summary.json').write_text(json.dumps(result))
                return 0

            self.assertEqual(run_prefetch(args, directory=directory, runner=fake_runner), 0)
            self.assertEqual(calls, [(8, 8)] * 3)
            report = (directory / 'report.md').read_text()
            self.assertIn('워밍업 120초', report)
            self.assertIn('ACCUMULATION_OBSERVED', report)
            self.assertIn('적체 관측 자체를 테스트 실패로 판정하지 않는다', report)


if __name__ == '__main__':
    unittest.main()
