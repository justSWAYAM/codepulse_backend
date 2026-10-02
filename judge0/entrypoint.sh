#!/bin/bash
# Set up the isolate cgroup, then hand over to Judge0's own entrypoint.
sudo /usr/local/bin/cgroup-setup.sh
exec /api/docker-entrypoint.sh "$@"
