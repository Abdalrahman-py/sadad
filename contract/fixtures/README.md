Shared JSON fixtures loaded by both the Edge (`api`) Deno tests and the Kotlin `:ledger-core` tests.

## `mobile/`

The `/v1/mobile/*` contract, used by `functions/api/mobile_test.ts` and `proof_test.ts`.

- `proof.json`: one device proof with its exact signed text. A client port must produce the same `proofText` byte for byte.
- `enroll.json`, `operations.json`, `changes.json`: request and response examples. Strings in braces (`{installationId}`, `{epoch}`, `{publicKey}`) are filled in by the test.
- `refusals.json`: HTTP status and contract code for each refusal.

Every signed request sends `x-sadad-installation`, `x-sadad-time` (13-digit epoch ms), `x-sadad-nonce` (UUID) and `x-sadad-signature` (raw r||s, base64url). The signed route is the path from `/v1` plus the query string, for example `/v1/mobile/changes?after=1&limit=200`.
