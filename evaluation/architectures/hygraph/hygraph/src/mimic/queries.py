import csv
from hygraph_core import HyGraph, TimeSeriesMetadata, HyGraphQuery
import pandas as pd
import ijson
import uuid
import json
import os
from datetime import datetime
from time import time


def value_above_threshold(node, threshold=100.0):
    ts = node.get_temporal_property("ts")
    data = ts.data.to_pandas()
    if len(data.columns) > 1:
        raise ValueError(
            f"Expected time series to have a single variable: {data.columns}"
        )
    result = data[data.columns[0]].apply(lambda x: x > threshold).any()
    return result


def q1(hygraph):
    start_time = time()
    query = HyGraphQuery(hygraph)
    result = (
        query.match_node(
            alias="p",
            label="Person",
            node_type="PGNode",
        )
        .match_edge(alias="e", edge_type="PGEdge")
        .match_node(label="TimeSeries", alias="ts", node_type="PGNode")
        .where(value_above_threshold)
        .connect("p", "e", "ts")
        .return_(
            p=lambda node: node["p"].get_static_property("id"),
            cat=lambda node: node["ts"].get_static_property("category"),
            ts=lambda node: node["ts"].get_temporal_property("ts"),
        )
        .execute()
    )
    elapsed_time = time() - start_time
    return result, elapsed_time


def q2(hygraph):
    start_time = time()
    query = HyGraphQuery(hygraph)
    result = (
        query.match_node(
            alias="p",
            label="Person",
            node_type="PGNode",
        )
        .match_edge(alias="e", edge_type="PGEdge")
        .match_node(label="TimeSeries", alias="ts", node_type="PGNode")
        .connect("p", "e", "ts")
        .return_(
            p=lambda node: node["p"].get_static_property("id"),
            cat=lambda node: node["ts"].get_static_property("category"),
            ts=lambda node: node["ts"]
            .get_temporal_property("ts")
            .apply_aggregation("mean"),
        )
        .execute()
    )
    elapsed_time = time() - start_time
    return result, elapsed_time


def q3(hygraph):
    start_time = time()
    query = HyGraphQuery(hygraph)
    result = (
        query.match_node(
            alias="p",
            label="Person",
            node_type="PGNode",
        )
        .match_edge(alias="e", edge_type="PGEdge")
        .match_node(label="TimeSeries", alias="ts", node_type="PGNode")
        .where(value_above_threshold)
        .connect("p", "e", "ts")
        .return_(
            p=lambda node: node["p"].get_static_property("id"),
            cat=lambda node: node["ts"].get_static_property("category"),
            ts=lambda node: node["ts"]
            .get_temporal_property("ts")
            .apply_aggregation("mean"),
        )
        .execute()
    )
    elapsed_time = time() - start_time
    return result, elapsed_time
