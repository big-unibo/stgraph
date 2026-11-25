from hygraph_core import HyGraphQuery
from time import time


MIMIC_FILTER = 100  # mimicFilter of TestMimicQuery
C_FILTER = 25000


def person_filter(node):
    """p.c > 25000, as Q1-Q3 of the other systems (c is read from the CSV of Neo4j as a string)"""
    return float(node.get_static_property("c")) > C_FILTER


def series_of_persons(hygraph):
    """(abbreviation, values) of every time series of the persons with c > 25000"""
    query = HyGraphQuery(hygraph)
    return (
        query.match_node(alias="p", label="Person", node_type="PGNode")
        .where(person_filter)
        .match_edge(alias="e", edge_type="PGEdge")
        .match_node(label="TimeSeries", alias="ts", node_type="PGNode")
        .connect("p", "e", "ts")
        .return_(
            p=lambda node: node["p"].get_static_property("id"),
            abbreviation=lambda node: node["ts"].get_static_property("abbreviation"),
            values=lambda node: values(node["ts"]),
        )
        .execute()
    )


def values(node):
    data = node.get_temporal_property("ts").data.to_pandas()
    return data[data.columns[0]]


def average_by_label(rows, threshold=None):
    """avg(value) per label (abbreviation) of the measurements of [rows], those above [threshold] if any"""
    sums, counts = {}, {}
    for row in rows:
        v = row["values"] if threshold is None else row["values"][row["values"] > threshold]
        if len(v) == 0:
            continue
        sums[row["abbreviation"]] = sums.get(row["abbreviation"], 0) + v.sum()
        counts[row["abbreviation"]] = counts.get(row["abbreviation"], 0) + len(v)
    return [(label, sums[label] / counts[label]) for label in sums]


def q1(hygraph):
    """The measurements above 100 of the persons with c > 25000: a result per measurement (p, t, m)"""
    start_time = time()
    result = [
        (row["p"], row["abbreviation"], t, v)
        for row in series_of_persons(hygraph)
        for t, v in row["values"][row["values"] > MIMIC_FILTER].items()
    ]
    elapsed_time = time() - start_time
    return result, elapsed_time


def q2(hygraph):
    """The average of the measurements per label of the persons with c > 25000"""
    start_time = time()
    result = average_by_label(series_of_persons(hygraph))
    elapsed_time = time() - start_time
    return result, elapsed_time


def q3(hygraph):
    """The average of the measurements above 100 per label of the persons with c > 25000"""
    start_time = time()
    result = average_by_label(series_of_persons(hygraph), MIMIC_FILTER)
    elapsed_time = time() - start_time
    return result, elapsed_time
