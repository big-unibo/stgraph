import csv
from hygraph_core import HyGraph, TimeSeriesMetadata, HyGraphQuery
import pandas as pd
import ijson
import uuid
import json
import os
from datetime import datetime

start_time = pd.to_datetime("2010-01-01", format="%Y-%m-%d")


def load_edges(csv_file, hygraph, source_col, target_col, label):
    """
    Read a CSV file and add one edge per row.

    Example CSV:
        source,target
        1,2
        1,3
        2,4
    """

    with open(csv_file, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            id += 1
            source_id = row[source_col]
            target_id = row[target_col]

            hygraph.add_pgedge(
                oid=id,
                source=source_id,
                target=target_id,
                label=label,
                start_time=start_time,
            )


def load_nodes(csv_file, hygraph: HyGraph, label_col=":LABEL", id=1, key=[]):
    """
    Read nodes from a CSV file and add them to HyGraph.

    If the filename ends with 'measurement.csv',
    rows are grouped by:
        id, ts_id, category, abbreviation, itemid

    and uploaded as a single time series per group.
    """
    filename = os.path.basename(csv_file)
    # ------------------------------------------------------------------
    # SPECIAL HANDLING FOR *_measurement.csv
    # ------------------------------------------------------------------
    if filename.endswith("measurement.csv") or filename.endswith(
        "measurement.small.csv"
    ):
        df = pd.read_csv(csv_file)
        df.columns = ["id"] + [col.strip() for col in df.columns[1:]]
        grouping_cols = [
            "id",
            "ts_id",
            "category",
            "abbreviation",
            "itemid",
        ]
        grouped = df.groupby(grouping_cols)
        for group_keys, group_df in grouped:
            (
                node_ref_id,
                ts_id,
                category,
                abbreviation,
                itemid,
            ) = group_keys
            id += 1
            # ----------------------------------------------------------
            # Create TimeSeries node
            # ----------------------------------------------------------
            node = hygraph.add_pgnode(
                oid=id,
                label="TimeSeries",
                start_time=start_time,
            )

            # Optional static properties
            node.add_static_property("category", category, hygraph)
            node.add_static_property("abbreviation", abbreviation, hygraph)
            node.add_static_property("itemid", itemid, hygraph)
            node.add_static_property("ts_id", ts_id, hygraph)

            # ----------------------------------------------------------
            # Build time series
            # ----------------------------------------------------------
            group_df = group_df.sort_values("timestamp")

            timestamps = pd.to_datetime(group_df["timestamp"])
            values = [[x] for x in group_df["value"].values]

            metadata = TimeSeriesMetadata(
                owner_id=node.getId(),
                element_type="node",
            )

            timeseries = hygraph.add_time_series(
                timestamps=timestamps,
                variables=[abbreviation],
                data=values,
                metadata=metadata,
            )

            node.add_temporal_property(
                "ts",
                timeseries,
                hygraph,
            )

            # ----------------------------------------------------------
            # Connect source entity -> TimeSeries
            # ----------------------------------------------------------
            hygraph.add_pgedge(
                oid=id,
                source=int(ts_id),
                target=id,
                label="HAS_PARAMETERS",
                start_time=start_time,
            )

        return hygraph, id

    # ------------------------------------------------------------------
    # DEFAULT CSV NODE LOADER
    # ------------------------------------------------------------------
    with open(csv_file, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            id += 1

            node = hygraph.add_pgnode(
                oid=id,
                label=row[label_col],
                start_time=start_time,
            )

            for col, value in row.items():
                if col in [label_col]:
                    continue

                if value is None or value == "":
                    continue

                node.add_static_property(
                    col if ":ID" not in col else "id", value, hygraph
                )

        return hygraph, id


def load_data(size):
    hygraph = HyGraph()
    id = 0
    persons, id = load_nodes(
        f"/home/hygraph/resources/datasets/mimic/{size}/person.csv",
        hygraph=hygraph,
        id=id,
    )
    ts, id = load_nodes(
        f"/home/hygraph/resources/datasets/mimic/{size}/measurement.csv",
        hygraph=hygraph,
        id=id,
        label_col="label",
    )

    return hygraph
