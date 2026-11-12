import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bench import run_phases, run_prefetch
from burst import burst_statistics, counts_match_plan, load_plan
from reporting import write_reports
from test_bench import sample_summary


class BurstTest(unittest.TestCase):
    def test_load_plan_has_no_gap_and_disjoint_fixture_ranges(self):
        args = argparse.Namespace(rate=88, baseline_seconds=180, burst_rate=220, burst_seconds=30, recovery_seconds=300)
        plan = load_plan(args)
        self.assertEqual([item['start_seconds'] for item in plan], [0, 180, 210])
        self.assertEqual([item['offset'] for item in plan], [0, 265, 376])
        self.assertEqual(sum(item['fixture_count'] for item in plan), 817)

    def test_each_segment_must_be_published_not_just_correct_total(self):
        args = argparse.Namespace(rate=60, baseline_seconds=60, burst_rate=120, burst_seconds=30, recovery_seconds=60)
        plan = load_plan(args)
        published = [{'stage': stage['name']} for stage in plan for _ in range(stage['fixture_count'] - 1)]
        self.assertTrue(counts_match_plan(published, len(published), plan))
        published[0]['stage'] = 'burst'
        self.assertFalse(counts_match_plan(published, len(published), plan))

    def fixture(self, delayed=False):
        args = argparse.Namespace(rate=60, baseline_seconds=60, burst_rate=120, burst_seconds=30, recovery_seconds=120)
        plan = load_plan(args)
        published, events = [], []
        for stage in plan:
            interval = 60000 / stage['rate']
            count = int(stage['duration'] * stage['rate'] / 60)
            for index in range(count):
                rid = f'{stage["name"]}-{index}'
                sent = stage['start_seconds'] * 1000 + index * interval
                published.append({'request_id': rid, 'stage': stage['name'], 'sent_at_ms': sent, 'routed': True})
                completed = 500000 + index if delayed and stage['name'] == 'burst' else sent + 1000
                events.append({'event': 'result', 'request_id': rid, 'at_ms': completed, 'outcome': 'SUCCEEDED'})
        return plan, published, events

    def test_recovery_uses_continuing_arrivals_and_needs_a_full_window(self):
        plan, published, events = self.fixture()
        result = burst_statistics(published, events, plan, 0)
        self.assertEqual(result['recovery_observation'], 'RECOVERED_UNDER_ARRIVALS')
        self.assertEqual(result['recovery_under_arrivals_seconds'], 60)
        self.assertGreater(result['stages'][2]['published'], 0)
        self.assertEqual(result['stages'][1]['succeeded'], 60)

    def test_final_drain_does_not_count_as_recovery_under_arrivals(self):
        plan, published, events = self.fixture(delayed=True)
        result = burst_statistics(published, events, plan, 0)
        self.assertIsNone(result['recovery_under_arrivals_seconds'])
        self.assertEqual(result['recovery_observation'], 'NOT_RECOVERED_IN_WINDOW')
        self.assertEqual(result['stages'][1]['missing'], 0)
        self.assertEqual(result['stages'][2]['pending_at_end'], 60)

    def test_burst_runs_only_one_measurement_phase_on_the_same_consumer(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'warmup').mkdir()
            args = argparse.Namespace(action='burst', warmup=120, duration=510)
            runtime = object()
            with patch('bench.drive_phase', return_value={'verdict': 'PASS'}) as driver:
                run_phases(args, directory, object(), runtime, object())
                self.assertEqual(driver.call_count, 2)
                self.assertEqual(driver.call_args_list[1].kwargs['duration'], 510)
                self.assertTrue(all(call.args[3] is runtime for call in driver.call_args_list))

    def test_burst_suite_keeps_workers_fixed_and_writes_stage_report(self):
        plan, published, events = self.fixture()
        burst = burst_statistics(published, events, plan, 0)
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary) / 'suite'
            args = argparse.Namespace(action='burst', rate=88, burst_rate=220, burst_seconds=30,
                                      repeat=3, workers=8, prefetch=8, warmup=120)
            calls = []

            def runner(args, directory):
                calls.append((args.workers, args.prefetch))
                directory.mkdir()
                result = sample_summary()
                result['burst'] = burst
                (directory / 'summary.json').write_text(json.dumps(result))
                return 0

            self.assertEqual(run_prefetch(args, directory=root, runner=runner), 0)
            self.assertEqual(calls, [(8, 8)] * 3)
            report = (root / 'report.md').read_text()
            self.assertIn('순간 집중 유입 구간별 결과', report)
            self.assertIn('회복 중 새 요청', report)
            self.assertIn('평상시 유입 유지 중 회복', report)


if __name__ == '__main__':
    unittest.main()
