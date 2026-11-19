"""Capture AeonG transaction times at the dump's known ingestion boundaries."""
import ast
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path


# A node written by a statement of the dumps: (:label {key: value}), its id first (the last one of the statement is the
# node it writes, e.g., the TimeSeries of MATCH (p:Person {person_id: 1}) CREATE (p)-[...]->(:TimeSeries {ts_id: 1}))
NODE = re.compile(r"\(\w*:(`(?:``|[^`])+`|\w+)\s+\{(sensor|series_id|person_id|ts_id):\s*('(?:\\.|[^'\\])*'|-?\d+)")
# The client of the image writes the result of each query with RETURN in a report file of this folder
REPORTS = Path('/home/AeonG/tests/results/query_results/output')


def transaction_time(client, statement, directory):
    nodes = NODE.findall(statement)
    if not nodes:
        raise ValueError('Boundary statement does not identify a node: ' + statement)
    label, key, value = nodes[-1]
    # AeonG returns a node with its transaction time (transaction_ts, the start of its current version)
    query = 'MATCH (n:%s {%s: $key}) RETURN n' % (label, key)
    REPORTS.mkdir(parents=True, exist_ok=True)
    for old in REPORTS.glob('report_query*.txt'):
        old.unlink()
    lookup = directory / 'lookup.json'
    lookup.write_text(json.dumps([query, {'key': ast.literal_eval(value)}]) + '\n')
    subprocess.run(client._get_args(input=str(lookup), queries_json=True, num_workers=1, port=client._port),
                   stdout=subprocess.PIPE, check=True)
    reports = list(REPORTS.glob('report_query*.txt'))
    if len(reports) != 1:
        raise ValueError('Expected the report of the lookup, got: ' + str(reports))
    times = re.findall(r'\btransaction_ts: (\d+)', reports[0].read_text(encoding='utf-8'))
    if len(times) != 1:
        raise ValueError('Expected one boundary node, got transaction times: ' + str(times))
    return int(times[0])


def ingest(client, dump, num_workers):
    if num_workers != 1:
        raise ValueError('Boundary capture requires sequential ingestion')
    dump = Path(dump)
    bounds = dict(line.split('=') for line in dump.with_suffix('.tt').read_text().splitlines() if line)
    bounds = {key: int(value) for key, value in bounds.items()}
    last = bounds['last']
    stops = set(bounds.values()) - {last + 1}
    recorded = {}
    output = dump.with_suffix('.aeong')
    if output.exists():
        output.unlink()
    elapsed = duration = 0
    started = int(time.time() * 1000000)
    with tempfile.TemporaryDirectory(prefix='aeong-ingest-') as directory:
        directory = Path(directory)
        chunk = directory / 'chunk.cypher'
        with dump.open(encoding='utf-8') as source, chunk.open('w', encoding='utf-8') as target:
            pending = False
            for position, statement in enumerate(source, 1):
                target.write(statement)
                pending = True
                if position not in stops:
                    continue
                target.flush()
                begin = time.monotonic()
                result = client.execute(file_path=str(chunk), num_workers=1)
                elapsed += time.monotonic() - begin
                duration += sum(row['duration'] for row in result)
                recorded[position] = transaction_time(client, statement, directory)
                target.seek(0)
                target.truncate()
                pending = False
            if pending:  # the statements after the last boundary (e.g., the changes of the graph after the last event)
                target.flush()
                begin = time.monotonic()
                result = client.execute(file_path=str(chunk), num_workers=1)
                elapsed += time.monotonic() - begin
                duration += sum(row['duration'] for row in result)
        if position < last:
            raise ValueError('Dump length differs from its boundary manifest')
    # TT FROM a TO b reads the versions that start before b: the end of the whole history (last) and of the windows past
    # the data (bound at statement last + 1) is just after the last statement
    recorded[last + 1] = recorded[last] + 1
    bounds = {key: last + 1 if key == 'last' else pos for key, pos in bounds.items()}
    output.write_text(''.join('%s=%s\n' % (key, recorded[pos]) for key, pos in bounds.items()))
    print('AeonG transaction times recorded during ingestion: ' + str(output), flush=True)
    # Exclude boundary lookups and chunk preparation from ingestion timing.
    return [{'duration': duration}], started, started + int(elapsed * 1000000)


if __name__ == '__main__':
    # Keep the image's startup, indexes, storage measurement and shutdown.
    script = Path('/home/AeonG/tests/scripts/create_temporal_database.py')
    source = script.read_text(encoding='utf-8')
    original = ('    start_time = int(time.time() * 1000000)\n'
                '    graph_op_ret = client.execute(file_path=args.original_dataset_cypher_path, num_workers=args.num_workers)\n'
                '    end_time = int(time.time() * 1000000)')
    replacement = '    graph_op_ret, start_time, end_time = ingest(client, args.original_dataset_cypher_path, args.num_workers)'
    if source.count(original) != 1:
        raise RuntimeError('AeonG ingestion script has changed')
    exec(compile(source.replace(original, replacement), str(script), 'exec'))
