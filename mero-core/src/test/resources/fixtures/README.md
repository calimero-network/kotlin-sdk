# Captured fixtures

Response bodies captured **verbatim** from a live node, not hand-written.

A hand-written fixture that matches the model can only confirm the model agrees
with itself — which is how both the rc.25 `groupId`→`namespaceId` rename and the
invitation field drop got past a fully green suite. These are the real bytes.

| file | captured from | route |
|---|---|---|
| `invitation-rc32.json` | `merod 0.11.0-rc.32` | `POST /admin-api/namespaces/{id}/invite` |
| `sealed/mero-tee-v2.3.8{6,7}.*`, `sealed/mero-kms-v2.3.87.*` | the mero-tee / mero-kms GitHub releases (file, cosign bundle, `.sig`), via mero-js `src/sealed/fixtures/` | signed release assets; `…tampered…` has one byte changed |
| `sealed/tdx_quote.bin`, `sealed/tdx_quote_collateral.json` | `@phala/dcap-qvl`'s sample TDX quote and its Intel-signed collateral, via mero-js | the input of `Dcap.verify` |
| `agent-gate/rust-sealed-bundle.json` | mero-agent-gate (Rust, the `hpke` crate), via mero-js `src/agent-gate/fixtures/` | an HPKE Auth-mode `/provision` message |

## Re-capturing

```sh
. ./ci/core-version
# …download and boot merod $CORE_TAG (TESTING.md §4a), then:
TOK=$(curl -s -X POST localhost:4001/auth/token -H 'content-type: application/json' \
  -d '{"auth_method":"user_password","public_key":"probe","client_name":"probe",
       "timestamp":0,"provider_data":{"username":"dev","password":"dev-password"}}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["access_token"])')
curl -s -X POST "localhost:4001/admin-api/namespaces/$NS/invite" \
  -H "authorization: Bearer $TOK" -H 'content-type: application/json' -d '{}' \
  | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin)["data"]["invitation"],indent=2))'
```

Ids and signatures are from a throwaway local namespace and mean nothing outside it.
