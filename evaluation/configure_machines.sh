#!/usr/bin/env bash
# Configures the evaluation of each machine from here: writes its src/main/resources/test_config.yml from the local one
# (datasets, sizes, volumes, defaults, ... as they are here), with only the systems, resources and label of that
# machine, and copies it there with scp; with -r, also starts ./gradlew evaluate there (Gradle daemon stopped first,
# output in evaluation.log on the machine). Then collect the results with collect_results.sh.
#
# Usage (from anywhere): evaluation/configure_machines.sh [-u <user>] [-c <config>] [-n] [-r] <machines file>
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   -c <config>: the local configuration to start from (default: src/main/resources/test_config.yml)
#   -n: dry run, only prints the configuration of each machine (what differs from the local one)
#   -r: starts the evaluation on each machine after copying its configuration
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph); PYTHON: the python (default: python)
# Machines file: one machine per line, "<host> <system>[,<system>...] <limited|free> [label]" (# for comments):
#   limited: { cpus: 1, conf: <system>/conf }; free: nothing (an empty entry, e.g., "aeong:"), the defaults of the
#   compose file: no CPU or memory limit, conf-free/; label (default: 1CPU or free) is appended to the model of the
#   results of every system (e.g., aeong_1CPU, graphix_free).
#   e.g.: 192.168.30.101 aeong   limited
#         192.168.30.104 graphix free
# Login with the ssh key (push-ssh-key.sh): no password.
set -uo pipefail

USAGE="Usage: $0 [-u <user>] [-c <config>] [-n] [-r] <machines file>"
USER_NAME=""
DRY=false
RUN=false
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$REPO/src/main/resources/test_config.yml"
while getopts "u:c:nrh" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        c) CONFIG="$OPTARG" ;;
        n) DRY=true ;;
        r) RUN=true ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -eq 1 && -f "$1" ]] || { echo "$USAGE"; exit 1; }
[[ -f "$CONFIG" ]] || { echo "No configuration $CONFIG"; exit 1; }
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
PYTHON="${PYTHON:-python}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# Writes the configuration of a machine: <config> <out> <systems> <limited|free> <label>
machine_config() {
    "$PYTHON" - "$@" <<'PY'
import re, sys
src, out, systems, profile, label = sys.argv[1:6]
systems = systems.split(",")
# The key of each system in resources, and the folder of its configurations
key = {"stgraph": "asterixdb"}
folder = {"stgraph": "stgraph", "graphix": "graphix", "neo4j": "neo4j", "pgage": "pgage", "aeong": "aeong", "hygraph": "hygraph"}
def resources(system):
    # free: an empty entry, the defaults of the compose file (no limit, conf-free/)
    return f" {{ cpus: 1, conf: evaluation/architectures/{folder[system]}/conf }}" if profile == "limited" else ""
wanted = {key.get(s, s): resources(s) for s in systems}
section, done = None, set()
lines = []
for line in open(src, encoding="utf-8").read().splitlines():
    top = re.match(r"^([A-Za-z_]\w*):", line)
    if top:
        section = top.group(1)
    m = re.match(r"^  (\w+):(.*)$", line)
    if m and section == "resources" and m.group(1) in wanted:
        line = f"  {m.group(1)}:{wanted[m.group(1)]}"; done.add(m.group(1))
    elif m and section == "evaluation" and m.group(1) == "systems":
        line = "  systems: [ " + ", ".join(f'"{s}"' for s in systems) + " ]"; done.add("systems")
    elif m and section == "defaults" and m.group(1) == "label":
        line = f"  label: {label}"; done.add("label")
    lines.append(line)
missing = (set(wanted) | {"systems", "label"}) - done
if missing:
    sys.exit(f"Not found in {src}: {', '.join(sorted(missing))} (resources.<system>, evaluation.systems, defaults.label)")
open(out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
PY
}

failed=()
while read -r host systems profile label _; do
    [[ -z "${host:-}" || "$host" == \#* ]] && continue
    [[ "$profile" == limited || "$profile" == free ]] || { echo "$host: the profile must be limited or free, not '$profile'"; failed+=("$host"); continue; }
    [[ -n "${label:-}" && "$label" != \#* ]] || label=$([[ "$profile" == limited ]] && echo 1CPU || echo free)
    dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
    out="$TMP/${host//[@:]/_}.yml"
    echo "== $dest: $systems, $profile, label $label"
    machine_config "$CONFIG" "$out" "$systems" "$profile" "$label" || { failed+=("$host"); continue; }
    if $DRY; then
        diff "$CONFIG" "$out" | grep '^>' | sed 's/^> /   /'
        continue
    fi
    scp -q "$out" "$dest:$REMOTE_DIR/src/main/resources/test_config.yml" || { echo "WARNING: not configured"; failed+=("$host"); continue; }
    if $RUN; then
        ssh "$dest" "cd $REMOTE_DIR && ./gradlew --stop >/dev/null; nohup setsid ./gradlew evaluate > evaluation.log 2>&1 < /dev/null &" \
            && echo "   started: ssh $dest tail -f $REMOTE_DIR/evaluation.log" || { echo "WARNING: not started"; failed+=("$host"); }
    fi
done < "$1"

[[ ${#failed[@]} -eq 0 ]] || { echo "Failed: ${failed[*]}"; exit 1; }
