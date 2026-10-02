#!/bin/bash
# Judge0 1.13.1 calls isolate with isolate 1.x options. isolate 2.x always times
# a cgroup run by the cgroup, so it dropped --cg-timing / --no-cg-timing;
# strip those two and pass everything else through unchanged.
args=()
for arg in "$@"; do
  case "$arg" in
    --cg-timing|--no-cg-timing) ;;
    *) args+=("$arg") ;;
  esac
done
exec /usr/local/bin/isolate.real "${args[@]}"
