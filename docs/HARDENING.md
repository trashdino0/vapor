# Hardening & runtime protection

This document tracks the post-0.4 strengthening work. The short version:

- **Static layer:** a per-class encrypted string pool (including `ConstantValue`
  strings), optional record-component and annotation stripping, an opaque entry
  branch for straight-line methods, and stronger flattening coverage.
- **Runtime layer (opt-in):** encrypted class bodies plus a Java 8-compatible
  bootstrap loader, with an optional CRC32 integrity header.
- **Selected-method virtualization:** a conservative int-only VM prototype
  exists for methods whose parameters/return are all `int`/`void` and whose
  bodies use integer arithmetic/control flow only. It is opt-in and leaves
  unsupported methods untouched.
- **Anti-tamper:** the loader verifies the CRC32 of each decrypted class before
  defining it. This detects accidental/tampered ciphertext; the key is present
  in the loader, so it is not a cryptographic authenticity boundary.

## Config additions

```json
{
  "transformers": {
    "obfuscateRecords": false,
    "stripAnnotations": false,
    "virtualize": false
  },
  "strings": {
    "minLength": 0,
    "pool": true,
    "encryptConstantValues": true
  },
  "hardening": {
    "splitSingleBlock": true,
    "blockSplitMinInstructions": 6,
    "flattenMinBlocks": 2,
    "virtualizeMaxInstructions": 400
  },
  "protection": {
    "encryptClasses": false,
    "verifyIntegrity": true
  }
}
```

## Class encryption usage

The protection path only activates when the input manifest has a
`Main-Class`. It is intended for executable jars launched with `java -jar`;
library jars are written normally and class encryption is skipped with a
reason in the log.

The output manifest points `Main-Class` at the embedded loader and keeps the
renamed application entry point in `Vapor-Main-Class`. The loader:

- reads encrypted entries through the system jar resource;
- recognizes the `VP01` (CRC32) and `VP00` (no CRC) headers;
- decrypts a class and verifies the checksum when present;
- defines the class through `ClassLoader.defineClass`;
- falls back to the parent for library classes whose resources do not carry the
  Vapor header.

Limitations: the encryption key is embedded in the unencrypted loader; a
running process can dump classes. Multi-release jars, signed jars, agents and
modular jars are intentionally skipped. Run with `java -jar`; launching a
protected main class through `java -cp` will not use the loader.

## Record components

When `obfuscateRecords` is enabled, the renamer gives the record's backing
field, accessor method and `RecordComponent` attribute one new name and rewrites
the `ObjectMethods` bootstrap name list. This changes `RecordComponent.getName`
and the `toString()` component labels; leave the option off if JSON reflection,
record serialization or tests depend on the original names.

## String pool

`strings.pool` stores encoded literal text and per-string keys in synthetic
per-class arrays initialized in `<clinit>`. Call sites carry only an integer
index. This removes the per-call encoded literal/key pattern. With
`encryptConstantValues`, static final `String` fields lose their `ConstantValue`
attribute and are assigned in `<clinit>`; this is a runtime-value change for
reflection but preserves ordinary field reads. Compile-time constants inlined
by other compilers are outside Vapor's control.

## What is still missing

- A true arbitrary-bytecode VM (the current prototype is intentionally narrow).
- Native/agent protections and anti-debugging; these are brittle and are not a
  substitute for server-side secrets.
- Protection of annotation values and bootstrap strings that are not handled by
  the record/string passes.
- A cryptographic tamper-proof seal: the embedded loader key makes the current
  check tamper-evident rather than attacker-proof.
