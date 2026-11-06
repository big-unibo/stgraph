import csv
import gzip
from hygraph_core import HyGraph, TimeSeriesMetadata
import pandas as pd
import os

start_time = pd.to_datetime("2010-01-01", format="%Y-%m-%d")


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
    if filename.endswith(("measurement.csv", "measurement.csv.gz")) or filename.endswith(
        "measurement.small.csv"
    ):
        df = pd.read_csv(csv_file)
        # The names without the types of the import of Neo4j (e.g., value:long)
        df.columns = ["id"] + [col.strip().split(":")[0] for col in df.columns[1:]]
        # A time series per person (ts_id) and item
        grouping_cols = [
            "ts_id",
            "category",
            "abbreviation",
            "itemid",
        ]
        grouped = df.groupby(grouping_cols)
        for group_keys, group_df in grouped:
            (
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
    with (gzip.open if csv_file.endswith(".gz") else open)(csv_file, "rt", newline="", encoding="utf-8") as f:
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

                # The names without the types of the import of Neo4j (e.g., c:long)
                node.add_static_property(
                    col.split(":")[0] if ":ID" not in col else "id", value, hygraph
                )

        return hygraph, id
