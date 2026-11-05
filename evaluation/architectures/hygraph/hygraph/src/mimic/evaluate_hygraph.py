"""
The MIMIC-IV evaluation of HyGraph: ingestion of the CSV files of the import of Neo4j, then Q1-Q3. The statistics are
appended to the ones of the other systems (results/dt_graph, mounted on /hygraph/results by the compose file), with
their columns: as it.unibo.stats.loadDataset (ingestion) and it.unibo.stats.runQuery (queries).
"""
import csv
import os
import uuid
from time import time

from hygraph_core import HyGraph
from data_loader import load_nodes
import queries

DATASET = "mimic"
RESULTS = os.path.join(os.sep, "hygraph", "results", "dt_graph")
INGESTION_STATS = os.path.join(RESULTS, "ingestion_time", "ingestion_statistics.csv")
QUERY_STATS = os.path.join(RESULTS, "query_time", "query_statistics.csv")
DATA = "/home/hygraph/resources/datasets/mimic"

# Set by the evaluation (MimicEvaluation): the sizes are the folders of the CSV files of Neo4j (the limits); the model
# (with the label of the run) and the size as recorded by the other systems
SIZES = os.environ.get("DATASET_SIZE", "1692200").split(",")
MODEL = os.environ.get("MODEL", "hygraph")
RESULT_SIZE = os.environ.get("RESULT_SIZE")
INGESTION_ITERATIONS = int(os.environ.get("INGESTION_ITERATIONS", "1"))
QUERY_ITERATIONS = int(os.environ.get("QUERY_ITERATIONS", "1"))

query_map = {
    "Q1HyGraph": queries.q1,
    "Q2HyGraph": queries.q2,
    "Q3HyGraph": queries.q3,
}


def millis():
    return int(time() * 1000)


def append(path, row):
    """Appends [row] to the CSV file [path], in the order of its header (written if the file is new)"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    header = None
    if os.path.exists(path):
        with open(path, newline="") as f:
            header = next(csv.reader(f), None)
    with open(path, "a", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")  # as the files written by the other systems
        if header is None:
            header = list(row.keys())
            writer.writerow(header)
        writer.writerow([row.get(column, "") for column in header])


def rows(path):
    with open(path) as f:
        return sum(1 for _ in f) - 1


def ingest(size):
    """Loads the persons (graph time) and the measurements as time series (time-series time): times in ms"""
    hygraph = HyGraph()
    start = millis()
    hygraph, id = load_nodes(f"{DATA}/{size}/person.csv", hygraph=hygraph, id=0)
    graph_end = millis()
    hygraph, _ = load_nodes(f"{DATA}/{size}/measurement.csv", hygraph=hygraph, id=id, label_col="label")
    end = millis()
    persons = rows(f"{DATA}/{size}/person.csv")
    series = len(hygraph.graph.nodes) - persons
    append(INGESTION_STATS, {
        "test_id": str(uuid.uuid4()),
        "model": MODEL,
        "startTimestamp": start,
        "endTimestamp": end,
        "dataset": DATASET,
        "datasetSize": RESULT_SIZE or size,
        "threads": 1,
        "graphElapsedTime": graph_end - start,
        "tsElapsedTime": end - graph_end,
        "indexTime": 0,
        "elapsedTime": end - start,
        "numMachines": 1,
        "storage": -2,
        "tsStorage": -1,
        "gsStorage": -1,
        # As the other loaders: the events, and the persons plus their time series (one per person and item)
        "tsCard": rows(f"{DATA}/{size}/measurement.csv"),
        "gsCard": persons + series,
    })
    return hygraph


for size in SIZES:
    hygraph = None
    for iteration in range(INGESTION_ITERATIONS):
        print(f"Ingestion iteration {iteration + 1}")
        hygraph = ingest(size)
    for iteration in range(QUERY_ITERATIONS):
        for query_id, query in query_map.items():
            result, elapsed = query(hygraph)
            append(QUERY_STATS, {
                "test_id": str(uuid.uuid4()),
                "queryid": query_id,
                "model": MODEL,
                "dataset": DATASET,
                "datasetSize": RESULT_SIZE or size,
                "threads": 1,
                "elapsedTime": round(elapsed * 1000),
                "numMachines": 1,
                "cardinality": len(result),
                "queryMode": "OPTIMIZED",
                "iteration": iteration,
                "querySelectivity": "increased",
                "temporalRangeIndex": 0,
            })
            print(f"{query_id}, iteration {iteration + 1}: {round(elapsed * 1000)} ms, {len(result)} results")
