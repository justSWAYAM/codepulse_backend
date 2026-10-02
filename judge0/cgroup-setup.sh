#!/bin/bash
# Prepare a cgroup v2 subtree for isolate inside this (privileged) container.
# Runs as root at container start; safe to run again on restart.
# Same approach as the Docker-in-Docker entrypoint.
set -u

CG=/sys/fs/cgroup

# Only needed on cgroup v2 hosts; on cgroup v1 nothing to do.
[ -f "$CG/cgroup.controllers" ] || exit 0

WANTED=""
for c in memory pids cpu; do
  grep -qw "$c" "$CG/cgroup.controllers" && WANTED="$WANTED +$c"
done

# cgroup v2 "no internal processes" rule: a cgroup that holds processes can't
# enable controllers for its children. Move everything into a leaf, then enable.
# Processes can appear in the root while we work (forks, docker exec), so retry.
mkdir -p "$CG/init"
ok=0
for attempt in $(seq 1 50); do
  # bash builtins only, so this loop forks nothing into the root cgroup
  while read -r pid; do
    echo "$pid" > "$CG/init/cgroup.procs" 2>/dev/null || true
  done < "$CG/cgroup.procs"
  if echo "$WANTED" > "$CG/cgroup.subtree_control" 2>/dev/null; then
    ok=1
    break
  fi
  sleep 0.1
done

if [ "$ok" != 1 ]; then
  echo "cgroup-setup: could not enable$WANTED on $CG (processes still in root cgroup)" >&2
  exit 1
fi

mkdir -p "$CG/isolate"
echo "$WANTED" > "$CG/isolate/cgroup.subtree_control"
mkdir -p /run/isolate/locks
echo "cgroup-setup: isolate cgroup ready ($WANTED)"
