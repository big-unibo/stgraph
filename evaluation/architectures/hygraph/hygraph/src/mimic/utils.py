import os
import pandas as pd


def setup_statistics_file(query_stats_path: str, header: list):
    # Se il file non esiste, crealo con header
    if not os.path.exists(query_stats_path):
        # assicura che la directory esista
        os.makedirs(os.path.dirname(query_stats_path), exist_ok=True)

        df = pd.DataFrame(columns=header)
        df.to_csv(query_stats_path, index=False)
