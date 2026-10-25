# Vapor

A general-purpose Java obfuscator. v1 shipped the foundation: **renaming** (classes / methods /
fields), **string encryption** and **metadata hygiene**. v2 adds **hardening**: opaque predicates,
dead-branch injection, integer/long constant obfuscation, control-flow noise and polymorphic
string decryptors. Everything is deterministic, budgeted and failsafe-by-default, with a
ProGuard-style mapping export.

```
usage:
  vapor -i <input.jar> -o <output.jar> [options]

options:
  -i, --input <jar>       input jar
  -o, --output <jar>      output jar
  -lib, --library <path>  library jar or class directory (repeatable)
  -config <file>          JSON config (defaults: full pipeline, v2 hardening on)
  -mapping <file>         write a ProGuard-style mapping file
  -keep <glob>            keep class names matching the dotted glob (repeatable)
  -v, --version           print version
  -h, --help              this help
```

## Build

```
.\gradlew.bat build          # tests + shadow jar
# → build\libs\vapor-0.3.2.jar
```

Requires JDK 21 to build (the output supports Java 8–21 class files; every class is written with
`COMPUTE_FRAMES`). The Gradle wrapper is included.

## Example

```
java -jar build\libs\vapor-0.3.2.jar -i app.jar -o app-obf.jar -config vapor.json -mapping mapping.txt
```

## What Vapor does

| Transformer | Effect |
|---|---|
| `Renamer` | Renames classes (flattened to the root package), methods and fields through a global `name+descriptor` key — overrides, bridges and interface dispatch stay consistent. Innermost-aware; lambdas' call sites follow the SAM rename. |
| `MetadataHygiene` | Strips line numbers, local variable tables, `MethodParameters`, generic signatures; rewrites `SourceFile` to `a`; clears `SourceDebugExtension`. |
| `StringEncryptor` | Replaces `LDC` string constants (length ≥ `strings.minLength`) with an encoded constant + per-string key + a call to a synthetic per-class decryptor. **Polymorphic**: each class gets one of 12 decryptor bodies (4 math shapes × 3 loop forms). Since v3.1 it also rewrites `invokedynamic` string-concat call sites into decryptor-backed `StringBuilder` chains, so recipe literals leave the class file too. |
| `DeadCode` | Inserts opaque-predicate-guarded decoy blocks at stack-empty points (never before `super()` in constructors). All junk is side-effect-free even if executed. |
| `FlowShuffle` | Control-flow noise: guard splits, condition perturbation (`(x)` → `(x ^ 0)`), jump inversion. Constructors are left alone. |
| `FlowFlatten` | v3.3: bounded control-flow flattening — blocks are moved behind a `lookupswitch` state machine (random, unique state values; permuted block order; `IllegalStateException` default). One dispatcher per verifier-consistent entry-frame signature (merged reference types use the project hierarchy); methods with try/catch or constructors are skipped. Since v3.6, values live across a branch (ternaries, short-circuit expressions) are spilled into fresh locals on every edge and reloaded inside the target case; the analyzer models `NEW`/`<init>` transitions so stored objects keep their initialized types. |
| `IntObfuscation` | Rewrites every real int/long/float/double constant push as `v ^ k` with a per-site key (floats/doubles go through their raw bits + `intBitsToFloat`/`longBitsToDouble`; NaN is skipped to keep payload bits exact). Runs last so constants injected by other transforms are covered too. |
| `Watermark` | Optionally embeds the configured watermark as a private synthetic constant in one deterministic class. |
| `ResourceFixups` | Rewrites `Main-Class` / `Launcher-Agent-Class` / `Agent-Class` and `META-INF/services/*` (file name and provider lines) to follow the mapping. |
| `MappingExport` | Writes a ProGuard-style mapping (classes, then fields, then methods with readable signatures). |

v3 adds bounded control-flow flattening on top (see `FlowFlatten` above), including v3.6
entry-stack spilling that makes ternaries and short-circuit expressions flattenable. Still
deferred: reference/proxy obfuscation, anti-tamper/integrity checks, native and virtualization.
The pipeline is built so those plug in as additional transformers.

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
    "flowFlatten": true
  },
  "strings": {
    "minLength": 2,
    "polymorphic": true
  },
  "hardening": {
    "maxOverhead": 2.0,
    "deadBranchesPerMethod": 3,
    "flowSplitsPerMethod": 6,
    "flattenMaxBlocks": 128
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
| `strings.minLength` | Strings shorter than this stay in plaintext (tiny strings are not worth a call site). |
| `strings.polymorphic` | `true` (default): per-class decryptor body picked among 12 shapes. `false`: the classic XOR/forward decryptor (smallest output). |
| `hardening.maxOverhead` | Growth cap for the noise transforms: each method gains at most `(maxOverhead − 1) × its size` extra instructions. `2.0` = up to double the instructions. |
| `hardening.deadBranchesPerMethod` | Max decoy blocks `DeadCode` inserts per method. |
| `hardening.flowSplitsPerMethod` | Max edits `FlowShuffle` performs per method (splits + perturbations + inversions). |
| `hardening.flattenMaxBlocks` | Methods with more basic blocks than this stay unflattened (default `128`; also capped at 2000 instructions). |

## Keep rules — what is never renamed

Renaming is applied through global member keys, so anything that must keep its name anchors the
whole key. Automatic anchors:

- `<init>` / `<clinit>`, `main`, `premain` / `agentmain`;
- enum `values()` / `valueOf()` and `ACC_ENUM` constants;
- `serialVersionUID` and serialization hooks (`writeObject`, `readObject`, `readObjectNoData`,
  `writeReplace`, `readResolve`) on `Serializable` classes;
- record component fields and their accessors (component **names** are kept);
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

## Known limitations (v1/v2/v3, intentional)

- **Plaintext that stays plaintext:** `ConstantValue` field initializers, annotation element
  values, record `toString` recipes, and string-concat recipes that contain literal control
  characters (those ride in as `\u0002` bootstrap constants and are left untouched — fail-safe).
  These are not `LDC` instructions.
- **Record component names are kept** (the `ObjectMethods` bootstrap couples them).
- **Package structure is flattened** — renamed classes go to the root package.
- **`module-info.class` disables renaming** (warning emitted): a modular jar's exports would
  otherwise lie about renamed packages.
- Multi-release `META-INF/versions/**` class entries are passed through **unobfuscated**
  (the base classes are obfuscated normally).
- Interface string decryptors are `public static` (private interface methods need class-file
  v53+); every decryptor is `ACC_SYNTHETIC`. Interfaces older than class-file v52 are promoted
  to v52 when a decryptor is injected (static interface methods need v52; the output targets
  Java 8+ anyway).
- **Constructors** get `DeadCode` only after the `super()` / `this()` call, and `FlowShuffle` /
  `FlowFlatten` leave them alone entirely.
- **`FlowFlatten` coverage is deliberately bounded**: methods with try/catch (including
  `synchronized` blocks), constructors, artificials, JSR/RET, more than 2000 instructions, or
  more than `flattenMaxBlocks` blocks stay unflattened. Values live across a branch (ternaries,
  short-circuit expressions) are *not* a skip reason since v3.6: they are spilled into fresh
  locals on every edge and reloaded inside the target case. The remaining skip rules are what
  keep the transform verifier-safe.
- Flattening drops the `LocalVariableTable` of rewritten methods (block reordering invalidates its
  ranges); the default pipeline strips those tables anyway.
- **Noise transforms are budgeted, not maximal** — they trade size for readability, capped by
  `hardening.*`. Raise `maxOverhead` at the cost of jar size.
- Missing `-lib` dependencies make the renamer conservative (more anchors), never wrong; resolve
  unresolvable hierarchies by pointing `-lib` at the app's dependencies.

## Testing

```
.\gradlew.bat test                                  # unit tests (hierarchy, predicates, shapes, flow)
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

## Architecture

```
dev.vapor
├── Main                     CLI + pipeline wiring
│                            (watermark → rename → hygiene → strings → deadcode → flow → ints)
├── config/VaporConfig       Gson config model
├── context/                 Context, JClass, JarEntry, SimpleHierarchy, LibraryLoader, JarIO
├── naming/                  Dictionary, Mappings, RemapperImpl, VaporClassRemapper
├── pipeline/                Pipeline, JarLoad, JarWrite, ResourceFixups, MappingExport
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
(`seed` + owner + method), never wall-clock time.

`docs/NOTES.md` carries the deeper engineering notes; `docs/PROGRESS.md` tracks the phase log;
`PLAN.md` is the master plan.

## Credits / references

Behavior and design were studied from public obfuscator projects (notably Aidsfuscator and
Skidfuscator, via bytecode inspection) and from ASM's documentation. No GPL-ecosystem source code
was copied; all implementation here is original.
