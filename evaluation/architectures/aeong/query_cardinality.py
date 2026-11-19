"""The cardinality of the query just run by evaluate_temporal_q.py of the image (run after it, with its args).

The client of the image writes the result of each query with RETURN in a report (report_query<QUERY>.txt, the query
in upper case): its number of rows goes to query_cards/<name of the statistics of the query>.txt, next to
query_stats, read by AeonGResults; the reports are removed, so the next query finds only its own. No report (e.g., a
query too long for a file name): -1.
"""
import re
from pathlib import Path

reports = list(Path('/home/AeonG/tests/results/query_results/output').glob('report_query*.txt'))
stats = Path(args.output)
rows = [int(n) for report in reports for n in re.findall(r'^Records \((\d+) rows\):', report.read_text(errors='replace'), re.M)]
cards = stats.parent.parent / 'query_cards'
cards.mkdir(parents=True, exist_ok=True)
(cards / (stats.stem + '.txt')).write_text(str(rows[-1] if rows else -1))
for report in reports:
    report.unlink()
