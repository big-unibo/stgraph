"""
The PeMS evaluation of HyGraph: ingestion of the local copy of the data of the other systems (datasets/original/pems/
cache/<size>, mounted by the compose file), then Q1-Q5 (as TestPemsQuery). HyGraph keeps the time of the graph: the
segment of a station is its inSegment edge, valid as the station version (start_time, end_time), and each measure of
a station lane is a time series. The statistics are appended to the ones of the other systems (results/dt_graph), with
their columns, as it.unibo.stats.loadDataset (ingestion) and it.unibo.stats.runQuery (queries).
"""
import csv
import gzip
import json
import os
import uuid
from datetime import datetime
from time import time

import pandas as pd
from hygraph_core import HyGraph, TimeSeriesMetadata

DATASET = "pems"
RESULTS = os.path.join(os.sep, "hygraph", "results", "dt_graph")
INGESTION_STATS = os.path.join(RESULTS, "ingestion_time", "ingestion_statistics.csv")
QUERY_STATS = os.path.join(RESULTS, "query_time", "query_statistics.csv")
DATA = os.environ.get("PEMS_DATA", "/home/hygraph/resources/datasets/pems")

SIZE = os.environ.get("DATASET_SIZE", "xsmall")
MODEL = os.environ.get("MODEL", "hygraph")
CONFIGURATION = os.environ.get("CONFIGURATION", "conf-free")
RESULT_SIZE = os.environ.get("RESULT_SIZE") or SIZE
INGESTION_ITERATIONS = int(os.environ.get("INGESTION_ITERATIONS", "1"))
QUERY_ITERATIONS = int(os.environ.get("QUERY_ITERATIONS", "1"))
QUERIES = [q for q in os.environ.get("QUERIES", "Q1,Q2,Q3,Q4,Q5").split(",") if q]

MEASURES = ["flow", "avg_occupancy", "avg_speed"]  # AbstractPemsLoader.MEASURES
# PemsWindows of TestPemsQuery (epoch ms, UTC): the windows of Q3 and Q4, [from, to), and the instant T of Q5
WINDOWS = {"Q3": (1735689600000, 1735776000000), "Q4": (1735689600000, 1738368000000)}
T = 1735689600000
START = datetime(1900, 1, 1)  # the start of what is always valid (Long.MIN_VALUE in the copy); the end: HyGraph's default


def ms(t):
    return pd.Timestamp(t, unit="ms")


def append(path, row):
    """Appends [row] to the CSV file [path], in the order of its header (written if the file is new)"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    header = None
    if os.path.exists(path):
        with open(path, newline="") as f:
            header = next(csv.reader(f), None)
    missing = [column for column in row if header is not None and column not in header]
    if missing:  # a file written before the last columns were added: the old rows leave them empty
        with open(path, newline="") as f:
            lines = f.read().splitlines()[1:]
        header = header + missing
        with open(path, "w", newline="") as f:
            f.write("\n".join([",".join(header)] + lines) + "\n")
    with open(path, "a", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        if header is None:
            header = list(row.keys())
            writer.writerow(header)
        writer.writerow([row.get(column, "") for column in header])


def jsonl(name):
    with gzip.open(os.path.join(DATA, SIZE, name), "rt") as f:
        return [json.loads(line) for line in f if line.strip()]


def ingest():
    """The stations and segments, the inSegment edges valid as the versions (graph time), then a time series per
    measure of each station lane (time-series time); times in ms"""
    hygraph = HyGraph()
    start = time()
    oid = 0
    segments = {}
    for s in jsonl("segments.jsonl.gz"):
        oid += 1
        node = hygraph.add_pgnode(oid=oid, label="Segment", start_time=START)
        node.add_static_property("segment_id", f"{s['fwy']}_{s['dir']}_{s['mile']}", hygraph)
        segments[(s["fwy"], s["dir"], s["mile"])] = oid
    stations = {}
    for s in jsonl("stations.jsonl.gz"):
        oid += 1
        node = hygraph.add_pgnode(oid=oid, label="Station", start_time=START)
        node.add_static_property("station_id", s["stationId"], hygraph)
        stations[s["stationId"]] = oid
    edges = 0
    for v in jsonl("versions.jsonl.gz"):
        segment = segments.get((v["fwy"], v["dir"], v["mile"]))
        if segment is None or v["stationId"] not in stations:
            continue
        oid += 1
        edges += 1
        valid_from = START if v["from"] == -2**63 else ms(v["from"])
        valid_to = None if v["to"] == 2**63 - 1 else ms(v["to"])
        hygraph.add_pgedge(oid=oid, source=stations[v["stationId"]], target=segment, label="inSegment", start_time=valid_from, end_time=valid_to)
    graph_end = time()
    events = pd.read_csv(os.path.join(DATA, SIZE, "events.csv.gz"), header=None, names=["station", "lane", "measure", "timestamp", "value"])
    for (station, lane, measure), group in events.groupby(["station", "lane", "measure"]):
        oid += 1
        label = f"lane{lane}_{MEASURES[measure]}"
        node = hygraph.add_pgnode(oid=oid, label="TimeSeries", start_time=START)
        node.add_static_property("lane", int(lane), hygraph)
        node.add_static_property("measure", label, hygraph)
        group = group.sort_values("timestamp")
        series = hygraph.add_time_series(timestamps=pd.to_datetime(group["timestamp"], unit="ms"), variables=[label],
                                         data=[[x] for x in group["value"].values], metadata=TimeSeriesMetadata(owner_id=oid, element_type="node"))
        node.add_temporal_property("ts", series, hygraph)
        hygraph.add_pgedge(oid=oid, source=stations[station], target=oid, label="hasTimeSeries", start_time=START)
    end = time()
    append(INGESTION_STATS, {
        "test_id": str(uuid.uuid4()), "model": MODEL, "configuration": CONFIGURATION,
        "startTimestamp": int(start * 1000), "endTimestamp": int(end * 1000), "dataset": DATASET, "datasetSize": RESULT_SIZE,
        "threads": 1, "graphElapsedTime": round((graph_end - start) * 1000), "tsElapsedTime": round((end - graph_end) * 1000),
        "indexTime": 0, "elapsedTime": round((end - start) * 1000), "numMachines": 1, "storage": -2, "tsStorage": -1, "gsStorage": -1,
        "tsCard": len(events), "gsCard": len(segments) + len(stations) + edges,
    })
    return hygraph


def speeds(hygraph):
    """For each station: its id, its inSegment edges (segment_id, start, end) and its speeds (lane0_avg_speed, by time)"""
    for sid, node in list(hygraph.graph.nodes(data=True)):
        if node.get("label") != "Station":
            continue
        station = node["data"]
        edges, values = [], None
        for _, target, data in hygraph.graph.out_edges(sid, data=True):
            if data["label"] == "inSegment":
                segment = hygraph.graph.nodes[target]["data"].get_static_property("segment_id")
                edges.append((segment, pd.Timestamp(data["start_time"]), pd.Timestamp(data["end_time"])))
            elif data["label"] == "hasTimeSeries":
                node = hygraph.graph.nodes[target]["data"]
                if node.get_static_property("measure") == "lane0_avg_speed":
                    frame = node.get_temporal_property("ts").data.to_pandas()
                    values = frame[frame.columns[0]]
                    values.index = pd.to_datetime(values.index)
        if values is not None:
            yield station.get_static_property("station_id"), edges, values


def average(hygraph, by_segment, window=None):
    """avg(speed) by station or by segment, each speed with the segment valid at its time, within [window] if any"""
    sums, counts = {}, {}
    for station, edges, values in speeds(hygraph):
        if window is not None:
            values = values[(values.index >= ms(window[0])) & (values.index < ms(window[1]))]
        for segment, start, end in edges:
            v = values[(values.index >= start) & (values.index < end)]
            if len(v):
                key = segment if by_segment else station
                sums[key] = sums.get(key, 0) + v.sum()
                counts[key] = counts.get(key, 0) + len(v)
    return [(key, sums[key] / counts[key]) for key in sums]


def active(hygraph):
    """avg(speed) of all time by station, of the stations in a segment at T"""
    t = ms(T)
    return [(station, values.mean()) for station, edges, values in speeds(hygraph)
            if len(values) and any(start <= t < end for _, start, end in edges)]


query_map = {
    "Q1": lambda h: average(h, by_segment=False),
    "Q2": lambda h: average(h, by_segment=True),
    "Q3": lambda h: average(h, by_segment=True, window=WINDOWS["Q3"]),
    "Q4": lambda h: average(h, by_segment=True, window=WINDOWS["Q4"]),
    "Q5": active,
}

hygraph = None
for iteration in range(INGESTION_ITERATIONS):
    print(f"Ingestion iteration {iteration + 1}/{INGESTION_ITERATIONS}")
    hygraph = ingest()
for iteration in range(QUERY_ITERATIONS):
    for query_id in QUERIES:
        start = time()
        result = query_map[query_id](hygraph)
        elapsed = time() - start
        append(QUERY_STATS, {
            "test_id": str(uuid.uuid4()), "queryid": query_id, "model": MODEL, "configuration": CONFIGURATION,
            "dataset": DATASET, "datasetSize": RESULT_SIZE, "threads": 1, "elapsedTime": round(elapsed * 1000),
            "numMachines": 1, "cardinality": len(result), "queryMode": "OPTIMIZED", "iteration": iteration,
            "querySelectivity": "increased", "temporalRangeIndex": 0,
        })
        print(f"{query_id}, iteration {iteration + 1}: {round(elapsed * 1000)} ms, {len(result)} results")
