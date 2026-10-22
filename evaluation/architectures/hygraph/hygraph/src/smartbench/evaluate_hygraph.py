from uuid import uuid4
from hygraph_core import HyGraph
from data_loader import loadData
import queries
import os
import utils
import pandas as pd
from time import time
from pathlib import Path
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


RESOURCES_DIR = os.path.join("/", "hygraph",  "resources")

# .env parameters setup
DATASET = os.getenv("DATASET", "smartbench")
DATASET_SIZE = os.getenv("DATASET_SIZE", "small")
INGESTION_ITERATIONS = int(os.getenv("INGESTION_ITERATIONS", "1"))
QUERY_ITERATIONS = int(os.getenv("QUERY_ITERATIONS", "1"))
QUERY_SELECTIVITY = os.getenv("QUERY_SELECTIVITY", "increased")

statistics_path = os.path.join(os.sep, "hygraph", "results", "hygraph")

# Statistics setup
INGESTION_CSV_HEADER = "test_id,model,startTimestamp,endTimestamp,dataset,datasetSize,threads,graphElapsedTime,tsElapsedTime,elapsedTime,numMachines,storage"
ingestion_stats_path = os.path.join(
    statistics_path, "ingestion_time", "ingestion_statistics.csv"
)
utils.setup_statistics_file(ingestion_stats_path, INGESTION_CSV_HEADER.split(","))

QUERY_CSV_HEADER = "test_id,model,dataset,datasetSize,threads,queryName,queryType,elapsedTime,numEntities,numMachines"
query_stats_path = os.path.join(statistics_path, "query_evaluation", "statistics.csv")
utils.setup_statistics_file(query_stats_path, QUERY_CSV_HEADER.split(","))
logger = setup_logger("HyGraph_SmartBench_QueryEvaluator")

TIME_CONSTRAINTS = get_temporal_constraints(QUERY_SELECTIVITY, logger)

resources_path = os.path.join(
    f"{os.sep}hygraph",
    "resources",
    "datasets",
    "smartbench",
    DATASET_SIZE,
)

ingestion_stats = pd.DataFrame(columns=INGESTION_CSV_HEADER.split(","))
query_stats = pd.DataFrame(columns=QUERY_CSV_HEADER.split(","))
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
    ingestion_stats = pd.concat(
        [
            ingestion_stats,
            pd.DataFrame(
                [
                    {
                        "test_id": TEST_UUID,
                        "model": "HyGraph",
                        "startTimestamp": start_time,
                        "endTimestamp": end_time,
                        "dataset": DATASET,
                        "datasetSize": DATASET_SIZE,
                        "threads": 1,
                        "graphElapsedTime": -1,
                        "tsElapsedTime": -1,
                        "elapsedTime": elapsed_time,
                        "numMachines": 1,
                        "storage": -1,
                    }
                ]
            ),
        ],
        ignore_index=True,
    )
ingestion_stats.to_csv(
    ingestion_stats_path,
    mode="a",
    header=not os.path.exists(ingestion_stats_path),
    index=False,
)

# Query phase
for query_iteration in range(QUERY_ITERATIONS):
    print(f"Query iteration {query_iteration+1}/{QUERY_ITERATIONS}")

    query_constraints = TIME_CONSTRAINTS.get(query_name, {}).get(
        DATASET_SIZE, []
    )
    results = {}

    for query_name, query in query_map.items():
        for idx, time_range in query_constraints.items():
            result, elapsed_time = query(hygraph, (time_range[0], time_range[1]))
            query_stats = pd.concat(
                [
                    query_stats,
                    pd.DataFrame(
                        [
                            {
                                "test_id": TEST_UUID,
                                "model": "HyGraph",
                                "dataset": DATASET,
                                "datasetSize": DATASET_SIZE,
                                "threads": 1,
                                "queryName": query_name,
                                "queryType": "edgesDirection",
                                "elapsedTime": round(elapsed_time * 1000, 0),
                                "numEntities": len(result),
                                "numMachines": 1,
                                "querySelectivity": QUERY_SELECTIVITY,
                                "temporalRangeIndex": idx,
                                "iteration": query_iteration,
                            }
                        ]
                    ),
                ],
                ignore_index=True,
            )
query_stats.to_csv(
    query_stats_path,
    mode="a",
    header=not os.path.exists(ingestion_stats_path),
    index=False,
)


print("Evaluation completed.")
