from uuid import uuid4
from hygraph_core import HyGraph
from data_loader import load_data
import queries
import os
import utils
import pandas as pd
from time import time
from pathlib import Path
import sys
import yaml
import logging

DATASET = "mimic"
statistics_path = os.path.join(os.sep, "home", "hygraph", "results", "hygraph")
INGESTION_CSV_HEADER = "test_id,model,startTimestamp,endTimestamp,dataset,datasetSize,threads,graphElapsedTime,tsElapsedTime,elapsedTime,numMachines,storage"
ingestion_stats_path = os.path.join(
    statistics_path, "ingestion_time", "ingestion_statistics.csv"
)
utils.setup_statistics_file(ingestion_stats_path, INGESTION_CSV_HEADER.split(","))

QUERY_CSV_HEADER = "test_id,model,dataset,datasetSize,threads,queryName,queryType,elapsedTime,numEntities,numMachines"
query_stats_path = os.path.join(statistics_path, "query_evaluation", "statistics.csv")
utils.setup_statistics_file(query_stats_path, QUERY_CSV_HEADER.split(","))
ingestion_stats = pd.DataFrame(columns=INGESTION_CSV_HEADER.split(","))
query_stats = pd.DataFrame(columns=QUERY_CSV_HEADER.split(","))
TEST_UUID = uuid4()


query_map = {
    "Q1": queries.q1,
    "Q2": queries.q2,
    "Q3": queries.q3,
}


def evaluate_ingestion_performance(sizes=["1692200"]):
    hygraph = HyGraph()
    start_time = time()
    for size in sizes:
        hygraph = load_data(size)
    end_time = time()

    return hygraph, start_time, end_time, end_time - start_time


for iteration in range(1):
    for size in ["1692200"]:
        print(f"Ingestion iteration {iteration+1}")
        hygraph, start_time, end_time, elapsed_time = evaluate_ingestion_performance(
            [size]
        )
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
                            "datasetSize": size,
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

        results = {}

        for query_name, query in query_map.items():
            result, elapsed_time = query(hygraph)
            query_stats = pd.concat(
                [
                    query_stats,
                    pd.DataFrame(
                        [
                            {
                                "test_id": TEST_UUID,
                                "model": "HyGraph",
                                "dataset": DATASET,
                                "datasetSize": size,
                                "threads": 1,
                                "queryName": query_name,
                                "queryType": "edgesDirection",
                                "elapsedTime": round(elapsed_time * 1000, 0),
                                "numEntities": len(result),
                                "numMachines": 1,
                                "querySelectivity": "",
                                "temporalRangeIndex": "",
                                "iteration": iteration,
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
