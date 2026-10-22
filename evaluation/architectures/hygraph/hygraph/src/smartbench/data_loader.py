from hygraph_core import HyGraph, TimeSeriesMetadata
import pandas as pd
import ijson
import uuid
import json
import os
from datetime import datetime

FAR_FUTURE_DATE = datetime(2100, 12, 31, 23, 59, 59)
FAR_PAST_DATE = datetime(2010, 1, 1, 0, 0, 0)

oid = 0


def getOOID():
    """Generate a unique object identifier."""
    global oid
    oid += 1
    return oid


def is_uuid(s: str) -> bool:
    try:
        uuid.UUID(s.replace("_", "-"))
        return True
    except ValueError:
        return False


def parseEdgesAndProperties(document, node, hygraph: HyGraph):
    try:
        for col in document.keys():
            # If property is an edge
            if (
                isinstance(document[col], list)
                and len(document[col]) > 0
                and isinstance(document[col][0], dict)
                and "id" in document[col][0]
            ):
                # TODO: Why not edges ???
                doc = document[col][0]
                target_id = doc["id"]
                hygraph.add_pgedge(
                    oid=getOOID(),
                    source=node.getId(),  # Assuming the source node ID is in the document
                    target=target_id,
                    label=f"has{col.capitalize()}",
                    start_time=pd.to_datetime("2010-01-01"),
                )
            elif isinstance(document[col], dict) and "id" in document[col]:
                try:
                    # Single edge case
                    doc = document[col]
                    target_id = doc["id"]
                    hygraph.add_pgedge(
                        oid=getOOID(),
                        source=document[
                            "id"
                        ],  # Assuming the source node ID is in the document
                        target=target_id,
                        label=f"has{col.capitalize()}",
                        start_time=pd.to_datetime("2010-01-01"),
                    )
                except Exception as e:
                    print(f"Error adding edge for document {document}: {e}")
            else:
                # It's a static property
                node.add_static_property(col, document[col], hygraph)
    except Exception as e:
        print(f"Error parsing document {document}: {e}")
    return hygraph


def load_json(file):
    documents = []
    for line in file:
        documents.append(json.loads(line))
    return documents


def loadObservations(file_path, sensor_id, hygraph: HyGraph, default_path: str):
    with open(file_path, "r", encoding="utf-8") as file:
        documents = load_json(file)
        hygraph, node = loadNode(
            documents[0], default_path, sensor_id, hygraph
        )  # Insert the TS as a node
        metadata = TimeSeriesMetadata(owner_id=node.getId(), element_type="node")
        timestamps = pd.to_datetime([doc["timestamp"] for doc in documents])
        values = [[doc["payload"]["temperature"]] for doc in documents]
        timeseries = hygraph.add_time_series(
            timestamps=timestamps,
            variables=["Temperature"],
            data=values,
            metadata=metadata,
        )
        node.add_temporal_property("Temperature", timeseries, hygraph)
        hygraph.add_pgedge(
            oid=getOOID(),
            source=sensor_id,
            target=node.getId(),
            label="hasTemperature",
            start_time=pd.to_datetime("2010-01-01"),
        )
    return hygraph


def loadNode(
    document,
    default_path: str,
    filename: str,
    hygraph: HyGraph,
):
    # Add node
    node = hygraph.add_pgnode(
        oid=document["id"],
        label=document["type"],
        start_time=pd.to_datetime("2010-01-01"),
    )

    # Add properties and edges
    hygraph = parseEdgesAndProperties(document, node, hygraph)
    tsFilePath = os.path.join(default_path, "timeseries", f"{document['id']}.json")
    # If it has a TimeSeries
    if filename == "sensor.json" and os.path.exists(tsFilePath):
        hygraph = loadObservations(
            tsFilePath,
            document["id"],
            hygraph,
            default_path,
        )

    return hygraph, node


def loadData(filename: str, hygraph: HyGraph, default_path):
    """Load data from a CSV file into the HyGraph instance."""
    with open(os.path.join(default_path, filename), "r", encoding="utf-8") as f:
        for document in ijson.items(f, "item"):
            hygraph, _ = loadNode(document, default_path, filename, hygraph)
    return hygraph
