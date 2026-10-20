# Vapor — Java Obfuscator: Project Plan

**Goal**: A general-purpose Java obfuscator. Input: jars containing Java 8–21 class files.
Output: obfuscated jar + mapping file. Runs on the local JDK 21.

**v1 scope**: renaming + metadata hygiene + string encryption, built on a correctness-first
test rig. Hardening passes (control flow, predicates, virtualization) are explicitly deferred.

**v2 scope** (current, 2026-10-18): dependency-free hardening — opaque predicates + decoy
branches, integer/long constant obfuscation, control-flow noise (split / invert / perturb),
polymorphic string decryptors — verified against the same rigs plus a real-jar stress target.

## Decisions (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Language / stack | **Java 21** + **ASM 9.11** (tree API) | Obfuscation *is* bytecode manipulation; ASM is what both reference tools use |
| Build | Gradle (Kotlin DSL), Shadow plugin for fat-jar | Runnable `vapor.jar` with zero external classpath setup |
| Config format | JSON via Gson | Matches Aidsfuscator convention (familiar, simple) |
| CLI | Hand-rolled arg parser (~100 lines, zero deps) | `-i -o -lib -config -mapping -keep` |
| Architecture | **Mirror Aidsfuscator**: pipeline + Transformer/Setting framework + ASM `Remapper` | Proven minimal design; Skidfuscator's event-bus + IR is overkill for v1 |
| Deep-dive references | CFR / Vineflower on temp extracts (`%TEMP%\opencode\refs\`) | Both reference jars are unobfuscated; use as behavior reference only, **never copy code** (GPL-ecosystem projects) |

## Reference tools in this repo

- `obfuscators/v2.10.0/aidsfuscator.jar` — primary architecture blueprint (`dev.lvstrng.aidsfuscator`, unobfuscated).
  Pipeline: `PreProcessorPass → ObfuscationPass → PostProcessorPass`; `Transformer` + typed `Setting<T>` + `TransformerOrder`;
  `Context` world model (classes / artificials / libraries / excluded); `IDictionary` naming; `Mappings` enum + ProGuard-style `MappingExport`;
  `RemapperImpl extends org.objectweb.asm.commons.Remapper`; `SimpleHierarchy` + `HierarchyClassWriter.getCommonSuperClass`;
  string encryption via `IStringInitializer`×`IStringDecryptor` (XOR w/ obfuscated index/trace vars); `polymorph/*` randomized decryptor bodies;
  `Exclusions` enum per transformer facet + `StringFilter` glob→regex.
- `obfuscators/skidfuscator.jar` — ideas for later phases (`dev.skidfuscator.obfuscator`, unobfuscated, bundles MapleIR + `dev.xdark.ssvm`).
  Event-driven transformers w/ strength strategies, opaque predicates, number hashing, string generators (incl. virtualized),
  method virtualizer, custom frame computation, picocli CLI, failsafe, config migrations. **Non-goals for v1.**
- `tools/cfr-0.152.jar`, `tools/vineflower-1.12.0.jar` — decompilers used by the test rig.

## Project layout

```
Java Obfuscator/
├── build.gradle.kts / settings.gradle.kts     # Gradle (Kotlin DSL), Java 21 toolchain
├── src/main/java/dev/vapor/
│   ├── Main.java                              # CLI: -i -o -lib -config -mapping -keep
│   ├── config/          # VaporConfig, TransformerConfig, JSON load/save (Gson)
│   ├── context/         # Context (world model), asm/RemapperImpl, asm/VaporClassWriter,
│   │                    # hierarchy/{IHierarchy, SimpleHierarchy}
│   ├── tree/            # JClass wrapper over ASM ClassNode (JMethod/JField in phase 2)
│   ├── pipeline/        # IPass; preprocess: JarLoad (+libs, jrt: JDK classes); postprocess: JarWrite, MappingExport
│   ├── transform/       # Transformer, Setting<T>, TransformerOrder
│   │   └── impl/rename/     ClassRename, MethodRename, FieldRename
│   │   └── impl/strip/      LineNumbers, LocalVariables, SourceFile, MethodParameters, Signature
│   │   └── impl/strings/    StringEncrypt + IStringDecryptor/IStringInitializer + Default/Poly decryptors
│   ├── exclude/         # Exclusions facets, StringFilter (glob→regex), presets, @VaporKeep stripping
│   ├── naming/          # IDictionary, Dictionary (a..z, aa..), Mappings(CLASS/FIELD/METHOD), Mapping
│   ├── file/            # JarIO (raw archive read/write, deterministic timestamps)
│   └── resource/        # ManifestHandler (rewrite Main-Class), ServicesHandler (META-INF/services)
├── samples/hello/       # release 21: records, sealed, indy concat, reflection, ServiceLoader…
├── samples/legacy8/     # release 8: StringBuilder concat, no records/sealed
├── scripts/rig.ps1      # the test rig
└── docs/                # PROGRESS.md (phase tracker), NOTES.md (working notes)
```

## Test rig (built first, runs after every change)

`scripts/rig.ps1`: compile sample → package jar → **run baseline, capture stdout/exit code** →
obfuscate → **run obfuscated, diff** → decompile both with CFR and Vineflower into
`%TEMP%\opencode\vapor-rig\` → report.

Sample app deliberately includes: lambdas (invokedynamic), enums, records (17+), sealed (17+),
inner/anonymous classes, 3-deep overridden-method hierarchy, `Serializable`, `ServiceLoader` +
services file, reflection usage with a keep-rule, string concat, `switch`-on-string.

## Phases (each ends at a green rig = commit boundary)

### Phase 0 — Bootstrap
Gradle skeleton, ASM/Gson wired, sample app + rig working with a pass-through round-trip.
*Acceptance: identical behavior after round-trip.*

### Phase 1 — Core engine
`Context`, jar I/O preserving resources, `SimpleHierarchy` (jar classes + `libs/` + JDK via `jrt:`
filesystem), `VaporClassWriter.getCommonSuperClass`, write with `COMPUTE_FRAMES`,
pipeline (preprocess → transforms → postprocess).
*Acceptance: rig green on round-trip; unit tests for hierarchy (interfaces, arrays, JDK types).*

### Phase 2 — Renaming
- Dictionary (`a…z`, `aa…`; shuffle; aggressive-overload option).
- `Mappings` + `RemapperImpl` + ASM `ClassRemapper` across the jar.
- Override **identity scopes** via hierarchy (Aidsfuscator `identityScope` approach); field-hiding scopes.
- Keep: `<init>/<clinit>`, `main`, enum `values/valueOf`; native/bridge handling.
- **Record component fields**: skipped in v1 (`preserveRecordNames` approach — renaming requires
  patching ObjectMethods bootstrap args).
- Serializable field names: optional keep.
- Resource fixups: manifest `Main-Class`, `META-INF/services`, `SourceFile`.
- Keep rules: config globs + `@VaporKeep` annotation (stripped afterward); ProGuard-style `mapping.txt` export.
*Acceptance: rig green; `mapping.txt` reverses names; reflection sample works when kept, documented failure when not.*

### Phase 3 — Metadata hygiene
Strip line numbers, local variable tables, `MethodParameters`; rewrite `SourceFile`; strip `Signature`
on renamed classes (generics leak). Keep `InnerClasses`/nest attributes (required for correctness).
*Acceptance: CFR/Vineflower output visibly degraded; rig still green.*

### Phase 4 — String encryption
- Replace `LDC` strings ≥ `minLength` with `new char[]{…}` + obfuscated-key args → per-class injected
  private static decryptor (XOR loop, obfuscated index/trace vars, mirroring `DefaultStringDecryptor`).
- Insertion placement validated via lightweight stack/frame analysis (simplified `SimpleInterpreter` idea).
- Excludes annotation values and excluded members.
- 4b (stretch): structurally randomized "polymorphic" decryptor bodies (à la `PolymorphMethodContext`).
*Acceptance: `grep` finds no plaintext strings in obfuscated jar (except deliberate skips); rig green;
decompiled call sites show opaque `decrypt()` calls.*

### Phase 5 — Hardening & polish
- Failsafe mode: on transform exception → log, keep class unmodified, continue (Skidfuscator `Failsafe` idea).
- Multi-release jar passthrough (obfuscate base, copy `versions/`).
- `module-info.class` detection → implicit keep + warning.
- Watermark config; deterministic output mode (fixed seed).
- README + config reference.
- Optional: obfuscate the same sample with Aidsfuscator/Skidfuscator and compare decompiled quality.

## Vapor v2 — Hardening ✅ (planned 2026-10-18, delivered 2026-10-18)

**Goal**: make decompiled output materially harder to read without leaving the correctness
envelope v1 established: deterministic output, Java 8–21 compatibility, failsafe apply,
relentless rig verification.

**Delivered**: all four transforms + the stress harness, per the phases below; 18/18 unit tests,
three rigs green, CFR self-hosting stress clean (LoadCheck 0 failures over 1302 classes;
obfuscated CFR decompiles its own obfuscated jar 733/733 byte-identically). Deviations from the
plan noted inline; open v3 items below reflect what remains.

### Deliverables

| # | Feature | Transformer | Default |
|---|---|---|---|
| V1 | Opaque predicates + decoy (dead) branches | `DeadCode` | on |
| V2 | Integer / long constant obfuscation | `IntObfuscation` | on |
| V3 | Control-flow noise (split, invert, perturb) | `FlowShuffle` | on |
| V4 | Polymorphic string decryptors ("salting") | `StringEncryptor` upgrade | on |

### Design rules (every v2 transform)

- **Dependency-free**: no block reordering, no moving code across value-producing boundaries —
  only in-place rewrites and insertions. Output stays valid under `COMPUTE_FRAMES`.
- **Constructor safety**: `<init>` is only touched after the `super()` / `this()` call;
  `FlowShuffle` skips constructors entirely. Synthetic decryptors are registered as
  "artificials" and skipped by `DeadCode` / `FlowShuffle`.
- **Budgets**: per-method addition cap via `hardening.maxOverhead` (added instructions ≤
  `(maxOverhead-1) × instructions present when the transform starts`); oversized methods skipped.
- **Safe decoys**: injected junk is side-effect-free even if it executed (arithmetic + local
  stores only) — a predicate bug degrades performance, never behavior.
- **Determinism**: every choice derives from `seed` + class + method + per-method counter
  (same mixer style as v1).
- **Predicate invariants are unit-tested by execution** — generated bytecode is loaded and
  called over edge values (MIN/MAX/−1/0/1 + a seeded random set), not just reasoned about.

### Phases (each ends at a green rig)

**V0 — scaffolding** ✅: version `0.2.0`; new `hardening` sample (loops, switches, shapes/constants,
inner classes, interface — release 21) wired into the rig with markers + watermark; config
additions below.

**V1 — `OpaquePredicates` + `DeadCode`** ✅: `if (alwaysTrue) goto; <junk>` guards inserted at
stack-empty points found with ASM's `Analyzer<BasicVerifier>` (not at fixed positions — every
insertion point is stack-verified). Delivered predicate forms over a per-method salt:
`(x*x + x) & 1 == 0`, `(x | 1) * (x | 1) >= 0`, `(x ^ (x >> 31)) < 0` (all overflow-safe for every
int; unit-tested by execution over edge values + 5000 random cases per form). Junk = arithmetic
chains + local stores + self-contained decoys, side-effect-free by construction. Constructors get
junk only after the first `INVOKESPECIAL <init>`.

**V2 — `IntObfuscation`** ✅: `ICONST_*` / `LCONST_*` / `BIPUSH` / `SIPUSH` / `LDC int|long` →
`push (v ^ k); push k; IXOR`/`LXOR`; everything that is not a stack push (switch keys/bounds,
`iinc` operands, array dims, bootstrap args) is untouched by construction. Runs **last** so
constants injected by `DeadCode` / `FlowShuffle` are obfuscated too.

**V3 — `FlowShuffle`** ✅ (dependency-free control-flow noise; full flattening is v3 territory):
- guard splits at stack-empty anchors (`if (opaqueTrue) goto C; <decoy>; C:`, decoy self-contained
  and stack-balanced);
- invert selected conditional jumps (`if x L` → `if !x C; goto L; C:`), **last** in the pass
  because inversion detaches nodes;
- perturb int conditions with an opaque zero (`cond` → `cond ^ 0` synthesized at runtime) for all
  `IFxx` / `IF_ICMPxx` forms; `IFNULL` / `IF_ACMPxx` forms get inversion only.

**V4 — polymorphic string decryptors** ✅: per class, deterministically pick a decryptor shape —
math (XOR+index / ADD+index / multiply mod 2¹⁶ with **runtime** modular inverse via 4 unrolled
Newton iterations / byte-swap+XOR) × loop form (forward `if_icmpge` / backward `iflt` from
`len−1` / equality-terminated) — 12 bodies, same descriptor `(Ljava/lang/String;I)Ljava/lang/String;`;
`strings.polymorphic=false` restores v1. All 12 shapes are round-tripped by an executable unit test.
Bug found here: the naive swap `(x << 8) | (x >>> 8)` pollutes bits 8–15 when `x` has high bits —
byte-mask instead.

**V5 — stress + acceptance** ✅:
- `tools/LoadCheck.java` harness: loads every class of a jar (`Class.forName(name, false, loader)`)
  under `-Xverify:all` and touches declared members — verifies, does not initialize.
- Stress target: `tools/cfr-0.152.jar` (1302 classes, no module-info) obfuscated with full v2
  defaults → **LoadCheck 0 failures** → obfuscated CFR decompiles a sample (6/6) and **its own
  obfuscated jar (733/733)** byte-identically to pristine CFR. The stress run caught a real bug:
  two **class-file v50 interfaces** in CFR cannot host a `static` decryptor → interfaces are now
  promoted to v52 when a decryptor is injected.
- Rigs: all three samples green — behavior identical, markers absent, watermark present,
  determinism byte-identical with v2 enabled.
- Docs: README (v2 features/config/limitations), PROGRESS, NOTES (design rules + bug lessons);
  before/after decompile notes in PROGRESS.

### Config additions

```json
"transformers": { "deadCode": true, "integerObfuscation": true, "flowShuffle": true },
"strings": { "polymorphic": true },
"hardening": { "maxOverhead": 2.0, "deadBranchesPerMethod": 3, "flowSplitsPerMethod": 6 }
```

### v3 candidates (explicitly deferred)

Full block flattening (dispatcher, needs CFG permutation + value analysis), reference/proxy
obfuscation, integrity verification (hash checks), class/field salting bloat, anti-debug,
virtualization.

## Vapor v3 — Coverage & Control Flow (v3.1–v3.3 shipped 2026-10-19; v3.4 acceptance pending)

**Goal**: close the two visible coverage gaps (indy string-concat literals staying plaintext,
float/double constants untouched) and add the first real control-flow transform (bounded
flattening) — keeping the v1/v2 envelope: deterministic, failsafe, budgeted, verified by tests,
rigs and the self-hosting stress run. Commit-per-phase is back on (v3.0 re-established git).

### Deliverables

| # | Feature | Transformer | Default |
|---|---|---|---|
| v3.1 | Encrypt `invokedynamic` string-concat literals | `StringEncryptor` upgrade | on ✅ |
| v3.2 | Float / double constant obfuscation | `IntObfuscation` upgrade | on ✅ |
| v3.3 | Bounded control-flow flattening (dispatcher + block permutation) | `FlowFlatten` | on ✅ |

### Phases (each ends at a green rig + a commit)

**v3.0 — hygiene**: `git init`, `.gitignore` (playground outputs + third-party jars stay
untracked), baseline commit; commit-per-phase from here on.

**v3.1 — concat literals**: for every `invokedynamic` call site whose bootstrap is
`StringConcatFactory.makeConcatWithConstants`, replace the call with an explicit `StringBuilder`
chain:

1. pop the call-site arguments into fresh locals (reverse order; slot types from the descriptor:
   Z/B/C/S/I → `ISTORE`, J/F/D, references → `ASTORE`);
2. build: `new StringBuilder()`, then per recipe component: literal → `LDC enc; push key;
   invokestatic <decryptor>` + `append(String)` (literals below `strings.minLength` stay plain
   `LDC`); dynamic arg → load local + the `append` overload matching the descriptor type
   (primitives by width; `String`/`CharSequence`/`char[]`/`Object` exactly);
3. `toString()`.

Failsafe: skip the call site if the recipe contains `\u0002` non-string constants, references an
argument index outside the descriptor, or anything else doesn't parse. Record `ObjectMethods`
bootstraps are never touched; `makeConcat` (no recipe) stays untouched. The writer drops
unreferenced `BootstrapMethods` entries, so the recipe text leaves the class file entirely.

**v3.2 — float/double constants**: same bits-XOR split as int/long —
`push (bits ^ k); push k; IXOR; invokestatic Float.intBitsToFloat(I)F` /
`Double.longBitsToDouble(J)D`. Covers `FCONST_*` / `DCONST_*` / `LDC Float|Double`; exact bits
are preserved for all finite values and infinities. NaN constants are **skipped** so their raw
payload bits stay bit-exact (XOR would alter the payload while still producing a NaN).

**v3.3 — bounded flattening**: per method (skipped: `<init>`, artificials, anything with
try/catch (this also covers `synchronized`), JSR/RET, methods whose block entries ever carry a
non-empty stack per ASM `Analyzer`, > `hardening.flattenMaxBlocks` blocks, > 2000 instructions,
straight-line methods):

```
state = <start>;
loop:  switch (state) { case i: block_i ... }
```

each block keeps its original body; its terminator becomes a state assignment + `goto loop`
(conditionals branch to two tiny assignment trampolines); RETURN/ATHROW stay as-is. Physical
block order in the switch body is permuted by a seeded `Mix.Rand`, state numbers follow the
permutation; the default switch target throws `IllegalStateException` (defense in depth).
Frames are recomputed at write time; behavior is proven by the differential-execution harness
(extended fixture), the three rigs and the CFR self-hosting stress run.

**v3.4 — stress + docs**: full acceptance (tests, rigs ×3 incl. decompile, determinism,
LoadCheck + CFR self-decompile on the new pipeline), README/PROGRESS/NOTES updates.

### Config additions

```json
"transformers": { "flowFlatten": true },
"hardening":    { "flattenMaxBlocks": 128 }
```

### v3 candidates (still deferred)

Salting (decoy class/member bloat), reference/proxy obfuscation, anti-tamper/hash checks,
`module-info`-aware renaming for modular jars, `ConstantValue` / annotation-value encryption,
virtualization/native.

## Non-goals for v1/v2

Full control-flow flattening, salting/hash-integrity, reference/proxy obfuscation, anti-debug,
virtualization/native, Fabric/Mixin integration, GUI, Gradle plugin.
(Design keeps doors open: transformer framework + `artificials` map in `Context` are what those
passes need. Opaque predicates and number obfuscation were v1 non-goals and shipped in v2.)

## Top risks

| Risk | Mitigation |
|---|---|
| Frame recomputation with incomplete hierarchy | JDK via `jrt:` + `libs/`; conservative fallback to `java/lang/Object`; rig catches verifier failures immediately |
| `invokedynamic` (lambdas) renaming | Implement `mapInvokeDynamicMethodName` explicitly (as Skidfuscator does); lambda-heavy sample in rig |
| Reflection/serialization breakage | Inherent — keep rules, `@VaporKeep`, docs; defaults favor correctness |
| Record/ObjectMethods bootstrap coupling | v1 preserves record component names (Aidsfuscator's proven approach) |
| **Old class-file versions in real-world jars** (v2 lesson: CFR ships v50 interfaces that reject `static` methods) | Never assume the version — inject only constructs legal at the class's version or promote deliberately (interface decryptor → v52); the CFR stress recipe re-run after transform changes |
| **Unusual archive shapes** (v2.x lesson: a .NET-rewritten zip truncated `ZipInputStream` after one entry → silent 0-class output) | Read jars through the central directory (`ZipFile`); warn when zero classes load; the `jars/` playground exercises third-party archive writers |

## Dependencies

- ASM 9.11.1 (`asm`, `asm-tree`, `asm-commons`, `asm-analysis`)
- Gson 2.14.0
- Gradle 9.8.1 + Shadow plugin (`com.gradleup.shadow`) 9.6.1 for the fat jar
- JUnit 6.1.3 (unit tests); rig is the integration test
