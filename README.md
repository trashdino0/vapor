# Vapor

A general-purpose Java obfuscator with both a CLI and a desktop GUI. v1 shipped the foundation:
**renaming** (classes / methods / fields), **string encryption** and **metadata hygiene**. v2 adds
**hardening**: opaque predicates, dead-branch injection, integer/long constant obfuscation,
control-flow noise and polymorphic string decryptors. v3 adds concat-recipe encryption,
float/double constants and bounded control-flow flattening with entry-stack spilling. v5 adds
**hardening 2.0**: a per-class encoded string pool, `ConstantValue` string encryption, record
component and annotation options, opaque entry splits for straight-line methods, and broader
flattening. It also adds an opt-in **encrypted-class loader** (with CRC32 integrity headers) for
executable jars. Everything is deterministic, budgeted and failsafe-by-default, with a
ProGuard-style mapping export.

```
usage:
  vapor                       launch the desktop GUI
  vapor --gui                 launch the desktop GUI
  vapor -i <input.jar> -o <output.jar> [options]

options:
  -i, --input <jar>       input jar
  -o, --output <jar>      output jar
  -lib, --library <path>  library jar or class directory (repeatable)
  -config <file>          JSON config (defaults: full v3 pipeline, hardening on)
  -mapping <file>         write a ProGuard-style mapping file
  -keep <glob>            keep class names matching the dotted glob (repeatable)
  -g, --gui               launch the desktop GUI
  -v, --version           print version
  -h, --help              this help
```

## Build

```
.\gradlew.bat build          # tests + shadow jar
# → build\libs\vapor-0.5.0.jar
```

Requires JDK 21 to build (the output supports Java 8–21 class files; every class is written with
`COMPUTE_FRAMES`). The Gradle wrapper is included.

## Example

```
java -jar build\libs\vapor-0.5.0.jar -i app.jar -o app-obf.jar -config vapor.json -mapping mapping.txt
```

## Desktop GUI

```
java -jar build\libs\vapor-0.5.0.jar --gui
# or, from the source tree:
.\gradlew.bat gui
```

Running the jar with no arguments also opens the GUI. It provides input/output/mapping pickers,
library paths, keep-rule editing, per-transform switches, all string/hardening knobs, config
load/save, a live pipeline log and a determinate progress bar with cooperative cancellation.

The GUI calls the same `Obfuscator` core as the CLI, so output is byte-identical for the same
input and config. It writes to temporary files beside the target and moves them into place only
after a successful run: a failed or cancelled job never replaces an existing output jar. The
`Cancel` button stops at the next safe point (between stages and while iterating classes); the
partial temporary output is deleted.

## What Vapor does

| Transformer | Effect |
|---|---|
| `Renamer` | Renames classes (flattened to the root package), methods and fields through a global `name+descriptor` key — overrides, bridges and interface dispatch stay consistent. Innermost-aware; lambdas' call sites follow the SAM rename. |
| `MetadataHygiene` | Strips line numbers, local variable tables, `MethodParameters`, generic signatures; rewrites `SourceFile` to `a`; clears `SourceDebugExtension`. `stripAnnotations` optionally drops runtime annotations. |
| `StringEncryptor` | Encodes every non-empty string (length ≥ `strings.minLength`, default `0`) into a synthetic per-class `String[]` pool plus an `int[]` key table initialized in `<clinit>`; call sites carry only an integer index. `encryptConstantValues` also moves static final `String` fields into encrypted `<clinit>` assignments. **Polymorphic**: each class gets one of 12 decryptor bodies (4 math shapes × 3 loop forms). It also rewrites `invokedynamic` string-concat literals through the same pool. |
| `DeadCode` | Inserts opaque-predicate-guarded decoy blocks at stack-empty points (never before `super()` in constructors). All junk is side-effect-free even if executed. |
| `FlowShuffle` | Control-flow noise: guard splits, condition perturbation (`(x)` → `(x ^ 0)`), jump inversion. Constructors are left alone. |
| `BlockSplit` | Adds an opaque entry branch to branch-free methods (minimum `hardening.blockSplitMinInstructions`, default 6) so the flattener has a basic-block boundary to work with. |
| `FlowFlatten` | Bounded control-flow flattening — blocks are moved behind a `lookupswitch` state machine (random, unique state values; permuted block order; `IllegalStateException` default). One dispatcher per verifier-consistent entry-frame signature (merged reference types use the project hierarchy); values live across a branch are spilled and reloaded; the analyzer models `NEW`/`<init>` transitions. Try/catch and provably safe post-`super()` constructor prefixes are supported by the v5 flattener; `<clinit>` and artificial decryptors are deliberately skipped. |
| `Virtualize` | Opt-in (`transformers.virtualize`): translates eligible static `int`/`void` methods into a compact `int[]` stack program and a generated per-method interpreter class. It runs before the flow transforms; helper classes are committed after flattening and then receive numeric obfuscation. Unsupported methods are untouched. |
| `IntObfuscation` | Rewrites every real int/long/float/double constant push as `v ^ k` with a per-site key (floats/doubles go through their raw bits + `intBitsToFloat`/`longBitsToDouble`; NaN is skipped to keep payload bits exact). Runs last so constants injected by other transforms are covered too. |
| `ClassProtector` | Opt-in (`protection.encryptClasses`): encrypts class bodies at the final jar-write boundary, prepends a `VP01`/`VP00` header, embeds a Java 8-compatible bootstrap loader, and points `Main-Class` at it. `protection.verifyIntegrity` adds a CRC32 of each plaintext class, verified by the loader before `defineClass`. |
| `Watermark` | Optionally embeds the configured watermark as a private synthetic constant in one deterministic class. This field is deliberately excluded from constant-value encryption so provenance remains discoverable. |
| `ResourceFixups` | Rewrites `Main-Class` / `Launcher-Agent-Class` / `Agent-Class` and `META-INF/services/*` (file name and provider lines) to follow the mapping. |
| `MappingExport` | Writes a ProGuard-style mapping (classes, then fields, then methods with readable signatures). |

v5 raises the static bar with the string pool, `ConstantValue` handling, record/annotation
options and broader flattening. The class protector adds a runtime barrier for executable jars,
and the loader verifies per-class CRC32 integrity when requested. An opt-in int-only
virtualizer prototype is available for selected methods (see `transformers.virtualize`); it
leaves unsupported methods untouched. Still deferred: full arbitrary-bytecode virtualization,
native/agent protections and anti-debugging. These are brittle and are not a substitute for
server-side secrets.

## Config reference

Default: no config file → everything below at its default (all transforms on, seed `0`).

```json
{
  "seed": 0,
  "failsafe": true,
  "keep": ["com.example.api.*", "com.example.Plugin"],
  "watermark": null,
  "transformers": {
    "renameClasses": true,
    "renameMethods": true,
    "renameFields": true,
    "stripLineNumbers": true,
    "stripLocalVariables": true,
    "stripMethodParameters": true,
    "stripSignatures": true,
    "stripSourceFile": true,
    "encryptStrings": true,
    "deadCode": true,
    "integerObfuscation": true,
    "flowShuffle": true,
    "flowFlatten": true,
    "obfuscateRecords": false,
    "stripAnnotations": false,
    "virtualize": false
  },
  "strings": {
    "minLength": 0,
    "polymorphic": true,
    "pool": true,
    "encryptConstantValues": true
  },
  "hardening": {
    "maxOverhead": 2.0,
    "deadBranchesPerMethod": 3,
    "flowSplitsPerMethod": 6,
    "flattenMaxBlocks": 128,
    "flattenMinBlocks": 2,
    "splitSingleBlock": true,
    "blockSplitMinInstructions": 6,
    "virtualizeMaxInstructions": 400
  },
  "protection": {
    "encryptClasses": false,
    "verifyIntegrity": true
  }
}
```

| Key | Meaning |
|---|---|
| `seed` | Dictionary seed (`long`). `0` = plain sequential names (`a`, `b`, …); non-zero deterministically shuffles the name pool. Same input + same seed → byte-identical output. |
| `failsafe` | `true` (default): a renaming failure logs a warning and the jar is written **un-renamed** instead of the run failing. `false`: the exception propagates. |
| `keep` | Class-name globs in dotted form, `*` and `?` supported (`com.example.*`). Kept classes keep their names **and** all their declared members; CLI `-keep` adds to this list. |
| `watermark` | Optional string. Embedded as a `ConstantValue` in a deterministic class; findable with `javap -v` / `strings` (it is deliberately **not** encrypted). |
| `transformers.*` | Per-transform switches — see the table above (`integerObfuscation` covers all numeric constants: int, long, float, double). |
| `strings.minLength` | Strings shorter than this stay in plaintext. Default `0` encrypts every non-empty string. |
| `strings.polymorphic` | `true` (default): per-class decryptor body picked among 12 shapes. `false`: the classic XOR/forward decryptor (smallest output). |
| `strings.pool` | `true` (default): store encoded strings and keys in per-class tables; call sites carry an index. `false`: classic per-call encoded literal + key. |
| `strings.encryptConstantValues` | `true` (default, pool mode): move static final `String` constants into encrypted `<clinit>` assignments. |
| `transformers.obfuscateRecords` | Rename record backing fields, accessors and `RecordComponent` attributes together; can break reflection by component name. |
| `transformers.stripAnnotations` | Drop runtime-visible/invisible annotations; breaks frameworks that reflect on them. |
| `transformers.virtualize` | Opt-in int-only VM for eligible static methods (parameters/return `int`/`void`, no objects/exceptions/calls). Unsupported methods are left untouched. |
| `hardening.maxOverhead` | Growth cap for the noise transforms: each method gains at most `(maxOverhead − 1) × its size` extra instructions. `2.0` = up to double the instructions. |
| `hardening.deadBranchesPerMethod` | Max decoy blocks `DeadCode` inserts per method. |
| `hardening.flowSplitsPerMethod` | Max edits `FlowShuffle` performs per method (splits + perturbations + inversions). |
| `hardening.flattenMaxBlocks` | Methods with more basic blocks than this stay unflattened (default `128`; also capped at 2000 instructions). |
| `hardening.flattenMinBlocks` | Minimum basic blocks before flattening (default `2`). |
| `hardening.splitSingleBlock` | Add an opaque entry branch to straight-line methods so they become flattenable. |
| `hardening.blockSplitMinInstructions` | Minimum method size for `BlockSplit` (default `6`). |
| `hardening.virtualizeMaxInstructions` | Instruction cap for the int-only virtualizer (default `400`). |
| `protection.encryptClasses` | Encrypt class entries and embed the bootstrap loader; requires a manifest `Main-Class`. Executable `java -jar` output only. |
| `protection.verifyIntegrity` | Store/verify a CRC32 of each plaintext class (requires class encryption). |

## Encrypted-class mode

`protection.encryptClasses` is for **executable jars** launched with `java -jar`. The final
write pass encrypts each class entry, appends an unencrypted bootstrap loader, and rewrites the
manifest to point `Main-Class` at the loader while retaining the application entry point in
`Vapor-Main-Class`. The loader decodes classes through the system jar resource and defines them
with the child `ClassLoader`; library classes without the Vapor header fall back to the parent.

`protection.verifyIntegrity` (default `true`) prepends a CRC32 of the plaintext class to each
encrypted entry. The loader verifies it before `defineClass`; a modified ciphertext fails with a
`SecurityException` (or a class-loading failure) instead of running. The key is embedded in the
loader: this is a static-analysis barrier and tamper evidence, **not** cryptographic secrecy
against a process dump.

The mode intentionally skips libraries without a `Main-Class`, signed jars, multi-release jars,
agents and modular jars. `java -cp protected.jar Main` does not use the loader; use `java -jar`.
Do not distribute `mapping.txt` if names must stay hidden.

## Keep rules — what is never renamed

Renaming is applied through global member keys, so anything that must keep its name anchors the
whole key. Automatic anchors:

- `<init>` / `<clinit>`, `main`, `premain` / `agentmain`;
- enum `values()` / `valueOf()` and `ACC_ENUM` constants;
- `serialVersionUID` and serialization hooks (`writeObject`, `readObject`, `readObjectNoData`,
  `writeReplace`, `readResolve`) on `Serializable` classes;
- record component fields and their accessors (component **names** are kept by default;
  `transformers.obfuscateRecords` renames the field, accessor and `RecordComponent` attribute together);
- all methods of annotation types (annotation use-sites store element names as plain strings);
- every member key declared both by a jar class and by a reachable library supertype (override
  safety), plus every key whose call-site resolution lands on a library member (e.g. `name()`
  inherited from `java.lang.Enum`);
- config `keep` globs and `-keep`;
- anything annotated `@VaporKeep` — matched by **simple name**, so define your own annotation
  (e.g. `com.example.VaporKeep`, `CLASS` retention); the annotation is stripped from the output.

Surviving reflection (`Class.forName`, `getDeclaredMethod`, …) needs explicit keeps — that is
true of every obfuscator.

## Mapping file

`-mapping mapping.txt` writes a ProGuard-style mapping:

```
dev.sample.Circle -> a:
    double radius -> radius
    java.lang.String toString() -> toString
```

Kept classes/members appear as identity lines. Synthetic artifacts (string decryptors, the
watermark field before renaming) show up under their generated names.

## Known limitations (v1-v5, intentional)

- **Plaintext that stays plaintext:** annotation element values, record `toString` recipes, and
  string-concat recipes that contain literal control characters (those ride in as `\u0002`
  bootstrap constants and are left untouched - fail-safe). Static final `String` `ConstantValue`
  fields are moved into the encrypted `<clinit>` path when `strings.pool` and
  `strings.encryptConstantValues` are on (the default); the watermark is the deliberate exception.
- **Record component names are kept by default** (the `ObjectMethods` bootstrap couples them).
  `transformers.obfuscateRecords` renames the field/accessor/metadata triplet together.
- **Package structure is flattened** — renamed classes go to the root package.
- **`module-info.class` disables renaming** (warning emitted): a modular jar's exports would
  otherwise lie about renamed packages.
- Multi-release `META-INF/versions/**` class entries are passed through **unobfuscated**
  (the base classes are obfuscated normally).
- Interface string decryptors are `public static` (private interface methods need class-file
  v53+); every decryptor is `ACC_SYNTHETIC`. Interfaces older than class-file v52 are promoted
  to v52 when a decryptor is injected (static interface methods need v52; the output targets
  Java 8+ anyway).
- **Constructors** get `DeadCode` only after the `super()` / `this()` call; `FlowShuffle` leaves
  them alone. `FlowFlatten` flattens the post-`super()` suffix when the prefix is branch-free,
  allocation-free and contains exactly one constructor call; otherwise it skips.
- **`FlowFlatten` coverage is deliberately bounded**: constructors with unprovable pre-super
  prefixes, artificials, `<clinit>`, JSR/RET, unresolved try-range boundaries, more than 2000
  instructions, or more than `flattenMaxBlocks` blocks stay unflattened. Try/catch
  (including `synchronized`) is supported: ranges are split at boundaries and each moved block
  gets its own table entry with handlers kept outside the dispatcher. Values live across a
  branch (ternaries, short-circuit expressions) are spilled into fresh locals since v3.6. The
  remaining skip rules are what keep the transform verifier-safe.
- **The virtualizer is intentionally narrow:** eligible methods are static with only
  `int`/`void` parameters/returns and arithmetic/branch bytecodes; objects, arrays, exceptions,
  switches, field/method calls and initializers are left untouched. It is opt-in because the
  generated interpreter adds a class and a dispatch loop per method.
- Flattening drops the `LocalVariableTable` of rewritten methods (block reordering invalidates its
  ranges); the default pipeline strips those tables anyway.
- **Noise transforms are budgeted, not maximal** — they trade size for readability, capped by
  `hardening.*`. Raise `maxOverhead` at the cost of jar size.
- Missing `-lib` dependencies make the renamer conservative (more anchors), never wrong; resolve
  unresolvable hierarchies by pointing `-lib` at the app's dependencies.

## Testing

```
.\gradlew.bat test                                  # 56 unit/integration tests
.\gradlew.bat gui                                   # launch the desktop UI from classes
.\scripts\rig.ps1 -Sample hello                     # integration rig, Java 21 sample
.\scripts\rig.ps1 -Sample legacy8                   # integration rig, Java 8 sample
.\scripts\rig.ps1 -Sample hardening                 # integration rig, v2 hardening sample
.\scripts\rig.ps1 -Sample hello -Build -SkipDecompile
```

The rig compiles a sample, runs the baseline, obfuscates, then verifies: **mapping sanity**
(renamed class present/absent under the right paths), **plaintext markers** (per-sample
`plaintext-markers.txt` must be absent from the obfuscated jar), **watermark presence** (if the
config sets one), **behavior** (stdout identical, exit code equal) and the **decompiler output**
(CFR + Vineflower) for manual inspection. Workspace: `%TEMP%\opencode\vapor-rig\<sample>-<release>\`.

A stronger structural check:

```
java -Xverify:all tools\LoadCheck.java <jar>        # loads every class, touches members
```

The v2 acceptance test obfuscated **CFR itself** (`tools/cfr-0.152.jar`, 1302 classes, full v2
defaults): all classes load under `-Xverify:all`, and the obfuscated CFR decompiles both a sample
and its own obfuscated jar **byte-identically** to the pristine build (6/6 and 733/733 files).
The 0.5.0 hardening release re-ran the CFR stress with the new defaults: 1302/1302 classes load
under `-Xverify:all` and the output is deterministic. A protected `legacy8` jar (and a
protected+virtualized Java 8 fixture) was also launched with the installed JDK 8
(`-Xverify:all`, exit 0), confirming the loader and helper class-file v52 path. The new suites
cover encrypted-jar determinism/tamper rejection, string pools and constant-value fields,
record metadata, try/catch + constructor flattening, and the virtualizer (program encoding,
differential execution, helper class-file version 52, and pipeline commit/load).

## Architecture

```
dev.vapor
├── Main                     CLI + GUI launch, option parsing
├── Obfuscator               shared CLI/GUI pipeline entry point
├── gui/VaporGui             Swing front-end: form, worker, log, progress, cancel
│                            (watermark → rename → hygiene → strings → deadcode → flow → ints)
├── config/VaporConfig       Gson config model
├── context/                 Context, JClass, JarEntry, SimpleHierarchy, LibraryLoader, JarIO
├── naming/                  Dictionary, Mappings, RemapperImpl, VaporClassRemapper
├── pipeline/                Pipeline, PipelineListener, JarLoad, JarWrite, ResourceFixups, MappingExport
├── transform/impl/          Watermark, Renamer, MetadataHygiene, StringEncryptor,
│                            DeadCode, FlowShuffle, IntObfuscation, Junk, OpaquePredicates
└── util/                    Insns, Mix (deterministic mixing + xorshift32)
```

Design notes worth knowing: member keys are global (`name+descriptor`) so all override chains
rename identically; generated names can never collide with jar or reachable-library member names;
the apply phase computes every remapped class **before** committing anything; `JarWrite` recomputes
all stack map frames through the (post-rename) hierarchy; zip entries get fixed timestamps so
output is byte-identical across runs; every v2 transform is an in-place insertion (no block
reordering) with a per-method instruction budget, and all randomness runs through `Mix`
(`seed` + owner + method), never wall-clock time. The GUI and CLI both call `Obfuscator`, so
front-end choice cannot change the produced bytes.

`docs/NOTES.md` carries the deeper engineering notes; `docs/PROGRESS.md` tracks the phase log;
`PLAN.md` is the master plan.

## Credits / references

Behavior and design were studied from public obfuscator projects (notably Aidsfuscator and
Skidfuscator, via bytecode inspection) and from ASM's documentation. No GPL-ecosystem source code
was copied; all implementation here is original.
