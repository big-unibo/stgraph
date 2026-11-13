#!/usr/bin/env bash
# Downloads a dataset into datasets/original/<dataset> with the downloader of the evaluation
# (src/test/kotlin/it/unibo/tests/evaluation/Download.kt), the only one: same URLs, archives extracted (the JSON files of
# SmartBench gzipped) and removed. Needs Java, as ./gradlew
set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <dataset> <dataset_size_or_filename>"
    echo "Example:"
    echo "  $0 smartbench small"
    echo "  $0 smartbench medium.tar.gz"
    echo "  $0 mimic x1692200.dump"
    exit 1
fi

cd "$(dirname "$0")"
./gradlew --quiet downloadDataset --args="$1 $2"
echo "Done."
