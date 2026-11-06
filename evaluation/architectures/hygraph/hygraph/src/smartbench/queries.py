from hygraph_core import HyGraph, HyGraphQuery
import pandas as pd
from time import time
import utils
from datetime import datetime

FAR_FUTURE_DATE = datetime(2100, 12, 31, 23, 59, 59)
FAR_PAST_DATE = datetime(2010, 1, 1, 0, 0, 0)


def temperature_values(node, t_from, t_to):
    """
    The values of the series of [node] in [t_from, t_to[ (milliseconds, or datetimes), as the time windows of STGraph
    (timestamp >= from and timestamp < to)
    """
    data = node.get_temporal_property("Temperature").data.to_pandas()
    index = data.index
    millis = index.asi8 // 1_000_000 if isinstance(index, pd.DatetimeIndex) else index.astype("int64")
    t_from, t_to = utils.to_ms(t_from), utils.to_ms(t_to)
    return data["Temperature"][(millis >= t_from) & (millis < t_to)].dropna()


def temperature_above_threshold(node, t_from, t_to, threshold=65.0):
    """Whether a value of the series of [node] in [t_from, t_to[ is at least [threshold] (as STGraph: >=)"""
    return bool((temperature_values(node, t_from, t_to) >= threshold).any())


def match_infrastructure_sensor(
    graph: HyGraph,
    infrastructureId: str = None,
    t_from: int = FAR_PAST_DATE,
    t_to: int = FAR_FUTURE_DATE,
):
    query = HyGraphQuery(graph)
    if infrastructureId:
        a = (
            query.match_node(
                alias="Infrastructure",
                label="Infrastructure",
                node_id=infrastructureId,
                node_type="PGNode",
            )
            .match_edge(label="hasCoverage", alias="hasCoverage", edge_type="PGEdge")
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Sensor", alias="Sensor", node_type="PGNode")
            .connect("Infrastructure", "hasCoverage", "Sensor")
            .return_(
                infrastructure_id=lambda node: node[
                    "Infrastructure"
                ].get_static_property("id"),
                sensor_id=lambda node: node["Sensor"].get_static_property("id"),
                t_from=lambda node: node["hasCoverage"].start_time,
                t_to=lambda node: node["hasCoverage"].end_time,
            )
            .execute()
        )
        return a
    else:
        return (
            query.match_node(
                alias="Infrastructure", label="Infrastructure", node_type="PGNode"
            )
            .match_edge(label="hasCoverage", alias="hasCoverage", edge_type="PGEdge")
            .match_node(label="Sensor", alias="Sensor", node_type="PGNode")
            .connect("Infrastructure", "hasCoverage", "Sensor")
            .return_(
                infrastructure_id=lambda node: node[
                    "Infrastructure"
                ].get_static_property("id"),
                sensor_id=lambda node: node["Sensor"].get_static_property("id"),
                t_from=lambda node: node["hasCoverage"].start_time,
                t_to=lambda node: node["hasCoverage"].end_time,
            )
            .execute()
        )


def environmentCoverage(graph: HyGraph, temporal_constraints: tuple[int, int]):
    start_time = time()
    results = match_infrastructure_sensor(graph, infrastructureId="3042")
    final_results = []
    for path in results:
        newQuery = HyGraphQuery(graph)
        sensor_id = path["sensor_id"]
        infrastructure_id = path["infrastructure_id"]
        test = (
            newQuery.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                sensor_id=lambda node: node["Sensor"].get_static_property("id"),
                temperature=lambda node: node["Temperature"].get_static_property("id"),
            )
            .execute()
        )
        if len(test) > 0:
            final_results.append(test)
    elapsed_time = time() - start_time
    final_results = [
        [{"Infrastructure": infrastructure_id}] + result_path
        for result_path in final_results
    ]
    # final_results.apply(
    #     lambda path: path.insert(0, {"Infrastructure": infrastructure_id})
    # )
    return final_results, elapsed_time


def environmentAggregate(graph: HyGraph, temporal_constraints: tuple[int, int]):
    # The time window of the query (milliseconds): the readings in [tA, tB[, as STGraph
    tA, tB = temporal_constraints
    start_time = time()

    results = match_infrastructure_sensor(
        graph, t_from=temporal_constraints[0], t_to=temporal_constraints[1]
    )
    final_results = []
    for path in results:
        query = HyGraphQuery(graph)
        sensor_id = path["sensor_id"]
        t_from = path["t_from"]
        t_to = path["t_to"]
        infrastructure_id = path["infrastructure_id"]
        subquery = (
            query.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                # The readings in the time window of the query, as STGraph
                values=lambda node: temperature_values(node["Temperature"], tA, tB),
            )
            .execute()
        )
        values = pd.concat([sensor_path["values"] for sensor_path in subquery]) if subquery else pd.Series(dtype=float)
        # As STGraph (timeaware): no reading in the window, no result
        if len(values) > 0:
            final_results.append((infrastructure_id, sensor_id, float(values.mean())))
    elapsed_time = time() - start_time
    return final_results, elapsed_time


def maintenanceOwners(graph: HyGraph, temporal_constraints: tuple[int, int]):
    # The time window of the query (milliseconds): the readings in [tA, tB[, as STGraph
    tA, tB = temporal_constraints
    start_time = time()

    sensor_in_infrastructure = match_infrastructure_sensor(
        graph, t_from=temporal_constraints[0], t_to=temporal_constraints[1]
    )
    # As STGraph (group by Device, Environment, Owner): the distinct (sensor, environment, owner)
    final_results = set()
    for path in sensor_in_infrastructure:
        query = HyGraphQuery(graph)
        sensor_id = path["sensor_id"]
        t_from = path["t_from"]
        t_to = path["t_to"]
        infrastructure_id = path["infrastructure_id"]
        subquery = (
            query.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                sensor_id=lambda node: node["Sensor"].get_static_property("id"),
                t_from=lambda node: node["hasTemperature"].start_time,
                t_to=lambda node: node["hasTemperature"].end_time,
                temperature_id=lambda node: node["Temperature"].get_static_property(
                    "id"
                ),
                # The threshold, only on the Temperature nodes of this sensor (after connect), in the time window of the
                # query, as STGraph
                above=lambda node: temperature_above_threshold(node["Temperature"], tA, tB),
            )
            .execute()
        )
        subquery = [sensor_path for sensor_path in subquery if sensor_path["above"]]

        for sensor_path in subquery:
            t_from = sensor_path["t_from"]
            t_to = sensor_path["t_to"]
            temperature = sensor_path["temperature_id"]
            query = HyGraphQuery(graph)
            owner_query = (
                query.match_node(
                    label="Sensor",
                    alias="Sensor",
                    node_id=sensor_id,
                    node_type="PGNode",
                )
                .match_edge(label="hasOwner", alias="hasOwner", edge_type="PGEdge")
                .where(
                    lambda edge: utils.time_overlap(
                        edge.start_time, edge.end_time, t_from, t_to
                    )
                )
                .match_node(label="User", alias="User", node_type="PGNode")
                .connect("Sensor", "hasOwner", "User")
                .return_(
                    sensor_id=lambda node: node["Sensor"].get_static_property("id"),
                    owner_id=lambda node: node["User"].get_static_property("id"),
                )
                .execute()
            )
            for owner in owner_query:
                final_results.add((owner["sensor_id"], infrastructure_id, owner["owner_id"]))
    final_results = list(final_results)
    elapsed_time = time() - start_time
    return final_results, elapsed_time


def avg_temperature_above_threshold(node, t_from, t_to, threshold=25.0):
    ts = node.get_temporal_property("Temperature")
    data = ts.data.to_pandas()
    data = data[
        data.apply(
            lambda row: utils.time_overlap(row.name, row.name, t_from, t_to), axis=1
        )
    ]
    mean_temp = data["Temperature"].agg("mean")
    result = mean_temp > threshold if not pd.isna(mean_temp) else False
    return result


def environmentOutlier(graph: HyGraph, temporal_constraints: tuple[int, int]):
    # The time window of the query (milliseconds): the readings in [tA, tB[, as STGraph
    tA, tB = temporal_constraints
    threshold = 50.5  # as STGraph (minTemp of EnvironmentOutlier): average > 50.5
    start_time = time()
    sensor_in_infrastructure = match_infrastructure_sensor(
        graph, t_from=temporal_constraints[0], t_to=temporal_constraints[1]
    )
    # As STGraph (group by Environment, AVG of the measurements, then average > threshold): the sum and the number of
    # the values in the window of every sensor of each infrastructure, then the average per infrastructure
    sums = {}
    counts = {}
    for path in sensor_in_infrastructure:
        sensor_id = path["sensor_id"]
        t_from = path["t_from"]
        t_to = path["t_to"]
        infrastructure_id = path["infrastructure_id"]
        query = HyGraphQuery(graph)
        subquery = (
            query.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                # The values of the series of this sensor in the time window of the query, as STGraph
                values=lambda node: temperature_values(node["Temperature"], tA, tB),
            )
            .execute()
        )
        for sensor_path in subquery:
            values = sensor_path["values"]
            sums[infrastructure_id] = sums.get(infrastructure_id, 0.0) + float(values.sum())
            counts[infrastructure_id] = counts.get(infrastructure_id, 0) + len(values)
    final_results = [
        (infrastructure_id, sums[infrastructure_id] / count)
        for infrastructure_id, count in counts.items()
        if count > 0 and sums[infrastructure_id] / count > threshold
    ]
    elapsed_time = time() - start_time
    return final_results, elapsed_time


def max_temperature(node, t_from, t_to, threshold=65.0):
    ts = node.get_temporal_property("Temperature")
    data = ts.data.to_pandas()
    data = data[
        data.apply(
            lambda row: utils.time_overlap(row.name, row.name, t_from, t_to), axis=1
        )
    ]
    max_temp = data["Temperature"].agg("max")
    result = max_temp > threshold if not pd.isna(max_temp) else False
    return result


def agentOutlier(graph: HyGraph, temporal_constraints: tuple[int, int]):
    # The time window of the query (milliseconds): the readings in [tA, tB[, as STGraph
    tA, tB = temporal_constraints
    start_time = time()
    sensors_in_infrastructure = match_infrastructure_sensor(
        graph, t_from=temporal_constraints[0], t_to=temporal_constraints[1]
    )
    final_results = []
    for path in sensors_in_infrastructure:
        sensor_id = path["sensor_id"]
        t_from = path["t_from"]
        t_to = path["t_to"]
        infrastructure_id = path["infrastructure_id"]
        query = HyGraphQuery(graph)
        subquery = (
            query.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                # The readings in the time window of the query, as STGraph
                values=lambda node: temperature_values(node["Temperature"], tA, tB),
            )
            .execute()
        )
        values = pd.concat([sensor_path["values"] for sensor_path in subquery]) if subquery else pd.Series(dtype=float)
        # As STGraph (group by Device, Environment; timeaware): the maximum, only with readings in the window
        if len(values) > 0:
            final_results.append((sensor_id, infrastructure_id, float(values.max())))
    elapsed_time = time() - start_time
    return final_results, elapsed_time


def agentHistory(graph: HyGraph, temporal_constraints: tuple[int, int]):
    deviceid = "thermometer3"  # as STGraph (AgentHistory)
    query = HyGraphQuery(graph)
    final_results = []
    start_time = time()
    results = (
        query.match_node(
            alias="Infrastructure",
            label="Infrastructure",
            node_type="PGNode",
        )
        .match_edge(label="hasCoverage", alias="hasCoverage", edge_type="PGEdge")
        .match_node(
            label="Sensor", alias="Sensor", node_id=deviceid, node_type="PGNode"
        )
        .connect("Infrastructure", "hasCoverage", "Sensor")
        .return_(
            infrastructure_id=lambda node: node["Infrastructure"].get_static_property(
                "id"
            ),
            sensor_id=lambda node: node["Sensor"].get_static_property("id"),
            t_from=lambda node: node["hasCoverage"].start_time,
            t_to=lambda node: node["hasCoverage"].end_time,
        )
        .execute()
    )
    for path in results:
        query = HyGraphQuery(graph)
        sensor_id = path["sensor_id"]
        t_from = path["t_from"]
        t_to = path["t_to"]
        infrastructure_id = path["infrastructure_id"]
        subquery = (
            query.match_node(
                label="Sensor", alias="Sensor", node_id=sensor_id, node_type="PGNode"
            )
            .match_edge(
                label="hasTemperature", alias="hasTemperature", edge_type="PGEdge"
            )
            .where(
                lambda edge: utils.time_overlap(
                    edge.start_time, edge.end_time, t_from, t_to
                )
            )
            .match_node(label="Temperature", alias="Temperature", node_type="PGNode")
            .connect("Sensor", "hasTemperature", "Temperature")
            .return_(
                sensor_id=lambda node: node["Sensor"].get_static_property("id"),
            )
            .execute()
        )
        if len(subquery) > 0:
            subquery.append({"Infrastructure": infrastructure_id})
            final_results.append(subquery)
    elapsed_time = time() - start_time
    return final_results, elapsed_time
