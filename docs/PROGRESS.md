# Vapor — Progress Tracker

> Live status for the build described in [PLAN.md](../PLAN.md). Updated at the end of every
> phase / work session. Format: status table → per-phase checklists → dated log → decisions.

## Status

| Phase | What | Status |
|---|---|---|
| 0 | Bootstrap (Gradle, samples, rig, pass-through) | ✅ Done (2026-10-17) |
| 1 | Core engine (Context, hierarchy, frames, pipeline) | ✅ Done (2026-10-17) |
| 2 | Renaming | ✅ Done (2026-10-18) |
| 3 | Metadata hygiene | ✅ Done (2026-10-18) |
| 4 | String encryption | ✅ Done (2026-10-18) |
| 5 | Hardening & polish | ✅ Done (2026-10-18) |
| V0 | v2 scaffolding (0.2.0, hardening sample) | ✅ Done (2026-10-18) |
| V1 | Opaque predicates + `DeadCode` | ✅ Done (2026-10-18) |
| V2 | Integer/long constant obfuscation | ✅ Done (2026-10-18) |
| V3 | Control-flow noise (`FlowShuffle`) | ✅ Done (2026-10-18) |
| V4 | Polymorphic string decryptors | ✅ Done (2026-10-18) |
| V5 | Stress test + docs | ✅ Done (2026-10-18) |
| v3.0–v3.4 | v3: coverage & control flow (0.3.0) | ✅ Done (2026-10-19) |
| v3.5 | Post-release flattening fixes (0.3.1) | ✅ Done (2026-10-19) |

**v1 is complete** — renaming + metadata hygiene + string encryption (+ watermark), rig-verified
on Java 21 and Java 8 samples.

**v2 (hardening) is complete** — opaque predicates + dead branches, integer/long constant
obfuscation, flow noise and polymorphic decryptors. All transforms are dependency-free
(in-place insertions only), deterministic, budgeted, and verified by executable unit tests plus a
self-hosting stress test (obfuscated CFR decompiles its own obfuscated jar byte-identically).

**v3 (coverage & control flow) is complete (0.3.1)** — concat-recipe encryption, float/double
constants and bounded control-flow flattening on top of the v2 envelope. The 0.3.1 patch fixed a
pipeline interaction that left flattening mostly inert (stale `maxStack`, guard-split anchor
placement, exact-only frame merging). Reference obfuscation, anti-tamper, salting and
virtualization remain deferred (PLAN.md).

## Phase 0 — Bootstrap ✅ (2026-10-17)

- [x] Gradle (Kotlin DSL) skeleton: Java 21 toolchain, Shadow plugin, ASM/Gson/JUnit wired
- [x] Gradle wrapper generated (`gradlew.bat`, `gradle/wrapper/*`) — Gradle 9.8.1 pinned
- [x] `dev.vapor.Main` CLI: `-i -o`, `--help`, `--version`; pass-through jar round-trip
- [x] `dev.vapor.file.JarIO`: order-preserving raw entry read/write (`ZipInputStream`, so
      `MANIFEST.MF` isn't swallowed like `JarInputStream` would)
- [x] `samples/hello` (release 21): interfaces/default methods, 3-level inheritance, enum with
      abstract method + anonymous subclasses + `values()`/`valueOf()`, lambdas (indy), indy string
      concat, StringBuilder concat, `String.format`, switch-on-string, records, sealed interface,
      inner + anonymous classes, static initializer, try/catch, reflection (keep-rule target),
      `Serializable` round trip, `ServiceLoader` + services file
- [x] `samples/legacy8` (release 8): StringBuilder concat, lambda, anonymous + inner classes, enum,
      default interface method, `Serializable` round trip
- [x] `scripts/rig.ps1`: compile → baseline run → obfuscate → obf run → stdout+exit-code diff →
      CFR + Vineflower decompiles (with logs and output checks)
- [x] Rig green: `hello` — PASS (stdout identical, exit code 0)
- [x] Rig green: `legacy8` — PASS (stdout identical, exit code 0)
- [x] Round-trip byte-identical: every file entry in `hello-obf.jar` hash-matches `hello.jar` (20/20)
- [x] Class file versions verified: hello = major 65 (Java 21), legacy8 = major 52 (Java 8)

Verification commands:

```powershell
.\gradlew.bat build                # -> build\libs\vapor-0.1.0.jar
.\scripts\rig.ps1 -Sample hello
.\scripts\rig.ps1 -Sample legacy8
```

Artifacts for inspection: `%TEMP%\opencode\vapor-rig\hello-21\` and `...\legacy8-8\`
(baseline/obfuscated jars, stdout captures, `dec-cfr-*` / `dec-vf-*` decompiles).

## Phase 1 — Core engine ✅ (2026-10-17)

- [x] `Context` world model: ordered jar entries (`JarEntry.Resource` / `JarEntry.ClassEntry`),
      class map, resource map, `LibraryLoader`, lazy `SimpleHierarchy`
- [x] `LibraryLoader`: `-lib` jars/directories (indexed, `ZipFile` handles kept open) + JDK classes
      via `jrt:/` (`/packages` → module → header-only parse); per-name caching
- [x] `IHierarchy` + `SimpleHierarchy`: assignability (superclass + interface BFS),
      `getCommonSuperClass` (JVMS-style, arrays handled, conservative `java/lang/Object` fallback)
- [x] `VaporClassWriter extends ClassWriter` overriding `getCommonSuperClass` + static `write()` helper
- [x] Write path uses `COMPUTE_FRAMES` — every finished class is re-emitted with freshly computed
      stack map frames (never copied from input)
- [x] Pipeline framework: `IPass` + `Pipeline` (preprocess → transformers → postprocess) with
      per-pass timing; `Transformer` base class (no concrete transformers yet — phase 2 adds them)
- [x] `JarLoad` pass (class parsing; `META-INF/*`, multi-release classes, `module-info.class` and
      directories → untouched resources)
- [x] `JarWrite` pass (replays context in original order; deterministic output — fixed zip timestamps)
- [x] `-lib` CLI option (repeatable), help text updated
- [x] Unit tests: `SimpleHierarchyTest` — 12 tests across JDK (`jrt:`), jar, arrays, fallbacks; green
- [x] Rig green: `hello` — PASS (stdout identical, exit 0), frames recomputed across all 18 classes
- [x] Rig green: `legacy8` — PASS (stdout identical, exit 0)
- [x] Determinism: two runs over the same input → byte-identical jars (SHA-256 verified)

## Phase 2 — Renaming ✅ (2026-10-18)

- [x] JSON config (`VaporConfig`, Gson): `seed`, `failsafe`, `keep[]`, `transformers.*`,
      `strings.minLength`, `watermark`; empty/missing config = full v1 pipeline
- [x] CLI: `-config <file>`, `-mapping <file>`, `-keep <glob>` (repeatable); help updated
- [x] `Dictionary`: bijective base-26 names (`a..z, aa..`), skips every taken name, optional
      deterministic block shuffle via `seed`
- [x] `Mappings` + `RemapperImpl`: classes by internal name; methods/fields keyed globally by
      `name+descriptor` (override chains, bridges and interface dispatch rename as one unit)
- [x] `VaporClassRemapper`: lambda call sites (`LambdaMetafactory`) renamed via the SAM type in
      bootstrap args; all other invokedynamic names (concat, record toString, switch bootstraps)
      intentionally untouched
- [x] Keep rules: config globs (dotted form), `@VaporKeep` by **simple name** on class/method/
      field, annotation stripped from output; kept class ⇒ all its declared member keys anchored
- [x] Automatic anchors:
      `main`, `<init>/<clinit>`, agent entry points (`premain`/`agentmain`), enum
      `values()/valueOf()` + `ACC_ENUM` constants, `serialVersionUID` + serialization magic methods
      (`writeObject`/`readObject`/`readObjectNoData`/`writeReplace`/`readResolve`) on
      `Serializable` classes, record component field+accessor, annotation element methods,
      library-declared member keys on any jar class (override safety)
- [x] Reference-resolution anchoring: every method/field reference + method/
      `<clinit>`-handle is resolved through the hierarchy; when the *nearest declaration* is a
      library member (e.g. `name()` inherited from `java/lang/Enum`, called with a jar-class
      owner) or the chain has unresolved classes, the key is anchored
- [x] Class naming: flattened to the root package; inner classes get `outerNew + "$" + suffix`
      (fixed-point resolution, jar-order independent), broken metadata falls back to a flat name;
      dictionary reserved against every original name
- [x] Member naming: separate method/field dictionaries; generated names avoid every name present
      in the jar + reachable library supertypes (a rename can never shadow/override a library
      member or an anchored one)
- [x] Single remap pass; all remapped copies computed before any commit (failsafe: a failure
      leaves the jar un-renamed); `Context.rekeyClasses()` re-indexes for post-rename
      `COMPUTE_FRAMES` (hierarchy cache dropped)
- [x] `ResourceFixups`: manifest `Main-Class`/`Launcher-Agent-Class`/`Agent-Class` +
      `META-INF/services/*` (file name and provider lines) follow the mapping
- [x] `MappingExport`: ProGuard-style `mapping.txt` (classes + readable field/method signatures),
      identity fallback when renaming is disabled
- [x] Samples: `@VaporKeep` annotation, class-level keep (`FancyGreeter`), member-level keep
      (`Outer.Inner.get`), config keep (`dev.sample.EnglishGreeter`), two new reflection probes
      (`annotationKeep`, `memberKeep`) proving the keep paths at runtime
- [x] Rig: passes `-config` + `-mapping`, verifies the mapping (renamed class present under the
      new path, absent under the old), full decompile run both samples
- [x] **Bug found & fixed via rig**: `NoSuchMethodError 'String c.d()'` — a call-site
      `invokevirtual Day.name()` (inherited from `Enum`) was renamed because another jar class
      declared the same key; fixed by the reference-resolution anchor scan above
- [x] Rig green: `hello` + `legacy8` — stdout identical, exit 0, mapping checks PASS
- [x] Determinism re-verified: two runs → byte-identical jars (SHA-256)

## Phase 3 — Metadata hygiene ✅ (2026-10-18)

- [x] `MetadataHygiene` transformer honoring the config flags:
      `stripLineNumbers` (LineNumberTable removed), `stripLocalVariables` (LVT + local variable
      type table), `stripMethodParameters` (`MethodParameters`), `stripSignatures` (generic
      signatures on classes / methods / fields / record components), `stripSourceFile`
      (`SourceFile` → `"a"`, `SourceDebugExtension` cleared)
- [x] Wired into `Main` right after `Renamer` (works on the post-rename tree, before frames are
      recomputed at write time)
- [x] Kept on purpose: `InnerClasses` / nest attributes / `EnclosingMethod` (carry semantics and
      are already consistently remapped) and runtime annotations
- [x] Rig green both samples; `javap -v` confirms the stripped attributes are gone

## Phase 4 — String encryption ✅ (2026-10-18)

- [x] `StringEncryptor`: every `LDC String` with `length >= strings.minLength` becomes an
      encoded constant + key + call to a synthetic per-class decryptor
- [x] Encoding is a position-dependent XOR (`char[i] ^ (key + i)`, computed as int, truncated to
      char); the decryptor reverses it in place (`toCharArray` → XOR loop → `new String`)
- [x] Keys are deterministic: class-name hash, `seed` and a per-class string counter mixed with
      fixed odd constants
- [x] Decryptor name: first base-26 dictionary name free among the class's method names
- [x] Interfaces: `public static` decryptor and `itf=true` on the call site — private static
      interface methods require class-file v53+, and v1 must stay loadable on Java 8 (v52)
- [x] Deliberately not encrypted (documented in the transformer + NOTES): `ConstantValue` field
      strings, annotation element values, and invokedynamic bootstrap arguments (string-concat
      and record recipes)
- [x] `samples/*/plaintext-markers.txt` + rig scan: every listed plaintext string must be absent
      from the obfuscated jar
- [x] Rig green: hello (8 markers absent), legacy8 (6 markers absent), stdout identical, exit 0
- [x] **Bug found & fixed via rig**: `IncompatibleClassChangeError` — a static call to an
      interface method must use an InterfaceMethodref (`itf=true`), and v52 interfaces cannot
      declare private static methods; both handled
- [x] Determinism re-verified with encryption enabled (byte-identical jars)

## Phase 5 — Hardening & polish ✅ (2026-10-18)

- [x] `Watermark` transformer: config `watermark` becomes a `private static final String`
      (`ACC_SYNTHETIC`) with a `ConstantValue` in the lexicographically-first eligible class
      (deterministic; interfaces / annotations / `module-info` excluded); injected **before**
      `Renamer` so its name obfuscates safely and cannot collide or shadow
- [x] Failsafe mode (default on): a renaming failure warns and writes the jar un-renamed; the
      apply phase is compute-then-commit in every transformer
- [x] Deterministic output: fixed zip timestamps + seeded dictionaries + class/seed/index-derived
      string keys — byte-identical jars verified across runs with encryption + watermark enabled
- [x] `module-info.class` → renaming disabled with a warning (implicit keep)
- [x] Multi-release `META-INF/versions/**` class entries passthrough (base classes obfuscated)
- [x] Rig: watermark presence check (reads `watermark` from the sample config, scans entries)
- [x] `README.md`: overview, CLI, config reference, keep rules, mapping format, limitations,
      architecture, testing
- [x] Full final pass: unit tests green; `hello` + `legacy8` rigs green (mapping / plaintext
      markers / watermark / behavior / decompile); determinism byte-identical
- [x] Optional (not done, listed in PLAN): side-by-side decompiled-quality comparison against
      Aidsfuscator/Skidfuscator output

## v2 / V0 — Scaffolding ✅ (2026-10-18)

- [x] Version `0.2.0`; `VaporConfig` grows the v2 surface: `transformers.deadCode`,
      `transformers.integerObfuscation`, `transformers.flowShuffle` (all default **on**),
      `strings.polymorphic`, and a `hardening` block (`maxOverhead` 2.0,
      `deadBranchesPerMethod` 3, `flowSplitsPerMethod` 6)
- [x] `samples/hardening`: loops, switches, shapes/constants, inner classes and an interface —
      exercises every transform; `vapor.json` with the standard watermark
- [x] Rig switches for the new sample; all three rigs green before any transform work

## v2 / V1 — Opaque predicates + `DeadCode` ✅ (2026-10-18)

- [x] `Context` gains the *artificials* registry (synthetic decryptor methods are excluded from
      noise transforms); `StringEncryptor` registers its decryptor and was refactored onto it
- [x] `OpaquePredicates`: three always-true predicate forms over a method salt
      (`(x*x+x) & 1 == 0`, `(x | 1) * (x | 1) >= 0`, `(x ^ x>>31) < 0`), each unit-tested by
      generating a host method in ASM, loading it and executing it (edge values + 5000 random
      cases per form)
- [x] `DeadCode`: inserts opaque-predicate-guarded junk blocks at stack-empty points found with
      ASM's `BasicVerifier` (`Analyzer`), never before `super()` in constructors, salt/junk
      locals via `maxLocals`, per-method budget = `(maxOverhead−1) × instructions-at-start`
- [x] Analyzer failure on a method ⇒ the method is skipped (failsafe), never a broken class
- [x] All junk is side-effect-free even when executed; every choice seeded from
      `Mix.of(seed, owner, method)` — no wall clock, no map order
- [x] Tests (3 predicate forms + budget behavior); rigs green on all three samples
- [x] Effect verified: `goto` 29 → 83, `istore` 52 → 122 in `javap -c`; decompiled output shows
      real `if ((n6 ^ n6 >> 31) < 0)` guards; determinism byte-identical

## v2 / V2 — Integer & long constant obfuscation ✅ (2026-10-18)

- [x] `IntObfuscation`: every real push of an int/long constant (`ICONST_*`, `LCONST_*`, `BIPUSH`,
      `SIPUSH`, `LDC` of Integer/Long) becomes `push(v ^ k); push k; IXOR`/`LXOR`
      (`k` per site via `Mix`), stack-neutral, no analysis needed
- [x] Artificial (synthetic-decryptor) methods are never touched; `Float`/`Double` deferred to v3
- [x] Wired **last** in the pipeline so constants injected by other transforms are obfuscated too
- [x] `IntObfuscationTest`: for 16 int + 8 long values, the instruction stream really contains the
      XOR pattern *and* the method still returns the original value when executed
- [x] Rigs green, determinism byte-identical

## v2 / V3 — Control-flow noise (`FlowShuffle`) ✅ (2026-10-18)

- [x] `Junk` extracted (shared side-effect-free junk emitter); `DeadCode` refactored onto it
- [x] `FlowShuffle` three techniques, all in-place and dependency-free:
      guard splits (always-true predicate jumps over junk), condition perturbation
      (`if (x) ` → `if ((x ^ 0))`), jump inversion (all 16 conditional opcodes, done last)
- [x] `<init>` is skipped entirely (constructor invariants); cap = `hardening.flowSplitsPerMethod`;
      salt local initialized lazily at the method head
- [x] Differential test harness: `FlowFixture` (loops / switch / do-while state machine) +
      `Reload` (child-first loader) — 19 int inputs × pairs + longs, results identical, goto
      count increased
- [x] Rigs green on all three samples (including a full decompile pass); determinism
      byte-identical (`det4` SHA match)

## v2 / V4 — Polymorphic string decryptors ✅ (2026-10-18)

- [x] `StringEncryptor` rewritten: per-class shape pick from `Mix` among **4 math shapes**
      (XOR, add, multiply, byte-swap) × **3 loop forms** (forward `if_icmpge`, backward from
      `len−1`, equality-terminated), 12 distinct decryptor bodies; `strings.polymorphic=false`
      restores the v1 XOR/forward decryptor
- [x] Multiply shape derives its modular inverse **at runtime** from the per-call-site key
      (`m = key|1`, 4 unrolled Newton iterations, 16 bits); encode side uses the same math
- [x] **Bug caught by the new shape test**: `(x << 8) | (x >>> 8)` swap polluted bits 8–15 when
      `x` had high bits set (XOR with a full-int key) — fixed with byte-wise masking
      `((x & 0xFF) << 8) | ((x >>> 8) & 0xFF)`
- [x] **Bug caught by the CFR stress test**: two class-file **v50 interfaces** in
      `cfr-0.152.jar` got a `static` decryptor — illegal below v52
      (`ClassFormatError: illegal modifiers: 0x1009`); interfaces are now promoted to v52 when a
      decryptor is injected (output targets Java 8+ anyway). Same latent bug existed in v1
- [x] `StringEncryptorShapesTest`: brute-forces class names until all 12 shapes are selected, then
      round-trips each shape end-to-end through real bytecode (encode → define → invoke → compare)
- [x] Rigs green; real output shows all 4 math shapes and all 3 loop forms across the hardening
      classes; determinism byte-identical

## v2 / V5 — Stress test + docs ✅ (2026-10-18)

- [x] `tools/LoadCheck.java`: loads every class of a jar under `-Xverify:all` and touches declared
      members (resolution smoke test)
- [x] Stress target `tools/cfr-0.152.jar` (1302 classes, 69 resources): obfuscated in ~1.7 s with
      full v2 defaults, 2.16 MB → 2.92 MB (1.35×), byte-identical across runs
- [x] LoadCheck: **0 failures / 1302 classes** (was 2 before the v50-interface fix)
- [x] Behavioral equivalence: obfuscated CFR decompiles the hardening sample → all 6 files
      byte-identical to pristine CFR; obfuscated CFR decompiling **its own obfuscated jar** →
      **733/733 files byte-identical**
- [x] Final acceptance: 18/18 unit tests, three rigs green (mapping / plaintext / watermark /
      behavior / decompile), determinism byte-identical on all samples and on CFR
- [x] Docs updated (README v2 features → config reference → limitations; PLAN v2 marked
      delivered; NOTES v2 design/lessons)

## Log

### 2026-10-17 — Phase 0 complete
- Environment: JDK 21.0.8 present; no system Gradle → downloaded the Gradle 9.8.1 distribution to
  `%TEMP%` and used it once to generate the project wrapper (see NOTES.md for paths).
- Pinned stack: ASM 9.11.1, Gson 2.14.0, JUnit 6.1.3, Shadow 9.6.1, Gradle 9.8.1.
- Built the pass-through end-to-end; rig green on both samples; both decompilers run clean.
- Fixed during the build (recorded in NOTES.md so we don't reintroduce them):
  - dropped Gradle `application` plugin (distZip/distTar conflicted with shadowJar output →
    Gradle validation failure);
  - rig hardened against PowerShell native-stderr quirks (scoped `$ErrorActionPreference`);
  - `-Xlint:-options` to silence javac's obsolete-release warnings.
- Open items: workspace is **not a git repo** — optional to init one for commit-per-phase.

### 2026-10-17 — Phase 1 complete
- Engine round-trip is live: `JarLoad` → (no transforms yet) → `JarWrite` with `COMPUTE_FRAMES`
  resolved through the jar + `-lib` + JDK (`jrt:/`) hierarchy; no JEB, no external classpath needed.
- Hierarchy unit tests 12/12 green; they caught one real bug (array element merge must re-encode
  class internal names as array descriptors, e.g. `[Ljava/lang/CharSequence;`).
- Rig green on `hello` (18 classes) and `legacy8` (8 classes) with identical stdout/exit codes; the
  bytecode verifier accepted every recomputed frame.
- Output is deterministic (fixed zip timestamps): same input → byte-identical jar; good basis for
  diffing transform effects in later phases.
- Passthrough decisions recorded in NOTES.md: `META-INF/*` (incl. multi-release nested classes) and
  `module-info.class` stay untouched resources until phases 2/5.

### 2026-10-18 — Phase 2 complete
- Renaming is live with keep rules, mapping export and resource fix-ups; both samples green with
  decompile. Keyed design: global `name+desc` member keys + three anchor layers
  (special names → library declarations → reference resolution).
- The rig immediately caught a dispatch bug (`name()` via `java/lang/Enum`) that pure
  declaration-based anchoring could not see; the reference-resolution scan is now a load-bearing
  part of the renamer (see NOTES.md).
- Output stays deterministic with renaming + shuffled-dictionary support via `seed`.
- Playwright-style lesson re-learned: PowerShell 5.1 needs explicit
  `Add-Type System.IO.Compression[.FileSystem]` before `[IO.Compression.ZipFile]`.

### 2026-10-18 — Phases 3 & 4 complete
- Metadata hygiene live (line numbers, LVT, MethodParameters, signatures, SourceFile).
- String encryption live: per-class synthetic decryptor, deterministic per-string keys,
  interface-safe (itf + v52 access rules). The rig now scans for leaked plaintext markers —
  both samples clean.
- Both bugs this phase were dispatch-shape issues invisible to unit tests but caught instantly by
  the runtime rig (interface call constants). Kept as regression knowledge in NOTES.md.

### 2026-10-18 — Phase 5 complete / v1 done
- Watermark transformer live (config → synthetic `ConstantValue`, deterministic target class);
  rig gained a watermark presence check.
- README written with the full config reference, keep-rule documentation and limitations.
- Final acceptance run: unit tests + both sample rigs green through mapping / plaintext /
  watermark / behavior / decompile stages; output byte-identical across runs.
- v1 scope delivered end-to-end on schedule: renaming + metadata hygiene + string encryption
  against Java 8 and Java 21 samples.

### 2026-10-18 — v2 complete (V0–V5)
- v2 hardening delivered: opaque predicates + dead branches (`DeadCode`), integer/long constant
  obfuscation (`IntObfuscation`), control-flow noise (`FlowShuffle`), polymorphic decryptors
  (`StringEncryptor` 4 math × 3 loop shapes).
- Design held: every transform is an in-place insertion (no block reordering), budgeted via
  `hardening.maxOverhead`, driven purely by `Mix.of(seed, owner, method)` (byte-identical output
  across runs everywhere), and failsafe (skip on analyzer failure, artificials excluded).
- Two real bugs surfaced by the new tests and the stress harness: the `swap16` bit-pollution bug
  (caught by the 12-shape round-trip test) and the v50-interface `static` decryptor
  `ClassFormatError` (caught by obfuscating CFR). Both fixed; the latter also existed latently
  since v1.
- Stress proof: obfuscated CFR (1302 classes) loads 100% under `-Xverify:all` and decompiles
  byte-identically vs the pristine build — both the hardening sample (6/6) and its own obfuscated
  jar (733/733).
- Tooling added: `tools/LoadCheck.java`, `samples/hardening`, shape/flow/differential test
  fixtures with `Reload` loading transformed bytecode in-process.

### 2026-10-18 — sample-jars playground + loader robustness fix
- New `jars/` playground: `input/` → `output/<jar>/` with the obfuscated jar, `mapping.txt`,
  CFR decompiles of both sides and a behavior smoke check; runner
  `scripts/obfuscate-jars.ps1` (+ `-Build/-Clean/-NoDecompile/-Config`),
  demo compiler `scripts/build-demo-jars.ps1`. Ships three samples: `demo-cli.jar`,
  `demo-math.jar` (sources in `jars/src/`) and `asm-9.11.1.jar` (real-world library,
  module-info stripped so renaming applies).
- The playground immediately found two real issues:
  - `JarIO` read via `ZipInputStream` and silently stopped after entry 1 on a .NET-rewritten
    zip → vapor "successfully" wrote a 152-byte 0-class jar. Now reads the central directory
    via `ZipFile`; `JarLoad` warns when zero classes load. Rigs re-verified green after the
    change.
  - PowerShell 5.1 script traps: BOM-less UTF-8 em dash decoded to a smart quote that
    PowerShell accepts as a string delimiter (phantom-string parse errors); `Start-Process`
    quoting/flush issues. Fixed with pure-ASCII scripts + the rig's direct-invocation pattern
    (both recorded in NOTES.md).
- Verified: 18/18 unit tests, three rigs green, determinism byte-identical on the playground
  samples, playground behavior PASS on both runnable demos (asm jar: 40/40 classes read).

### 2026-10-19 — v3.0/v3.1 (git baseline + concat-literal encryption)
- v3.0: repository initialized (`main`); `.gitignore` keeps playground outputs and third-party
  reference jars out of history; baseline commit of v1+v2, then the v3 plan. Commit-per-phase
  from here on.
- v3.1: `StringEncryptor` rewrites `makeConcatWithConstants` call sites into decryptor-backed
  `StringBuilder` chains; recipe literals no longer exist in the class file. Fail-safe skips for
  `\u0002` control-character recipes and malformed shapes.
- A probe settled the conversion semantics instead of guessing: the concat factory uses
  `String.valueOf(Object)` for *all* reference args (`char[]` → `[C@hash`), so reference args map
  to `append(Object)` (see NOTES.md).
- Verified: 21/21 unit tests (new `ConcatEncryptionTest` — javac fixture transcript, hand-built
  `([C)` / `(Ljava/lang/Object;)` sites, interface site), three rigs green (hardening now carries
  the `concat-recipe-marker` plaintext marker and prints a concat line), determinism
  byte-identical across runs.
- v3.2: `IntObfuscation` now also rewrites `FCONST_*`/`DCONST_*`/`LDC Float|Double` through
  their raw bits + `intBitsToFloat`/`longBitsToDouble`; NaN constants are skipped so payload
  bits stay exact. `IntObfuscationTest` compares floats/doubles bit-for-bit (so `-0.0` cannot
  pass as `0.0`) and asserts NaNs are untouched. Verified: 22/22 tests, three rigs green,
  determinism byte-identical.
- v3.3: `FlowFlatten` rewrites eligible methods into `lookupswitch` state machines: unique
  non-sequential state keys from `Mix`, permuted block order, per-target trampolines,
  `IllegalStateException` default. Dispatchers are per exact entry-frame signature; ambiguous
  frames get private dispatchers. The CFR stress run found a real verifier bug in the first
  version (`Type top ... is not assignable to reference type`) caused by conflating merged
  reference types with long/double second slots; the signature rules were corrected (NOTES.md).
- v3.3 verification: 24/24 unit tests (new `FlowFlattenTest`: differential execution over 19×19
  ints + longs + dense/sparse switches, skip assertions for ternaries and try/catch, determinism);
  `FlowFlatten` adds a `flattened=` line to the hardening rig (1 method flattened there);
  stress on CFR 1302 classes: LoadCheck 0 failures, obfuscated CFR exit 0, 733/733 decompiled
  files byte-identical; hardening output deterministic.
- v3.4 (release `0.3.0`): final acceptance on the release jar — 24/24 tests; three rigs green
  (hello 8 markers absent, legacy8 6, hardening 7 + `flattened=` line, all behaviors identical,
  decompiles produced); CFR stress re-run: 1302 classes, 0 verification failures, obfuscated CFR
  exit 0, 733/733 self-decompile files byte-identical; hardening output deterministic
  (ratio unchanged, new hash includes flattening). README/NOTES/PLAN updated to 0.3.0.
- v3.5 (patch `0.3.1`): exercising the demo playground jars showed `FlowFlatten` was inert in the
  default pipeline (0 methods in demo-math despite 4 eligible ones). Three independent causes:
  1. transforms insert code but never updated `MethodNode.maxStack`; downstream analyzers size
     frames from it and threw `Insufficient maximum stack size`, silently skipping the method.
     `StringEncryptor`, `DeadCode` and `FlowShuffle` now raise the bound by a safe constant when
     they insert; the writer still recomputes the real value with `COMPUTE_MAXS`.
  2. `FlowShuffle` guard splits resumed at arbitrary stack depths. The resume label became a jump
     target carrying values across it, so the whole method failed the empty-stack-entry rule
     (demo-math `fnv1a`/`main`). Guard-split anchors now require `stackSize == 0`, measured with a
     `BasicVerifier` pass; the other techniques are unchanged.
  3. `FlowFlatten` grouped blocks by exact-only frames, so a local assigned on only one path
     merged to `UNINITIALIZED_VALUE` and every join block got a private dispatcher (25 dispatchers
     for `Primes.countBelow`). The merge interpreter now keeps exact reference types but merges
     them through `Context.hierarchy().getCommonSuperClass`, matching the writer's
     `COMPUTE_FRAMES`; with a hierarchy, TOP slots group under `?`. Without a hierarchy (unit
     fixtures) ambiguous merges still get private dispatchers, which is sound but verbose.
- v3.5 verification: 25/25 tests (new "flattening survives DeadCode + FlowShuffle" regression);
  rigs hello/legacy8/hardening green; demo jars behavior-identical (demo-cli 6/40 methods
  flattened, demo-math 4/15: `fnv1a`, `longest`, `main`, `countBelow`); CFR stress 2094/8883
  methods flattened (was 179), LoadCheck 1302/1302 clean, obfuscated CFR exit 0, 733/733
  self-decompile files byte-identical; hardening determinism PASS. `vapor --version` now reports
  0.3.1 (the banner was still hardcoded to 0.2.0).

## Decisions log (short)

- **2026-10-17**: Java 21 + ASM over Rust — JVM bytecode ecosystem lives in Java; ASM owns the
  space. Revisit GraalVM native-image only if a single binary is ever needed.
- **2026-10-17**: Architecture mirrors Aidsfuscator (pipeline + `Transformer`/`Setting<T>` + ASM
  `Remapper`) instead of Skidfuscator's IR/event-bus — right size for v1.
- **2026-10-17**: Dropped Gradle `application` plugin; keep `java` + `shadow` only, Main-Class in
  the shadow manifest.
- **2026-10-17**: `docs/` added for this tracker + NOTES.md (user request: track done/not-done in
  `.md` files).
- **2026-10-18**: v2 transforms stay dependency-free (in-place insertions only) — flattening /
  virtualization / reference-obfuscation are v3, gated on the same failsafe+determinism bar.
- **2026-10-18**: `IntObfuscation` runs last so constants injected by other transforms are
  obfuscated too; noise transforms skip synthetic decryptors via the `Context` artificials
  registry.
- **2026-10-18**: interfaces that get a decryptor and predate class-file v52 are promoted to v52
  (single per-class static method is the only construct that needs it; output targets Java 8+).
