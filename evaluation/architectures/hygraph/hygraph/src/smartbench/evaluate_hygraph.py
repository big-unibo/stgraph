from uuid import uuid4
import csv
from hygraph_core import HyGraph
from data_loader import loadData
import queries
import os
from time import time
import sys
import yaml
import logging

# --- RESOURCE LOADING ---
def get_temporal_constraints(selectivity, logger):
    """
    Parses the YAML file and returns constraints for the selected selectivity level.
    """
    yaml_path = os.path.join(RESOURCES_DIR,  "time_constraints.yaml")
    try:
        with open(yaml_path, "r") as f:
            data = yaml.safe_load(f)
        return data["temporalConstraints"][selectivity]
    except (FileNotFoundError, KeyError) as e:
        logger.error(f"Critical error loading YAML constraints: {e}")
        sys.exit(1)


def setup_logger(logger_name, log_level=logging.INFO):
    log_format = "[%(asctime)s][%(levelname)s] %(name)s: %(message)s"
    formatter = logging.Formatter(log_format)

    logger = logging.getLogger(logger_name)
    logger.setLevel(log_level)

    # Create stream handler to print logs to standard output
    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setLevel(log_level)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    return logger


# The resources mounted by the compose file (outside the code folder): time windows and datasets
RESOURCES_DIR = os.path.join("/", "home", "hygraph", "resources")

# .env parameters setup
DATASET = os.getenv("DATASET", "smartbench")
DATASET_SIZE = os.getenv("DATASET_SIZE", "small")
INGESTION_ITERATIONS = int(os.getenv("INGESTION_ITERATIONS", "1"))
QUERY_ITERATIONS = int(os.getenv("QUERY_ITERATIONS", "1"))
QUERY_SELECTIVITY = os.getenv("QUERY_SELECTIVITY", "increased")

# The statistics of the other systems (results/dt_graph, mounted on /hygraph/results by the compose file), with their
# columns: as it.unibo.stats.loadDataset (ingestion) and it.unibo.stats.runQuery (queries); MODEL and RESULT_SIZE (the
# size of the evaluation, not the one of this container) are set by SmartBenchEvaluation
statistics_path = os.path.join(os.sep, "hygraph", "results", "dt_graph")
ingestion_stats_path = os.path.join(statistics_path, "ingestion_time", "ingestion_statistics.csv")
query_stats_path = os.path.join(statistics_path, "query_time", "query_statistics.csv")
MODEL = os.getenv("MODEL", "hygraph")
RESULT_SIZE = os.getenv("RESULT_SIZE", DATASET_SIZE)


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

logger = setup_logger("HyGraph_SmartBench_QueryEvaluator")

TIME_CONSTRAINTS = get_temporal_constraints(QUERY_SELECTIVITY, logger)

resources_path = os.path.join(
    RESOURCES_DIR,
    "datasets",
    "smartbench",
    RESULT_SIZE,  # the size of the evaluation (DATASET_SIZE too, set by SmartBenchEvaluation)
)

TEST_UUID = uuid4()


inputFiles = [
    "group.json",
    "user.json",
    "platformType.json",
    "sensorType.json",
    "platform.json",
    "infrastructureType.json",
    "infrastructure.json",
    "sensor.json",
]

query_map = {
    "EnvironmentCoverage": queries.environmentCoverage,
    "EnvironmentAggregate": queries.environmentAggregate,
    "MaintenanceOwners": queries.maintenanceOwners,
    "EnvironmentOutlier": queries.environmentOutlier,
    "AgentOutlier": queries.agentOutlier,
    "AgentHistory": queries.agentHistory,
}


def evaluate_ingestion_performance():
    hygraph = HyGraph()
    start_time = time()
    for file in inputFiles:
        hygraph = loadData(file, hygraph, resources_path)
    end_time = time()

    return hygraph, start_time, end_time, end_time - start_time


# Ingestion phase

hygraph = HyGraph()

print(f"Evaluating HyGraph with dataset size {DATASET_SIZE} - Test ID: {TEST_UUID}")

for ingestion_iteration in range(INGESTION_ITERATIONS):
    print(f"Ingestion iteration {ingestion_iteration+1}/{INGESTION_ITERATIONS}")
    hygraph, start_time, end_time, elapsed_time = evaluate_ingestion_performance()
    # Times in ms; the graph and the time series are loaded together (no separate times), sizes and cardinalities unknown
    append(ingestion_stats_path, {
        "test_id": str(uuid4()),
        "model": MODEL,
        "startTimestamp": int(start_time * 1000),
        "endTimestamp": int(end_time * 1000),
        "dataset": DATASET,
        "datasetSize": RESULT_SIZE,
        "threads": 1,
        "graphElapsedTime": -1,
        "tsElapsedTime": -1,
        "indexTime": 0,
        "elapsedTime": round(elapsed_time * 1000),
        "numMachines": 1,
        "storage": -2,
        "tsStorage": -1,
        "gsStorage": -1,
        "tsCard": -1,
        "gsCard": -1,
    })

# Query phase
for query_iteration in range(QUERY_ITERATIONS):
    print(f"Query iteration {query_iteration+1}/{QUERY_ITERATIONS}")

    for query_name, query in query_map.items():
        # The time windows of the query on this size (the constraints were read before the name of the query existed)
        query_constraints = TIME_CONSTRAINTS.get(query_name, {}).get(RESULT_SIZE, {})
        for idx, time_range in query_constraints.items():
            result, elapsed_time = query(hygraph, (time_range[0], time_range[1]))
            append(query_stats_path, {
                "test_id": str(uuid4()),
                "queryid": query_name,
                "model": MODEL,
                "dataset": DATASET,
                "datasetSize": RESULT_SIZE,
                "threads": 1,
                "elapsedTime": round(elapsed_time * 1000),
                "numMachines": 1,
                "cardinality": len(result),
                "queryMode": "OPTIMIZED",
                "iteration": query_iteration,
                "querySelectivity": QUERY_SELECTIVITY,
                "temporalRangeIndex": idx,
            })


print("Evaluation completed.")
