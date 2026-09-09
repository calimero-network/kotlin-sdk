# Merobox sync checks

Backend/protocol-level tests that boot real `merod` nodes in Docker (via
[merobox](https://github.com/calimero-network/merobox)) and assert that state
**syncs across nodes**. They isolate the node + WASM + sync layer from the Android
sample app and the emulator, so a sync regression shows up here as a clean,
fast signal.

## What runs

- **`sync-two-node.yml`** — installs the canonical `kv_store` app, meshes one
  context across two nodes, writes a key on node 1, waits for the context state
  hash to **converge across both nodes**, then reads the key back on node 2 and
  asserts it matches. The `wait_for_sync` step is the important part: if node 2
  never initializes its context (the all-ones "uninitialized" hash) or never
  catches up, it times out and the check fails instead of passing silently.
- **`sync-two-node-bidi.yml`** — same setup, but asserts **both directions**:
  node 1 → node 2 *and* node 2 → node 1 (the joiner writes, the creator must
  see it). Catches a sync path that only works outward from the context creator.

The CI job also always dumps each node's logs and a best-effort **peer-connectivity
count**, so a failure can be diagnosed as *"nodes never peered"* (discovery /
networking) vs *"peered but state didn't sync"* (the sync path itself).

## Fetch the fixture before running

`res/` is **not committed** — the app bundle is downloaded per-run from the core
release pinned in [`ci/core-version`](../core-version):

```sh
. ./ci/core-version
gh release download "$CORE_TAG" --repo calimero-network/core \
  --pattern 'kv-store-test-fixture.mpk' \
  --output ci/merobox/res/kv-store.mpk --clobber
```

It must stay an **`.mpk` bundle**: a dev install refuses a raw `.wasm`.

## History: the `1111…` failure was a wasm/node version mismatch

The first runs failed with node 2 stuck on the all-ones **uninitialized** hash
while node 1 had a real hash:

```
context=…:
  calimero-node-1: BkifspwXGw7MfKumpuYkB8RNFmS3fqZ1s4nwR4zytdNV
  calimero-node-2: 11111111111111111111111111111111
```

That looked like the Android chat sync bug, but it was **our harness**: the vendored
`kv_store.wasm` was a stale build incompatible with the `edge` node, so the
joining node could never initialize the context. merobox's own Docker CI passes
all 30+ sync scenarios against `edge` — it just always *builds* the wasm from
core `master`. Fixed by doing the same (see the wasm note below).

With the matching wasm, both scenarios converge on CI in **under 2 seconds**
(forward and backward), so the job is a **gating check** — a red run means a
genuine regression at the pinned release.

The drift that caused it cannot recur: node image and app bundle now come from
the *same* core release (below), instead of a moving `edge` image and a wasm
someone rebuilt by hand.

## Run locally

Requires Docker running.

```sh
pip install merobox
merobox bootstrap validate ci/merobox/sync-two-node.yml   # schema only, no Docker
merobox bootstrap run      ci/merobox/sync-two-node.yml   # boots 2 nodes in Docker
```

## Node image and app bundle: one pinned release

Both come from [`ci/core-version`](../core-version), and CI fails the job if a
scenario's `image:` no longer agrees with that file — a half-landed bump would
otherwise run the *old* node and report green.

```
CORE_TAG=0.11.0-rc.32
MEROD_IMAGE=ghcr.io/calimero-network/merod:0.11.0-rc.32
```

`ghcr.io/calimero-network/merod` publishes **a tag per rc** (plus `-profiling`
variants and a short-sha per release commit), so nothing here needs `edge`.
An earlier note in this file claimed no `0.11.x` tag existed; it was wrong, and
tracking `edge` is what let the node move underneath a fixed app. Override for
one run via the `merod_image` `workflow_dispatch` input.

The app comes from the same release: every core tag publishes
`kv-store-test-fixture.mpk`, built from that release's own SDK. Nothing is
vendored, so a rebuild-by-hand step cannot go stale — bumping `ci/core-version`
moves node and app together.

## ⚠️ The joining node needs the app installed too

core 0.11.0-rc.31 (#3652) made distribution **registry-only**. The context
registration op carries `package` + `version`, and a joining node resolves those
against **its own** registry instead of pulling the blob from the peer.

These fixtures dev-install a local bundle whose coordinates
(`com.calimero.kv-store@<core tag>`) are published nowhere, so node 2 reached out
to `apps.calimero.network`, found nothing, and failed its first execution:

```
bytecode blob c13ac279…7778ed not found in blobstore
JSON-RPC Error: InternalError
```

Three steps after the join, and **after `wait_for_sync` reported the context hash
converged** — governance replicated fine; only the bytecode was missing. So both
scenarios install the bundle on node 2 as well. The ApplicationId is derived from
package + signer, so the two installs agree.

The chat scenario needs no such step, and that is the control: `com.calimero.chat`
is really published, so node 2 resolves it from the registry the same way node 1 did.

## Bumping the core release

1. Edit `ci/core-version` (`CORE_TAG` **and** `MEROD_IMAGE`, same release).
2. Update the `image:` line in each `ci/merobox/*.yml` to match.
3. Push — the image-agreement check and both sync scenarios run on the PR.
