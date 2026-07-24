#!/usr/bin/env bash
#
# check-registry-parity.sh — every public SDK method must be reachable from the
# sample app's SDK Explorer.
#
# The sample app doubles as the SDK's living documentation: if a method exists on
# AdminApi/AuthApi/RpcClient but no `SDKOperation` invokes it, the explorer quietly
# stops covering part of the surface. This script diffs the two lists and fails
# with the missing names.
#
# Methods that intentionally have no explorer entry go in EXCLUDED below, with a
# reason — internal plumbing, or things the UI drives on your behalf.
#
# Usage: ./ci/check-registry-parity.sh   (from the repo root)

set -euo pipefail

CORE_SRC="mero-core/src/main/kotlin/com/calimero/mero"
REGISTRY="sample-app/src/main/kotlin/com/calimero/mero/sample/explorer/OperationRegistry.kt"

# Internal / non-explorer methods, each with a reason.
EXCLUDED=(
  authenticate       # Mero-level login flow, driven by the login screen
  logout             # ditto
  events             # SSE stream — consumed live by the chat screen, not a form
  execute            # raw HttpClient escape hatch behind rpc/admin
  executeRaw         # ditto, untyped variant
  encodeComponent    # internal: percent-encoding helper
  compareSemverComponent  # internal: helper behind compareSemver
  origin             # internal: base-URL accessor
  fetchExternal      # internal: cross-node fetch helper
  getMetadataRecord  # internal: shared metadata decoder
  rawJson            # internal: untyped passthrough used by typed wrappers
  subscribe          # SSE subscription plumbing
)

methods() {
  grep -ohE '\bfun [a-zA-Z0-9_]+' \
    "$CORE_SRC/admin/AdminApi.kt" "$CORE_SRC/auth/AuthApi.kt" "$CORE_SRC/rpc/RpcClient.kt" \
    | sed -E 's/^fun //' | sort -u
}

# Third string literal of each SDKOperation(...) is the method name.
registry_names() {
  grep -ohE '"[a-zA-Z0-9_.]+", *"[^"]+", *"[a-zA-Z0-9_.]+"' "$REGISTRY" \
    | awk -F'", *"' '{ gsub(/"/, "", $3); print $3 }' | sort -u
}

missing=""
reg=$(registry_names)
while read -r m; do
  [ -n "$m" ] || continue
  skip=false
  for e in "${EXCLUDED[@]}"; do [ "$m" = "$e" ] && skip=true && break; done
  $skip && continue
  # Registry entries may be qualified (e.g. rpc.execute) — match the bare tail too.
  if ! printf '%s\n' "$reg" | grep -qE "(^|\.)${m}$"; then
    missing="$missing $m"
  fi
done <<< "$(methods)"

echo "SDK methods:        $(methods | wc -l | tr -d ' ')"
echo "Registry entries:   $(printf '%s\n' "$reg" | grep -c . | tr -d ' ')"

if [ -n "$missing" ]; then
  echo
  echo "✗ SDK methods with no SDK Explorer entry:"
  for m in $missing; do echo "    - $m"; done
  echo
  echo "Add an SDKOperation for each in $REGISTRY,"
  echo "or list it in EXCLUDED in this script with a reason."
  exit 1
fi

echo "✓ every public SDK method has an SDK Explorer entry"
