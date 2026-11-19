"""Runs the queries of AeonG on the loaded database, in place of the query scripts of the image.

The scripts of the image (query_dataset.sh, query_mimic.sh) start AeonG for each query and stop it right after, so
every query ran on a server that had just reloaded the database. Here AeonG starts once, on the same database and with
the same settings (as ../scripts/evaluate_temporal_q.py), runs QUERY_ITERATIONS times every query of QUERIES (the files
/vol/queries/<query>.txt, written by the evaluation) with the client of the image, and stops at the end, as the other
systems. For each run: the statistics of the client (the duration, timed by the client), in
../results/aeong/query_results/<dataset>/query_stats/<query>_sz<size>_wrk1_it<iteration>_sel<selectivity>_tr0.json, and
its number of rows (the report the client writes for each query with RETURN, report_query<QUERY>.txt, the query in
upper case; -1 without it, e.g., a query too long to name its file) in query_cards/<the same name>.txt; both read by
AeonGResults.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.append('../mgbench')
import runners  # noqa: E402

DATABASE = '../results/database/aeong'
REPORTS = Path('/home/AeonG/tests/results/query_results/output')
dataset, size = os.environ['DATASET'], os.environ['DATASET_SIZE']
iterations = int(os.environ['QUERY_ITERATIONS'])
selectivity = os.environ['QUERY_SELECTIVITY']
queries = [q for q in os.environ['QUERIES'].split(',') if q]
stats = Path('../results/aeong/query_results') / dataset / 'query_stats'
cards = stats.parent / 'query_cards'


def clear_reports():
    for report in REPORTS.glob('report_query*.txt'):
        report.unlink()


if iterations > 0 and queries:
    stats.mkdir(parents=True, exist_ok=True)
    cards.mkdir(parents=True, exist_ok=True)
    aeong = runners.Memgraph('../../build/memgraph', DATABASE, True, memgraph_port=7687, snapshot_interval_sec=30,
                             memory_limit=0, anchor_num=10, real_time_flag=False)
    aeong.start_benchmark()
    client = runners.Client('../../build/tests/mgbench/client', DATABASE, memgraph_port=7687)
    try:
        for iteration in range(1, iterations + 1):
            for query in queries:
                print('AeonG %s (iteration %d/%d)' % (query, iteration, iterations), flush=True)
                name = '%s_sz%s_wrk1_it%d_sel%s_tr0' % (query, size, iteration, selectivity)
                clear_reports()
                try:
                    client.execute(file_path='/vol/queries/%s.txt' % query, num_workers=1, output=str(stats / (name + '.json')))
                except subprocess.CalledProcessError as e:
                    print('AeonG %s failed: %s' % (query, e), flush=True)
                    continue
                rows = [int(n) for report in REPORTS.glob('report_query*.txt')
                        for n in re.findall(r'^Records \((\d+) rows\):', report.read_text(errors='replace'), re.M)]
                (cards / (name + '.txt')).write_text(str(rows[-1] if rows else -1))
        clear_reports()
    finally:
        aeong.stop()
