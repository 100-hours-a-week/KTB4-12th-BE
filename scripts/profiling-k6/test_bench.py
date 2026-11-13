import json
import argparse
import contextlib
import io
from pathlib import Path
import tempfile
import unittest

from bench import counts_match, percentile, prefetch_cases, read_publish_events, run_prefetch, summarize
from reporting import write_reports


class SummaryTest(unittest.TestCase):
    def test_duration_boundary_and_missing_ledger(self):
        self.assertTrue(counts_match(10, 10, 30, 20))
        self.assertTrue(counts_match(11, 11, 30, 20))
        self.assertFalse(counts_match(11, 10, 30, 20))
        self.assertFalse(counts_match(9, 9, 30, 20))

    def test_missing_requests_do_not_disappear_from_verdict(self):
        published = [{'request_id': 'one', 'sent_at_ms': 1000, 'routed': True},
                     {'request_id': 'two', 'sent_at_ms': 1000, 'routed': True}]
        events = [{'event': 'result', 'request_id': 'one', 'at_ms': 3000, 'outcome': 'SUCCEEDED'}]
        result = summarize(published, events, 60, 1000, 61000, {'one'}, 0, 0)
        self.assertEqual(result['missing'], 1)
        self.assertEqual(result['completion_ratio'], .5)
        self.assertEqual(result['verdict'], 'INCOMPLETE_OR_FAILED')
        self.assertEqual(result['completion_p95_s'], 2)
        self.assertIsNone(result['last_result_after_load_s'])

    def test_duplicates_and_drain_are_not_counted_as_load_throughput(self):
        published = [{'request_id': 'one', 'sent_at_ms': 1000, 'routed': True}]
        result = {'event': 'result', 'request_id': 'one', 'at_ms': 65000, 'outcome': 'SUCCEEDED'}
        summary = summarize(published, [result, result], 60, 1000, 61000, {'one'}, 0, 0)
        self.assertEqual(summary['succeeded'], 1)
        self.assertEqual(summary['duplicate_results'], 1)
        self.assertEqual(summary['success_per_min_during_load'], 0)

    def test_failure_and_uncertain_publish_cannot_pass(self):
        published = [{'request_id': 'one', 'sent_at_ms': 1000, 'routed': False}]
        events = [{'event': 'result', 'request_id': 'one', 'at_ms': 3000, 'outcome': 'FAILED'}]
        summary = summarize(published, events, 60, 1000, 61000, set(), 1, 1)
        self.assertNotEqual(summary['verdict'], 'PASS')
        self.assertEqual(summary['publish_failed'], 1)
        self.assertEqual(summary['publish_errors'], published)

    def test_k6_console_format(self):
        event = {'request_id': 'one', 'sent_at_ms': 1000, 'routed': True}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'publish.log'
            path.write_text('INFO[0000] LOAD_EVENT ' + json.dumps(event) + ' source=console\n')
            self.assertEqual(read_publish_events(path), [event])
            path.write_text(json.dumps({'msg': 'LOAD_EVENT ' + json.dumps(event)}) + '\n')
            self.assertEqual(read_publish_events(path), [event])
            path.write_text('time="now" level=info msg=' + json.dumps('LOAD_EVENT ' + json.dumps(event)) + '\n')
            self.assertEqual(read_publish_events(path), [event])

    def test_empty_percentile_is_not_zero(self):
        self.assertIsNone(percentile([], .95))
        self.assertEqual(percentile([3, 1, 2], .95), 3)


def sample_summary(prefetch=8):
    result = summarize([{'request_id': 'one', 'sent_at_ms': 1000, 'routed': True}],
                       [{'event': 'result', 'request_id': 'one', 'at_ms': 3000, 'outcome': 'SUCCEEDED'},
                        {'event': 'analysis_end', 'seconds': 1}], 60, 1000, 61000, {'one'}, 0, 0)
    result.update(run_id='test-run', workers=8, prefetch=prefetch, duration_seconds=60,
                  rate_per_min=120, mean_analysis_seconds=4.1, seed=42, peak_rss_bytes=1048576)
    return result


class PrefetchTest(unittest.TestCase):
    def test_order_rotates_and_every_round_contains_all_values(self):
        self.assertEqual(list(prefetch_cases(3)), [(1, 8), (1, 16), (1, 32),
                                                 (2, 16), (2, 32), (2, 8),
                                                 (3, 32), (3, 8), (3, 16)])

    def test_suite_groups_all_runs_and_keeps_workers_fixed(self):
        calls = []

        def fake_runner(args, directory):
            calls.append((args.workers, args.prefetch))
            directory.mkdir()
            (directory / 'summary.json').write_text(json.dumps(sample_summary(args.prefetch)))
            return 0

        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            directory = Path(temporary) / 'suite'
            args = argparse.Namespace(repeat=3, workers=8, prefetch=8)
            self.assertEqual(run_prefetch(args, directory=directory, runner=fake_runner), 0)
            report = (directory / 'report.md').read_text()
            summary = json.loads((directory / 'comparison.json').read_text())
            self.assertEqual(len(calls), 9)
            self.assertTrue(all(workers == 8 for workers, _ in calls))
            self.assertEqual(summary['recorded_runs'], 9)
            self.assertEqual(len(list(directory.glob('r*-w8-p*/summary.json'))), 9)
            self.assertIn('적체 증가', report)
            self.assertIn('최솟값·중앙값·최댓값', report)
            self.assertIn('설정은 자동 변경하지 않는다', report)

    def test_suite_stops_on_missing_summary_and_preserves_partial_report(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            directory = Path(temporary) / 'suite'
            args = argparse.Namespace(repeat=1, workers=8, prefetch=8)
            calls = []

            def fake_runner(args, directory):
                calls.append(args.prefetch)
                return 1

            self.assertEqual(run_prefetch(args, directory=directory, runner=fake_runner), 1)
            self.assertEqual(calls, [8])
            self.assertIn('전체 실행 미완료', (directory / 'report.md').read_text())
            self.assertIsNotNone(json.loads((directory / 'comparison.json').read_text())['interruption'])

    def test_report_uses_median_of_run_p95_and_retains_failed_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            results = [sample_summary() for _ in range(3)]
            for result, p95 in zip(results, [10, 20, 90]):
                result['completion_p95_s'] = p95
            results[-1]['verdict'] = 'INCOMPLETE_OR_FAILED'
            results[-1]['reasons'] = ['publish failure']
            write_reports(directory, results, expected_runs=3)
            report = (directory / 'report.md').read_text()
            self.assertIn('10.00 · 20.00 · 90.00', report)
            self.assertIn('INCOMPLETE_OR_FAILED', report)
            self.assertIn('publish failure', report)


if __name__ == '__main__':
    unittest.main()
