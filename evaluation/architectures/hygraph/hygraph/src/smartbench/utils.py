

import numbers
from datetime import datetime, timedelta, timezone

import pandas as pd


def to_ms(t):
    """[t] in milliseconds since the epoch: a number as it is, a datetime (naive: UTC, as STGraph) converted"""
    if t is None or isinstance(t, numbers.Real):
        return t
    if isinstance(t, pd.Timestamp):
        return t.value // 1_000_000
    if isinstance(t, datetime):  # also beyond the range of pandas (e.g., datetime.max)
        if t.tzinfo is not None:
            t = t.astimezone(timezone.utc).replace(tzinfo=None)
        return (t - datetime(1970, 1, 1)) // timedelta(milliseconds=1)
    return pd.Timestamp(t).value // 1_000_000


def time_overlap(
    from_timestamp: int,
    to_timestamp: int,
    from_: int,
    to: int,
) -> bool:
    # The edges hold datetimes, the time windows milliseconds: both in milliseconds
    from_timestamp, to_timestamp, from_, to = (to_ms(t) for t in (from_timestamp, to_timestamp, from_, to))
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
