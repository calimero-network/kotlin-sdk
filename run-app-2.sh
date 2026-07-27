#!/usr/bin/env bash
#
# run-app-2.sh — kept as an alias for the two-user stack, which now lives in
# run-all-2.sh (same behaviour, plus AVD cloning and a launch loop that can't
# spin forever). Use either name.
exec "$(dirname "$0")/run-all-2.sh" "$@"
