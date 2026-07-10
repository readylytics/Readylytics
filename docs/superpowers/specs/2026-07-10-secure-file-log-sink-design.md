# SecureFileLogSink Crypto Modernization Design

Date: 2026-07-10
Status: Draft for review
Target: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`

## Goal

Replace deprecated `MasterKey` and `EncryptedFile` usage in `SecureFileLogSink` with supported encryption approach that:

- keeps encrypted on-disk log storage
- allows fresh-format cutover with no legacy migration
- moves crypto details out of log sink into shared helper
- stays consistent with existing repo security patterns

## Context

Current `SecureFileLogSink` builds `MasterKey` and `EncryptedFile` on each encrypted read/write. Both APIs are deprecated. Repo already contains two relevant patterns:

- `EncryptionManager` uses Tink plus `AndroidKeysetManager` and Android Keystore-backed master key
- `SqlCipherKeyManager` uses direct `AndroidKeyStore` AES key generation and raw cipher operations

`SecureFileLogSink` currently reads all log slots, appends pending content, trims total capacity, repartitions into slot files, and rewrites all slot files. Current behavior may read whole file contents into memory; design should preserve behavior unless log-storage refactor is explicitly desired later.

User confirmed:

- old encrypted log files do not need to remain readable
- shared helper is preferred over sink-local crypto code
- best engineering fit should be chosen rather than minimizing dependencies

## Non-Goals

- no backward-compatible migration of old encrypted log files
- no partial mixed-format read support
- no change to log buffering, trimming, partitioning, or slot rotation semantics
- no change to diagnostic log-reading API behavior beyond fresh-format cutover

## Options Considered

### 1. Tink `StreamingAead` shared helper

Use Tink streaming file encryption with Android Keystore-protected keyset material in dedicated helper.

Pros:

- closest supported replacement for `EncryptedFile` use case
- built for file streaming and large payloads
- less custom crypto framing code
- consistent with repo already using Tink

Cons:

- requires dedicated helper and stream-based test coverage
- introduces log-specific Tink keyset wiring rather than direct reuse of current string `Aead` manager

### 2. Tink `Aead` shared helper with whole-file encrypt/decrypt

Encrypt complete file contents using non-streaming `Aead`.

Pros:

- simpler helper API
- directly resembles existing `EncryptionManager`

Cons:

- not stream-native for file storage
- more temporary plaintext/ciphertext buffering
- less future-proof if log storage grows

### 3. Direct `AndroidKeyStore` AES/GCM helper

Manage file format, IVs, and stream wrapping directly with Keystore-generated AES key.

Pros:

- no extra Tink streaming abstraction
- full control over format

Cons:

- most custom crypto code
- more room for subtle mistakes
- more testing burden

## Decision

Use option 1: add shared helper backed by Tink `StreamingAead`.

Reasoning:

- `StreamingAead` is purpose-built for encrypted file access and best matches deprecated `EncryptedFile` responsibility
- repo already depends on Tink patterns, so operational model stays familiar
- helper can keep `SecureFileLogSink` focused on buffering and slot management rather than cryptographic lifecycle

## Proposed Architecture

Add shared helper under `app/src/main/kotlin/app/readylytics/health/data/security/`. Suggested names:

- `SecureFileStore`
- `EncryptedFileStore`

Recommended name: `SecureFileStore`, because helper abstracts secure file read/write behavior rather than mirroring deprecated API names.

Responsibilities:

- register Tink streaming config once
- initialize or load dedicated log-encryption keyset
- ensure dedicated Android Keystore master key alias exists
- expose encrypted text read/write operations for arbitrary files
- treat unreadable, missing, or obsolete-format log files as empty content

`SecureFileLogSink` responsibilities after change:

- buffer log lines
- decide when to flush
- read full logical log contents through helper
- trim and repartition
- rewrite slot files through helper

Crypto concerns should no longer live in `SecureFileLogSink`.

## Key Management Design

Use dedicated key material for secure log files. Do not reuse SQLCipher key or existing string-encryption keyset directly.

Suggested dedicated constants:

- keystore alias: `readylytics_log_file_key_v1`
- shared prefs file: `secure_log_file_keyset_prefs`
- keyset name: `secure_log_file_keyset`

Helper should:

1. register Tink streaming config during initialization
2. ensure Android Keystore alias exists, using same general pattern as current `EncryptionManager`
3. build `AndroidKeysetManager` with dedicated prefs + keyset name
4. request `StreamingAead` primitive from keyset handle

This separation avoids coupling secure log files to other encrypted-storage lifecycles and leaves room for future rotation.

## File Format and Data Flow

Use Tink `StreamingAead` ciphertext format directly. No custom file header should be added unless implementation forces it.

Associated data:

- bind encryption to stable file identity, such as relative log-slot path bytes
- recommended input: `file.name.toByteArray(Charsets.UTF_8)` or log-directory-relative path bytes

Write flow:

1. sink computes new slot contents
2. empty slot content deletes or omits file
3. non-empty content writes through helper using encrypted output stream
4. helper should write atomically where practical so half-written files do not become normal steady-state outcome

Read flow:

1. sink asks helper for file text
2. missing file returns empty string
3. decrypt failure, legacy format, or corrupt bytes return empty string for log use case
4. next successful flush rewrites file in fresh supported format

This design intentionally discards any pre-existing `EncryptedFile` output rather than migrating it.

## API Sketch

Helper surface should stay small:

```kotlin
interface SecureFileStore {
    fun readText(file: File, associatedData: ByteArray = defaultAssociatedData(file)): String
    fun writeText(file: File, content: String, associatedData: ByteArray = defaultAssociatedData(file))
}
```

Implementation detail may instead choose internal helper methods like:

- `openInput(file, associatedData)`
- `openOutput(file, associatedData)`

If testability is easier with stream-oriented internals plus text convenience methods, prefer that split. Public API should still remain narrow.

## `SecureFileLogSink` Changes

`SecureFileLogSink` should:

- receive `SecureFileStore` dependency, ideally constructor-injected
- remove direct imports of `MasterKey` and `EncryptedFile`
- delete `getEncryptedFile(file)`
- delegate encrypted reads to helper
- delegate encrypted writes to helper

Behavior that should remain unchanged:

- write serialization via coroutine scope
- 5-line / 2-second flush threshold
- total-capacity trim
- UTF-8 byte-aware truncation
- slot ordering and rewrite-all-slots model
- `readLogsDecrypted()` returning plaintext for diagnostics

## Failure Handling

Read failures:

- helper catches decryption/keyset/keystore/IO exceptions
- return empty content for log files rather than throwing
- optionally emit warning log for diagnosis

Write failures:

- helper throws or returns failure up to sink write path
- sink already catches exceptions around buffered write job and logs failure

Key initialization failures:

- should fail fast at first encrypted operation rather than silently claiming success
- sink error path already logs write failures; helper should include precise failure messages

No legacy fallback decryptor should exist. Old format support is intentionally out of scope.

## Testing Strategy

### Unit tests

Add focused tests around helper abstraction with injected primitive/factory seams where possible:

- read missing file returns empty string
- write then read round-trip returns original text
- corrupt ciphertext read behaves as disposable empty content
- associated data mismatch fails read and is handled as designed

For `SecureFileLogSink` tests:

- encrypted path delegates read/write to helper
- unreadable old slot content does not block next flush
- next flush recreates readable fresh-format slot file
- existing trim and partition behavior remains unchanged

### Integration scope

Only add Android instrumented coverage for Tink/Keystore wiring if repo already has similar security integration pattern or if unit seams prove insufficient. Keep helper wiring thin so most logic remains regular unit-testable Kotlin.

## Rollout Notes

- fresh-format cutover means release drops previous secure log contents
- this is acceptable per user direction
- no user-visible migration or settings flow needed

## Risks

- Tink streaming setup differs from current string-encryption setup and may need one-time API discovery
- associated-data choice must remain stable across reads/writes for same slot file
- atomic-write strategy must not leave undecryptable partial output under normal interruption cases

## Open Implementation Decisions

These are intentionally narrow, implementation-level choices left for planning:

- exact helper class name
- whether helper API exposes stream methods, text methods, or both
- whether atomic writes use temp file plus replace or direct overwrite
- exact associated-data source (`file.name` versus relative path)

None of these affect architectural direction.

## Recommended Plan Scope

Single implementation plan should cover:

1. add shared secure file helper
2. refactor `SecureFileLogSink` to use helper
3. add or update tests for helper and sink behavior

No broader refactor needed.
