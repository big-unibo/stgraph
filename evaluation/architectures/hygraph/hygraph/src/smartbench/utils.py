import os
import pandas as pd


def time_overlap(
    from_timestamp: int,
    to_timestamp: int,
    from_: int,
    to: int,
) -> bool:
    empty1 = from_timestamp == to_timestamp
    empty2 = from_ == to

    if not empty1 and not empty2:
        # Case 1: Both intervals are not empty
        return max(from_timestamp, from_) < min(to_timestamp, to)
    elif empty1 and empty2:
        # Case 2: Both intervals are empty; they overlap if they represent the same point
        return from_timestamp == from_
    elif empty1:
        # Case 3: Only the first interval is empty
        return from_ < to and from_timestamp >= from_ and from_timestamp < to
    else:
        # Case 4: Only the second interval is empty
        return (
            from_timestamp < to_timestamp
            and from_ >= from_timestamp
            and from_ < to_timestamp
        )


def get_interval(
    temporal_constraints: dict[str, dict[str, tuple[int, int]]],
    category: str,
    size: str,
) -> tuple[int, int] | None:
    """Ritorna la tupla (from, to) per la categoria/size richiesta."""
    return temporal_constraints.get(category, {}).get(size)


def setup_statistics_file(query_stats_path: str, header: list):
    # Se il file non esiste, crealo con header
    if not os.path.exists(query_stats_path):
        # assicura che la directory esista
        os.makedirs(os.path.dirname(query_stats_path), exist_ok=True)

        df = pd.DataFrame(columns=header)
        df.to_csv(query_stats_path, index=False)
