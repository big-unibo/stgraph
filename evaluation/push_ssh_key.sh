#!/usr/bin/env bash
# Push your SSH public key to the authorized_keys of one or more remote hosts.
#
# Usage:
#   ./push-ssh-key.sh [-k ~/.ssh/id_ed25519.pub] [-u user] [-p port] host
#   ./push-ssh-key.sh -u mfrancia host1 host2 user@host3:2222
#   ./push-ssh-key.sh hosts.txt
#
# hosts.txt: one host per line, as  host | user@host | user@host:port
# Blank lines and lines starting with # are ignored.
# You are asked for each host's password once (unless the key is already there).
# Works without ssh-copy-id too (e.g., Git Bash on Windows).

set -uo pipefail

KEY="${HOME}/.ssh/id_rsa.pub"
USER_DEFAULT="${USER:-$(whoami)}"
PORT_DEFAULT=22

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

while getopts "k:u:p:h" opt; do
  case $opt in
    k) KEY="$OPTARG" ;;
    u) USER_DEFAULT="$OPTARG" ;;
    p) PORT_DEFAULT="$OPTARG" ;;
    *) usage ;;
  esac
done
shift $((OPTIND - 1))
[ $# -ge 1 ] || usage

# Create a key if none exists yet.
if [ ! -f "$KEY" ]; then
  echo "No key at $KEY - generating one."
  ssh-keygen -t ed25519 -f "${KEY%.pub}" -N "" -C "$(whoami)@$(hostname)" || exit 1
fi

# Collect targets: a file of hosts, or hosts given as arguments.
TARGETS=()
for arg in "$@"; do
  if [ -f "$arg" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%%#*}"; line="$(echo "$line" | xargs)"
      [ -n "$line" ] && TARGETS+=("$line")
    done < "$arg"
  else
    TARGETS+=("$arg")
  fi
done

# Appends the key to authorized_keys over a plain ssh session (no ssh-copy-id), once.
push_key() { # user host port
  ssh -p "$3" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "$1@$2" \
    'umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys &&
     read -r key && { grep -qxF "$key" ~/.ssh/authorized_keys || echo "$key" >> ~/.ssh/authorized_keys; }' \
    < "$KEY"
}

ok=(); fail=()
for t in "${TARGETS[@]}"; do
  host="$t"; user="$USER_DEFAULT"; port="$PORT_DEFAULT"
  [[ "$host" == *@* ]] && { user="${host%%@*}"; host="${host#*@}"; }
  [[ "$host" == *:* ]] && { port="${host##*:}"; host="${host%:*}"; }

  echo "==> ${user}@${host}:${port}"
  # Already installed? (key-only login, no prompt)
  if ssh -p "$port" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
         -i "${KEY%.pub}" "${user}@${host}" true 2>/dev/null; then
    echo "    key already present"; ok+=("$t"); continue
  fi
  if command -v ssh-copy-id >/dev/null 2>&1; then
    ssh-copy-id -i "$KEY" -p "$port" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "${user}@${host}"
  else
    push_key "$user" "$host" "$port"
  fi
  if [ $? -eq 0 ]; then
    ok+=("$t")
  else
    echo "    FAILED"; fail+=("$t")
  fi
done

echo
echo "Done: ${#ok[@]} ok, ${#fail[@]} failed."
[ ${#fail[@]} -eq 0 ] || { printf '  failed: %s\n' "${fail[@]}"; exit 1; }
