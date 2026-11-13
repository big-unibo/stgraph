#!/usr/bin/env python3
"""Merges the result statistics of several machines (see collect_results.sh).

Usage: merge_results.py <machines folder> <output folder>

<machines folder>/<machine>/dt_graph/{ingestion_time/ingestion_statistics.csv,query_time/query_statistics.csv}, the
results/ folder of each machine, are merged into <output folder>/ingestion_statistics.csv and query_statistics.csv: the
columns matched by name (files written before a column was added leave it empty), plus a column machine (the name of
the folder of the machine). A row present twice (same test_id, e.g., the same file copied twice) is kept once.
"""
import csv
import os
import sys

FILES = [
    os.path.join("dt_graph", "ingestion_time", "ingestion_statistics.csv"),
    os.path.join("dt_graph", "query_time", "query_statistics.csv"),
]


def merge(machines_dir: str, output_dir: str) -> None:
    os.makedirs(output_dir, exist_ok=True)
    machines = sorted(d for d in os.listdir(machines_dir) if os.path.isdir(os.path.join(machines_dir, d)))
    for rel in FILES:
        columns, rows, seen = [], [], set()
        for machine in machines:
            path = os.path.join(machines_dir, machine, rel)
            if not os.path.isfile(path):
                continue
            with open(path, newline="") as f:
                for row in csv.DictReader(f):
                    row.pop(None, None)  # values beyond the header, if any
                    key = row.get("test_id")
                    if key and key in seen:
                        continue
                    if key:
                        seen.add(key)
                    row["machine"] = machine
                    columns += [c for c in row if c not in columns]
                    rows.append(row)
        if "machine" in columns:  # the machine last
            columns.remove("machine")
            columns.append("machine")
        out = os.path.join(output_dir, os.path.basename(rel))
        with open(out, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=columns, restval="", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        print(f"{out}: {len(rows)} rows from {len(machines)} machine(s)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: merge_results.py <machines folder> <output folder>")
    merge(sys.argv[1], sys.argv[2])
