import gzip
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prometheus_archive import archive_run, read_archive, save_run_archive, validate_series, write_openmetrics

RUN = 'c0b75076-add4-4241-bece-4fbd5d2ae524'


def response():
    return {'status': 'success', 'data': {'resultType': 'matrix', 'result': [
        {'metric': {'__name__': 'profiling_bench_completed_total', 'run_id': RUN, 'phase': 'measurement'},
         'values': [[1794440000.123, '1'], [1794440005.123, '2']]}
    ]}}


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def read(self):
        return json.dumps(response()).encode()


def make_run(directory):
    (directory / 'summary.json').write_text(json.dumps({'run_id': RUN, 'started_at_ms': 1794440000000,
                                                      'load_ended_at_ms': 1794440060000}))
    (directory / 'events.jsonl').write_text(json.dumps({'at_ms': 1794439880000}) + '\n')


class ArchiveTest(unittest.TestCase):
    def test_archive_keeps_raw_timestamps_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            make_run(directory)
            with patch('prometheus_archive.urlopen', return_value=Response()) as query:
                manifest = archive_run(directory)
                archive_run(directory)
                self.assertEqual(query.call_count, 1)
            self.assertEqual(manifest['sample_count'], 2)
            self.assertEqual(manifest['sample_start'], 1794440000.123)
            with gzip.open(directory / 'prometheus' / 'metrics.json.gz', 'rt') as stream:
                self.assertEqual(json.load(stream), response())
            read_archive(directory / 'prometheus')

    def test_checksum_error_is_detected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            make_run(directory)
            with patch('prometheus_archive.urlopen', return_value=Response()):
                archive_run(directory)
            (directory / 'prometheus' / 'dashboard.json').write_text('corrupted')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                read_archive(directory / 'prometheus')

    def test_restore_text_keeps_labels_times_and_deduplicates(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            make_run(directory)
            with patch('prometheus_archive.urlopen', return_value=Response()):
                archive_run(directory)
            target = directory / 'samples.openmetrics'
            write_openmetrics([directory / 'prometheus', directory / 'prometheus'], target)
            text = target.read_text()
            self.assertEqual(len(text.splitlines()), 3)
            self.assertIn('1794440000.123', text)
            self.assertIn('phase="measurement"', text)
            self.assertTrue(text.endswith('# EOF\n'))

    def test_empty_data_and_wrong_run_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_series([], RUN)
        series = response()['data']['result']
        with self.assertRaises(ValueError):
            validate_series(series, 'c0b75076-add4-4241-bece-4fbd5d2ae525')

    def test_export_failure_does_not_overwrite_test_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            make_run(directory)
            before = (directory / 'summary.json').read_text()
            with patch('prometheus_archive.archive_run', side_effect=OSError('offline')):
                self.assertFalse(save_run_archive(directory))
            self.assertEqual((directory / 'summary.json').read_text(), before)
            self.assertEqual(json.loads((directory / 'prometheus-export-status.json').read_text())['status'], 'ERROR')


if __name__ == '__main__':
    unittest.main()
