"""Render collected measurements in the agreed report format; never invent missing metrics."""
import csv
import json
from statistics import median


def number(value):
    return '미수집' if value is None else f'{value:.2f}'


def comparison_row(result):
    return {
        '실행': result.get('artifact_directory', result['run_id'][:8]),
        '모드': '모의 분석·실제 MQ/DB',
        'W/P/B': f'{result["workers"]}/{result["prefetch"]}/—',
        '실제 유입': f'{result["published"]}건/{result["duration_seconds"]}초',
        '성공/분': result['success_per_min_during_load'],
        '완료율(%)': result['completion_ratio'] * 100,
        '분석 p95(초)': result.get('analysis_p95_s'),
        '전체 p95(초)': result.get('completion_p95_s'),
        '적체 증가(종료 시 미완료 건수)': result.get('backlog_at_load_end'),
        '해소 시간(초)': result.get('last_result_after_load_s'),
        '최대 RSS(MiB)': result['peak_rss_bytes'] / 1024 ** 2,
        '판정': result['verdict'] + ' · ' + result.get('load_observation', '성능 확정 보류'),
    }


def write_reports(directory, results, *, expected_runs, interruption=None):
    rows = [comparison_row(result) for result in results]
    with (directory / 'comparison.csv').open('w', newline='') as stream:
        if rows:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    (directory / 'comparison.json').write_text(json.dumps(
        {'expected_runs': expected_runs, 'recorded_runs': len(results), 'interruption': interruption,
         'runs': results}, indent=2, ensure_ascii=False))
    lines = ['# AI 프로파일링 MQ 성능 테스트 보고서', '', '## 1. 목적·실행 조건', '',
             '모의 분석을 사용해 실제 RabbitMQ·AI MQ 처리·DB 저장·결과 수집 경로를 비교한다.',
             'BE 발행 배치·실제 모델·검색은 이 실험에 포함하지 않는다.', '',
             f'- 예정 실행: {expected_runs}회 / 결과 수집: {len(results)}회',
             '- 워밍업 여부·시간은 실행 조건에 표시한다. warmup/ 원본은 본 측정 통계에서 제외한다.',
             '- 원본: 각 실행 폴더의 manifest.json, requests.json, publish.log, events.jsonl, k6-summary.json, summary.json', '']
    lines += ['- Prometheus 장기 보존: 실행 정리 후 prometheus/에 별도 저장한다. 실제 저장 성공 여부는 prometheus-export-status.json의 SAVED를 확인한다.',
              '- 보고서 PASS는 시계열 파일 저장 성공을 보장하지 않는다. 저장 실패 시 원래 측정 결과를 바꾸지 않고 별도 오류를 남긴다.', '']
    if interruption:
        lines += [f'**{interruption}**', '실패한 실행을 제외하고 전체 비교가 성공했다고 판단하지 않는다.', '']
    if len(results) != expected_runs:
        lines += ['**전체 실행 미완료: 설정 선택을 보류한다.**', '']
    if results:
        settings = {(r['rate_per_min'], r['duration_seconds'], r['mean_analysis_seconds'], r['seed'], r.get('warmup_seconds', 0)) for r in results}
        for rate, duration, mean, seed, warmup in sorted(settings):
            lines += [f'- 목표 유입 {rate}건/분 · 워밍업 {warmup}초 후 완료 확인 · 본 측정 {duration}초 · 모의 평균 목표 {mean}초 · seed {seed}']
        if len(settings) != 1:
            lines += ['- 조건이 서로 다른 실행이 있어 직접적인 설정 비교를 보류한다.']
    lines += ['', '## 2. 결과 비교', '']
    if rows:
        lines += ['| ' + ' | '.join(rows[0]) + ' |', '| ' + ' | '.join('---' for _ in rows[0]) + ' |']
        for row in rows:
            lines += ['| ' + ' | '.join(number(v) if isinstance(v, (int, float)) or v is None else str(v)
                                      for v in row.values()) + ' |']
    else:
        lines += ['아직 수집된 결과가 없다.']
    lines += ['', '- 성공/분은 유입 구간의 고유 성공 건수다. 회복 중 성공은 포함하지 않는다.',
              '- 완료율은 발행 확인된 요청 기준이다. 전체 시도·발행 실패는 아래에서 별도 확인한다.',
              '- 전체 p95는 발행 시도부터 결과 수집까지이며 BE 최종 DB 반영 시간은 아니다.',
              '- 적체 열은 종료 시 미완료 건수다. Ready 수나 마지막 5분의 증가율과 구분한다.',
              '- 해소 시간은 유입 종료부터 마지막 결과 수집까지다. 미완료가 있으면 실제 해소 시간이 아니다.',
              '- 최대 RSS는 워밍업을 포함한 프로세스 최고값이다. prefetch/sustained는 실행별 새 프로세스를 사용한다.', '',
              '## 3. 반복 집계·지연 분포', '',
              '| W/P | 횟수 | 성공/분 최솟값·중앙값·최댓값 | 실행별 완료 p95 최솟값·중앙값·최댓값(초) |',
              '| --- | --- | --- | --- |']
    for workers, prefetch in sorted({(r['workers'], r['prefetch']) for r in results}):
        group = [r for r in results if (r['workers'], r['prefetch']) == (workers, prefetch)]
        speeds = [r['success_per_min_during_load'] for r in group]
        p95s = [r['completion_p95_s'] for r in group if r.get('completion_p95_s') is not None]
        triplet = lambda values: ' · '.join(number(v) for v in (min(values), median(values), max(values))) if values else '미수집'
        lines += [f'| {workers}/{prefetch} | {len(group)} | {triplet(speeds)} | {triplet(p95s)} |']
    lines += ['', '실행별 p95를 평균 내거나 위 집계를 전체 요청을 합친 p95로 해석하지 않는다.', '',
              '| 실행 | 분석 평균(초) | 완료 p50 / p95 / p99(초) | 성공 완료 표본 |',
              '| --- | --- | --- | --- |']
    for result, row in zip(results, rows):
        quantiles = ' / '.join(number(result.get(f'completion_p{p}_s')) for p in (50, 95, 99))
        lines += [f'| {row["실행"]} | {number(result.get("analysis_mean_s"))} | {quantiles} | {result["completion_samples"]} |']
    burst_results = [(result, row) for result, row in zip(results, rows) if 'burst' in result]
    if burst_results:
        lines += ['', '### 순간 집중 유입 구간별 결과', '',
                  '기본 유입률은 평상시 값이며 본 측정 전체가 같은 유입률인 것은 아니다. 아래 구간별 목표를 기준으로 비교한다.',
                  '평상시→집중→회복은 같은 소비자·한 번의 연속 부하다. 사이에 대기 작업을 비우지 않는다.', '',
                  '| 실행 | 구간 | 목표 건/분 | 시간(초) | 발행 / 성공 / 미완료 | 완료 지연 95백분위수(초) | 99백분위수(초) | 구간 종료 시 미완료 | 전달 전 대기 최고 표본 |',
                  '| --- | --- | --- | --- | --- | --- | --- | --- | --- |']
        names = {'baseline': '평상시', 'burst': '집중 유입', 'recovery': '회복 중 새 요청'}
        for result, row in burst_results:
            for stage in result['burst']['stages']:
                lines += [f'| {row["실행"]} | {names[stage["stage"]]} | {stage["target_rate_per_min"]} '
                          f'| {stage["duration_seconds"]} | {stage["published"]} / {stage["succeeded"]} / {stage["missing"]} '
                          f'| {number(stage["completion_p95_s"])} | {number(stage["completion_p99_s"])} '
                          f'| {stage["pending_at_end"]} | {number(stage["ready_peak_sampled"])} |']
        lines += ['', '구간별 지연·완료는 해당 구간에 발행한 요청을 끝까지 추적한 값이다. 다음 구간·최종 정리 중 완료도 포함한다.',
                  '구간 종료 시 미완료는 해당 구간 요청만이 아니라 그때까지 들어온 전체 본 측정 요청 기준이다.',
                  '아래 회복 시간은 유입을 끊은 뒤의 해소 시간과 구분한다.', '',
                  '| 실행 | 평상시 완료 지연 기준(초) | 평상시 미완료 기준(건) | 평상시 유입 유지 중 회복 관측(초) |',
                  '| --- | --- | --- | --- |']
        for result, row in burst_results:
            burst = result['burst']
            recovered = number(burst['recovery_under_arrivals_seconds']) if burst['recovery_under_arrivals_seconds'] is not None else '관측 시간 내 확인 못함'
            lines += [f'| {row["실행"]} | {number(burst["baseline_completion_p95_s"])} '
                      f'| {number(burst["baseline_pending_p95"])} | {recovered} |']
        lines += ['', '회복 관측 기준: 집중 직전 60초 완료 요청의 95백분위 지연과 미완료 표본의 95백분위를 기준으로 한다.',
                  '회복 구간에서 60초 창을 5초씩 이동하며, 그 창의 5초별 미완료가 기준+1건 이내이고',
                  '완료된 회복 구간 요청이 10건 이상이며 지연의 95백분위수가 기준의 110% 이내인 첫 창의 종료 시점을 기록한다.',
                  '이는 실험용 비교 기준이며 운영 대기시간 약속이나 장애 판정 기준이 아니다. 평상시 기준 표본이 10건 미만이면 판정을 보류한다.',
                  '회복 관찰이 끝난 뒤 유입을 중단한 최종 정리는 정합성 확인용이다. 이때만 해소됐다고 유입 유지 중 회복으로 표시하지 않는다.',
                  '본 실험의 마지막 5분 미완료 변화는 집중 유입 후 회복 구간의 값이며 지속 유입 용량 판정에 쓰지 않는다.']
    lines += ['', '### 큐 상한 산정용 관측값', '',
              '| 실행 | 마지막 5분 미완료 시작→종료 | 증가(건/분) | Ready 최대(표본) | Unacked 최대(표본) | Ready 본문 최대(bytes) | 발행~분석시작 p95(초) | 평균 요청 본문(bytes) |',
              '| --- | --- | --- | --- | --- | --- | --- | --- |']
    for result, row in zip(results, rows):
        lines += [f'| {row["실행"]} | {number(result.get("last5_pending_start"))} → {number(result.get("last5_pending_end"))} '
                  f'| {number(result.get("last5_pending_growth_per_min"))} | {number(result.get("request_ready_peak_sampled"))} '
                  f'| {number(result.get("request_unacked_peak_sampled"))} | {number(result.get("request_ready_bytes_peak_sampled"))} '
                  f'| {number(result.get("preanalysis_wait_p95_s"))} | {number(result.get("request_payload_mean_bytes"))} |']
    lines += ['', '발행~분석시작은 HTTP·큐·DB 전처리를 포함하며 순수 큐 대기시간은 아니다.']
    lines += ['Ready/Unacked 표본은 Management API 갱신 지연이 있어 실행 경계의 이전 표본이 섞일 수 있다.',
              '본문 바이트 상한은 Ready 메시지 본문 기준이며 Unacked·메시지 속성·브로커 메모리 전체를 포함하지 않는다.', '',
              '### 본 측정 자원·관측 상태', '',
              '| 실행 | CPU 평균 코어(회복 포함) | DB 풀 최대 사용(표본) | 관측 최대 공백(초) | 부하 관측 상태 |',
              '| --- | --- | --- | --- | --- |']
    for result, row in zip(results, rows):
        lines += [f'| {row["실행"]} | {number(result.get("phase_cpu_cores_including_drain"))} '
                  f'| {number(result.get("max_db_checked_out"))} | {number(result.get("sample_max_gap_s"))} '
                  f'| {result.get("load_observation", "미수집")} |']
        if result.get('observation_notes'):
            lines += ['관측 상태 설명: ' + ', '.join(result['observation_notes'])]
    lines += ['', 'CPU는 분석 프로세스의 user+system 시간/경과시간이며 k6·RabbitMQ·PostgreSQL의 CPU는 아니다.',
              'intervals.csv는 1분 단위 유입·완료·종료 시 미완료와 해당 분에 발행한 요청의 완료 p95/p99를 저장한다.',
              '도착 분 기준 지연은 회복 중 완료까지 포함하며, 미완료 표본 수를 함께 읽는다.']
    lines += ['', '## 4. 유효성·정합성 판정', '',
              '| 실행 | 시도 / 발행 / 성공 | 발행 실패 | dropped | 최종 실패 / 미완료 | 중복 결과 | DB SETTLED |',
              '| --- | --- | --- | --- | --- | --- | --- |']
    for result, row in zip(results, rows):
        lines += [f'| {row["실행"]} | {result["attempted"]} / {result["published"]} / {result["succeeded"]} '
                  f'| {result.get("publish_failed", result["attempted"] - result["published"])} '
                  f'| {result["dropped_iterations"]} | {result["failed"]} / {result["missing"]} '
                  f'| {result["duplicate_results"]} | {result["settled"]} |']
        if result['reasons']:
            lines += [f'실행 {row["실행"]} 판정 사유: ' + ', '.join(result['reasons'])]
    lines += ['', 'PASS는 정상 요청·결과·AI DB 완료 대응 판정이다. 지속 처리량 인증이 아니다.',
              '관측 공백은 로컬 원장 표본 기준이다. Prometheus 수집 공백·DB 연결 대기·PC 절전 여부를 모두 검증한 것은 아니다.', '',
              '## 5. 관측 사실과 병목 추정', '',
              '표의 처리량·지연·종료 시 미완료는 관측값이다. 원본 큐 시계열·자원 지표를 확인한 뒤 병목을 해석한다.',
              'prefetch 증가만으로 성능이 좋아진다고 가정하지 않으며, 표본·실행 순서·유입 포화 여부를 함께 확인한다.', '',
              '## 6. 기존 참고값과 실제 모델 검증의 구분', '',
              '기존 노션의 실제 AI 참고값은 v3 110건/분·평균 4.1초·p95 6.9초다.',
              '이번 합성 분석의 시간 설정은 그 참고값을 바탕으로 한다. 비슷한 수치가 나와도 실제 모델 성능 재현은 아니다.', '',
              '## 7. 설정 결정·다음 변경', '',
              '설정은 자동 변경하지 않는다. 후보별 3회 반복과 동일 조건을 확인한 뒤 처리량·완료 p95·자원을 비교한다.',
              '처리량 차이가 5% 이내이고 완료 p95가 기준보다 10% 넘게 악화되지 않으면 작은 prefetch를 우선 검토한다.',
              '이번 목적은 동시 워커·prefetch·RabbitMQ 대기 큐 상한 구체화다. 적체 관측 자체를 테스트 실패로 판정하지 않는다.',
              '측정 → 결과 검토 → 필요한 설정만 변경 → 재측정 순서로 진행한다. 유입량 가정으로 큐 상한을 자동 확정하지 않는다.',
              '상한 검토에는 실제 처리량·대기 허용시간·Ready 최고 표본·메시지 크기를 함께 사용한다.',
              'BE 배치는 별도 항목이다. 실제 AI 검증 전에는 운영 용량을 확정하지 않고, 적체 시 장애 정책은 후속 범위로 남긴다.', '',
              '## 8. 미측정 항목·남은 작업', '',
              '- 입력 크기별 분포, 순수 큐·스레드풀 대기, 실제 DB 연결 대기',
              '- 짧은 실행은 워밍업·장시간 검증을 대체하지 않는다. Prometheus 원본·대시보드 이미지 첨부는 별도다.',
              '- 실제 AI·429·폴백·장애 주입·버전 역전·BE 배치 및 전체 경로', '']
    (directory / 'report.md').write_text('\n'.join(lines))
