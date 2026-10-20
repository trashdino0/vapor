# Vapor — Working Notes

> Durable notes for future sessions: environment facts, commands, gotchas, and reference-tool
> findings. Everything here is "stuff you'd otherwise have to rediscover".

## Environment & commands

- JDK 21.0.8 is the only JDK (`java` / `javac` / `jar` on PATH). Gradle is **not** installed
  system-wide.
- Gradle 9.8.1 was downloaded to `%TEMP%\opencode\gradle-9.8.1\bin\gradle.bat` (temp — may
  vanish). The project has a wrapper: prefer `.\gradlew.bat <task>`. First wrapper run downloads
  the dist into `~/.gradle/wrapper/dists/` (one-time, ~130 MB).
- Build: `.\gradlew.bat build` → fat jar `build\libs\vapor-0.2.0.jar` (`shadowJar` alone builds
  just the jar).
- Run: `java -jar build\libs\vapor-0.2.0.jar -i in.jar -o out.jar`
- Rig: `.\scripts\rig.ps1 -Sample hello` / `-Sample legacy8` / `-Sample hardening`; `-Build`
  builds first; `-SkipDecompile` for speed. Workspace:
  `%TEMP%\opencode\vapor-rig\<sample>-<release>\` with
  `baseline.out/err`, `obfuscated.out/err`, `mapping.txt`, `dec-cfr-*`, `dec-vf-*`, `dec-*.log`.
  The rig passes `samples/<s>/vapor.json` via `-config` (if present) and verifies `mapping.txt`
  (renamed class present under new path, absent under old). A `samples/<s>/plaintext-markers.txt`
  (if present) triggers a scan for leaked plaintext strings in the obfuscated jar.
- Stress / structural check: `java -Xverify:all tools\LoadCheck.java <jar>` loads every class and
  touches declared members (0 failures expected on obfuscated output). Stress workspace:
  `%TEMP%\opencode\vapor-stress\` (obfuscated CFR runs there).
- Sample-jars playground: drop jars into `jars\input\`, then
  `.\scripts\obfuscate-jars.ps1` (`-Build`, `-Clean`, `-NoDecompile`, `-Config`);
  results — obfuscated jar, `mapping.txt`, CFR decompiles of both sides, behavior check —
  land in `jars\output\<name>\`. Demo jars rebuild via `.\scripts\build-demo-jars.ps1`
  (sources in `jars\src\`).
- Decompilers: `java -jar tools\cfr-0.152.jar <jar> --outputdir <dir>` and
  `java -jar tools\vineflower-1.12.0.jar <jar> <dir>`.
- Reference jars (both unobfuscated; inspect with `javap -p -classpath <jar> <fqcn>`):
  - `obfuscators\v2.10.0\aidsfuscator.jar` — primary architecture blueprint
    (`dev.lvstrng.aidsfuscator`, Main-Class `dev.lvstrng.aidsfuscator.Main`).
  - `obfuscators\skidfuscator.jar` — later-phase ideas (`dev.skidfuscator.obfuscator`,
    Main-Class `dev.skidfuscator.obfuscator.SkidfuscatorMain`).
  - Deep-dive workflow: extract classes to `%TEMP%\opencode\refs\` and decompile with CFR /
    Vineflower. (JEB lane unavailable — MCP client not running, no install found.)
  - **Never copy code from these** (GPL-ecosystem projects) — behavior/design reference only.

## Rig gotchas (read before editing `scripts/rig.ps1`)

- `$ErrorActionPreference = "Stop"` + any native command writing to **stderr** (javac warnings,
  CFR progress chatter) = terminating `NativeCommandError`. Every native call in the rig is
  wrapped in a scoped `$ErrorActionPreference = "Continue"` block with an explicit
  `$LASTEXITCODE` check afterward — keep that pattern for new steps.
- javac obsolete-option warnings suppressed via `-Xlint:-options` (needed for `--release 8`).
- Don't pipe the rig through `Select-Object -First N` when you need its exit code — cutting the
  pipeline early aborts the script (reports exit -1, misleading).
- Behavior PASS = stdout text identical **and** same exit code. stderr is captured to files but
  not compared.
- Windows PowerShell 5.1: load zip support explicitly before using it —
  `Add-Type -AssemblyName System.IO.Compression` + `...System.IO.Compression.FileSystem`
  (the rig does this; the mapping and plaintext checks need it).
- **Never put non-ASCII in `.ps1` files.** Windows PowerShell 5.1 reads BOM-less scripts as
  ANSI; a UTF-8 em dash (`E2 80 94`) decodes to `â€”` where the last byte `0x94` becomes `”`
  (U+201D) — and PowerShell's tokenizer accepts **smart quotes as string delimiters**. One em
  dash inside a string opened a phantom string that swallowed the rest of the file:
  "missing terminator" errors hundreds of lines away while every line looked balanced.
  Keep scripts pure ASCII (scan with a byte check after writing), or save UTF-8 with BOM.
- `Start-Process -PassThru -NoNewWindow` on this PS 5.1 is a trap: (1) the timed
  `WaitForExit(ms)` overload returns before redirected streams are flushed — even the no-arg
  `WaitForExit()` follow-up left `ExitCode` empty here; (2) `-ArgumentList` array elements
  containing spaces are not quoted reliably (`java` received the path split at the first
  space → "Unable to access jarfile"). Use the rig pattern instead: direct
  `& java -jar $path 1> out 2> err` under a scoped `$ErrorActionPreference = "Continue"`,
  then read `$LASTEXITCODE`.

## Sample rules (critical — the rig diffs stdout before/after obfuscation)

- Sample output must be **name-independent**: never print `Class.getName()`, record `toString()`
  (it embeds the class simple name), default `Object.toString()`, or `serialVersionUID` values —
  these legitimately change under renaming and would false-fail the diff. Print values computed
  consistently within the run instead (`recordHashStable`, `recordEq` booleans, etc.).
- `hello` intentionally depends on things surviving obfuscation (now exercised by the rig):
  - `Class.forName("dev.sample.EnglishGreeter")` + `getMethod("greet")` → class + members kept by
    **config** (`samples/hello/vapor.json`, `keep` glob).
  - `Class.forName("dev.sample.FancyGreeter")` → class kept by **`@VaporKeep`** (annotation).
  - `Outer.Inner.get` looked up via `getDeclaredMethod("get")` → member kept by **`@VaporKeep`**
    (the class itself is renamed).
  - `META-INF/services/dev.sample.Plugin` → services file name + contents rewritten
    consistently with the impl class rename (`ResourceFixups`).
- `legacy8` stays records/sealed-free and uses plain `if/else` string compares so javac's
  release-8 output shape (StringBuilder concat, no indy concat) is what gets tested.
- String-concat literals ARE eligible as plaintext markers since v3.1 (the call site is rewritten
  and the recipe entry disappears). Exclusions: literals shorter than `strings.minLength`, and
  recipes carrying `\u0002` bootstrap constants (literal control characters) — those keep their
  `invokedynamic` and stay plaintext.

## Gradle gotchas

- Do **not** re-add the `application` plugin: its `distZip`/`distTar` tasks consume the shadow
  jar's output location without a declared dependency → Gradle validation failure ("implicit
  dependency"). Main-Class is set in `tasks.shadowJar.manifest` instead.
- `archiveClassifier ""` on `shadowJar` makes the fat jar the primary artifact
  (`build\libs\vapor-0.1.0.jar`); the thin jar becomes `...-plain.jar` (rig ignores `plain`).
- `org.gradle.configuration-cache=false` in `gradle.properties` — deliberate, for reliability
  with Shadow; revisit later.

## Pinned versions

ASM 9.11.1 (`asm`, `asm-tree`, `asm-commons`, `asm-analysis`) · Gson 2.14.0 ·
JUnit 6.1.3 (BOM `org.junit:junit-bom` + `junit-jupiter` + `junit-platform-launcher`) ·
Shadow `com.gradleup.shadow` 9.6.1 · Gradle 9.8.1 · Java toolchain 21.

## Engine notes (phase 1)

- `Context` holds everything a pass needs: classes (`JClass` wrappers over ASM `ClassNode`), the
  ordered `JarEntry` list (resources replay verbatim), the lazy `SimpleHierarchy` and the
  `LibraryLoader`. The class map is keyed by *current* name — the renamer calls
  `Context.rekeyClasses()` after committing new names (and drops the hierarchy cache) so
  post-rename `COMPUTE_FRAMES` resolves through the new keys. `Context.replaceEntry()` keeps entry
  order + the resource index consistent when a resource is rewritten.
- `LibraryLoader` resolves headers from `-lib` jars/dirs first, then the JDK via `jrt:/`
  (`/packages/<pkg>` lists modules; only the class header is parsed — nothing is loaded/executed).
  `members(name)` additionally parses declared method/field keys + bare names for the renamer.
- `SimpleHierarchy.getCommonSuperClass` follows the JVMS/ASM rule set; unknown types fall back to
  `java/lang/Object`. Arrays merge by element; class elements must be re-encoded as descriptors
  (`[L<name>;`) — keep the unit test covering that green.
- `VaporClassWriter` overrides `getCommonSuperClass`; `JarWrite` serializes every class with
  `COMPUTE_FRAMES` (frames are recomputed, never copied from input).
- `JarIO.read` uses `ZipFile` (central directory), **not** `ZipInputStream`: the streaming reader
  silently stops at the first local header it dislikes — a .NET `ZipArchive`-rewritten ASM jar
  truncated after entry 1, and vapor then "successfully" wrote a 0-class jar. Found by the
  `jars/` playground; `JarLoad` now warns when an input yields zero classes. (Unlike
  `JarInputStream`, `ZipFile` does not swallow `META-INF/MANIFEST.MF`.)
- Determinism: zip entries get a fixed timestamp (0 → stored as 1980-01-01). Two runs over the same
  input produce byte-identical jars — use that to diff what a transform actually changed.
- Passthrough until later phases: `META-INF/*` (incl. multi-release `versions/` classes),
  `module-info.class` (warns), directory entries. Multi-release class entries are deliberately NOT
  parsed — they would collide with base names in the class map (phase 5 work).
- CLI: `-i -o -lib` + phase 2: `-config <json>`, `-mapping <file>`, `-keep <glob>` (repeatable).

## Phase 2 notes (renaming) — design rules that took thought

- **Global member keys** (`name+descriptor`, owner-independent): any two members with the same key
  rename identically. This makes override chains, bridge methods and interface dispatch consistent
  for free. The price: a key is renamable only if *nothing* requires it to stay — hence three
  anchor layers below.
- **Anchor layer 1 — special names**: `<init>/<clinit>`, `main`, enum `values()/valueOf()` +
  `ACC_ENUM` fields, `serialVersionUID` + serialization hooks, record component field/accessor,
  annotation element methods (annotation use-sites keep element names as plain strings — ASM does
  not remap them), agent entry points, `@VaporKeep`, config globs.
- **Anchor layer 2 — library declarations**: a key declared by a jar class *and* by any transitive
  library supertype (e.g. `toString`, `toString()V`…, `Runnable.run`) stays. Otherwise a rename
  would silently stop satisfying an override contract.
- **Anchor layer 3 — reference resolution** (the subtle one): every method/field reference +
  method handle is resolved through the hierarchy, *including jar supertypes*, and if the nearest
  declaration is a **library** member, the key is anchored. Real bug caught by the rig:
  `invokevirtual Day.name()` (owner = jar enum, `name()` inherited from `java/lang/Enum`) was
  remapped because unrelated jar classes declared `name()` — `NoSuchMethodError` at runtime.
  Call-site remapping is owner-blind by design, so the anchor set must be computed from reference
  *resolution*, not just declarations. Unresolvable chains anchor too (recommend `-lib`).
- **Rename-into-collision safety**: generated member names avoid every name present in the jar or
  in reachable library supertypes, so a rename can never create a new override/hide relationship.
- Class flattening: renamed classes go to the root package; inner classes become
  `outerNew + "$" + suffix` (resolved to a fixed point, works regardless of jar entry order;
  name-prefix fallback if InnerClasses/EnclosingMethod metadata is missing).
- `mapInnerClassName` override keeps `InnerClasses` self-entries coherent; `mapRecordComponentName`
  is identity in v1 (keeping components + accessors + backing fields consistent).
- Lambda indy call sites: the call-site name is the SAM method name (validated by
  `LambdaMetafactory`); rename via the `samMethodType` argument (bsmArgs[0]). Concat recipes,
  record `toString` and switch bootstraps stay untouched.
- Apply phase is compute-then-commit: all remapped `ClassNode` copies are produced first; a failure
  aborts without touching the jar (failsafe mode prints a warning, otherwise rethrows).
- `module-info.class` present ⇒ renaming disabled entirely (warn) — a modular jar's exports would
  lie about renamed packages.

## Phase 3+4 notes (metadata hygiene, string encryption)

- `MetadataHygiene` runs AFTER `Renamer` (sees final names) and BEFORE write-time
  `COMPUTE_FRAMES`. Flags map 1:1 to `VaporConfig.Transformers`; all default true.
- Stripped: LineNumberTable, LVT/LVTT, MethodParameters, generic signatures (class/method/field/
  record component), `SourceFile` → `"a"`, `SourceDebugExtension` cleared. Kept: InnerClasses /
  nest attributes / EnclosingMethod (semantics + consistently remapped), runtime annotations.
- String encryption shape (per `LDC String`, length ≥ `strings.minLength`):
  `ldc <encoded>; ldc <key>; invokestatic <class>.<decryptor>(Ljava/lang/String;I)Ljava/lang/String;`.
  The decryptor (XOR `char[i] ^ (key + i)`, in-place loop, `new String`) is injected once per
  class that had eligible strings; frames are recomputed at write time, no manual frame math.
- **Interface dispatch traps** (both caught by the rig on legacy8, invisible in units):
  - `invokestatic` targeting an interface method MUST carry `itf=true` (InterfaceMethodref);
    otherwise `IncompatibleClassChangeError: ... must be InterfaceMethodref constant`.
  - **private** static interface methods require class-file v53+ — a v52 (Java 8) interface
    cannot declare them. Vapor injects `public static` decryptors into interfaces and
    `private static` into classes so Java 8 targets stay loadable. The decryptor is
    `ACC_SYNTHETIC`, so it reads as tool-generated in javap.
- Not encrypted (documented in `StringEncryptor`): `ConstantValue` field strings, annotation
  element values, and `invokedynamic` bootstrap arguments other than string-concat recipes
  (record `toString`, enum/type-switch bootstraps). Concat recipes are covered since v3.1; rig
  plaintext markers must still avoid the first two categories.
- Decryptor name = first free base-26 name among the class's methods; deterministic. Synthetic
  methods are not in `mapping.txt` (retrace shows them as-is) — acceptable v1.

## Phase 5 notes (watermark)

- Watermark = `private static final String` + `ConstantValue` in the lexicographically smallest
  eligible class name (**pre-rename**), so the choice is deterministic and jar-order independent.
- Injected BEFORE `Renamer` on purpose: the placeholder name (`watermark`, `watermark2`, … if
  taken) is renamed by the normal dictionary flow, so no collision / shadowing is possible and
  kept classes simply keep the readable name. `MappingExport` shows it (`java.lang.String
  watermark -> a`).
- The value is deliberately NOT encrypted (it is a `ConstantValue`, not an `LDC`) — `javap -v`
  prints `ConstantValue: String ...` and `strings`-style scans find it. That is the feature:
  provenance, not secrecy.
- Rig: reads `watermark` from the sample config (`ConvertFrom-Json`) and scans every entry of the
  obfuscated jar; absent value or missing property skips the check.

## v2 notes (hardening transforms)

### Design rules that keep v2 safe (do not violate)

- **In-place insertions only.** No node reordering, no moving values across boundaries — the
  original instruction sequence stays contiguous. That is what makes `COMPUTE_FRAMES` + the
  JVM verifier trivially happy and keeps behavior provable.
- **Everything is budgeted.** `hardening.maxOverhead` (2.0 default) caps growth: each method gets
  `(maxOverhead−1) × instructions-at-transform-start` extra instructions max; injections stop at
  the cap. Budget is computed from a snapshot at transform start (nested transform growth does not
  compound).
- **Every choice is deterministic**: `Mix.of(seed, owner, method)` / `Mix.Rand(xorshift32)` — no
  wall clock, no hash-map iteration order. Byte-identical output across runs is the invariant to
  re-check after any change.
- **Junk must be side-effect-free even if executed** (guards can be miscompiled by *other* tools;
  never make output behaviorally dependent on the noise).
- **Synthetic decryptors are "artificials"**: `Context.addArtificialMethod(owner, name+desc)` —
  `DeadCode` / `FlowShuffle` / `IntObfuscation` skip them, so the decryptor stays minimal and
  fast. Register any future injected method the same way.

### Per-transform specifics

- `DeadCode` insertion points come from ASM's `Analyzer<BasicVerifier>` frames: only frames with
  an empty stack are candidates (any declared type is fine — the locals are untouched).
  Analyzer failure ⇒ skip the method (failsafe). Constructors: nothing before the first
  `INVOKESPECIAL <init>`. Junk blocks use freshly extended `maxLocals` (salt + temporaries).
- `FlowShuffle`: guard splits insert `pred ? jump-over-junk : junk` (valid at any stack depth
  because the junk is self-contained: it neither reads nor extends the live stack); condition
  perturbation rewrites `(cond)` to `(cond ^ 0)`; **jump inversion runs last** because inverting a
  `JumpInsnNode` detaches and re-links nodes — no anchoring may happen after that. `<init>` is
  skipped entirely. The salt local is allocated and initialized **once at the method head**
  (lazily, on first need): the store dominates every use, and a back-edge that jumps to a label
  near the head re-enters after the store — so the salt is always initialized before any use.
- `IntObfuscation` is stack-neutral per push (`v ^ k`, `k`, `IXOR`) so it needs no analysis at
  all; it runs LAST in the pipeline so constants introduced by earlier transforms are obfuscated
  too. Since v3.2 it covers float/double via raw bits (`(bits ^ k)`, `k`, XOR,
  `Float.intBitsToFloat` / `Double.longBitsToDouble`); NaN constants are skipped so their payload
  bits stay exact, while `-0.0`, infinities and denormals round-trip exactly through the bit XOR.
- Polymorphic `StringEncryptor`: 12 shapes — math ∈ {XOR, add, mul, byte-swap} × loop ∈ {forward
  `if_icmpge`, backward `iflt` from `len−1`, equality-terminated}. Shape pick:
  `Mix.of(seed, class, "<decryptor>", 0x5A17)` → `pick % 4`, `(pick >>> 8) % 3`.
  - The multiply shape computes `inv = (key|1)⁻¹ mod 2¹⁶` **at runtime** with 4 unrolled Newton
    iterations (`inv *= 2 − m·inv`; correctness doubles per round: 1→2→4→8→16 bits). The inverse
    depends on the per-call-site key, so it cannot be baked in at class level.
  - All shapes are safe because the JVM's `I2C` masks to 16 bits; only the swap shape needed real
    bit surgery (see bug below).

### Bugs found (both would have shipped latent)

1. **Swap bit pollution** (caught by `StringEncryptorShapesTest`): `((x << 8) | (x >>> 8))`
   ORs `x`'s bits 16+ into bits 8–15 after the `>>> 8` — fine when `x` is a clean 16-bit char
   (encode side), wrong after `x = enc ^ key` with a full-int key (decode side). Fix:
   `((x & 0xFF) << 8) | ((x >>> 8) & 0xFF)`. Lesson: **mask before shifting, not after OR-ing**.
2. **v50 interfaces can't have `static` methods** (caught by obfuscating CFR): `cfr-0.152.jar`
   ships two classes with `major_version 50` interfaces; injecting the `public static` decryptor
   produced `ClassFormatError: Method a in class pl has illegal modifiers: 0x1009`
   (0x1009 = PUBLIC|STATIC|SYNTHETIC). Fix: when a decryptor is injected into an interface with
   `node.version < V1_8`, promote the class to 52. This bug existed latently since v1 (v1 simply
   never met a v50 interface in the samples). Lesson: **never assume input class versions** —
   the stress target (CFR) found it in minutes.

### Test infrastructure added in v2

- `FlowFixture` + `Reload`: differential execution — compile/construct a fixture, transform it
  with ASM, load it in-process with a child-first loader that shadows the parent's copy, then
  compare the transformed behavior with the expected. Reuse this for every future
  control-flow-touching transform.
- `OpaquePredicatesTest` executes each predicate form for edge values + 5000 random cases per
  form (generated host methods, loaded and run) — predicates are *proven*, not eyeballed.
- `StringEncryptorShapesTest` brute-forces class names until all 12 (math × loop) shapes are hit,
  then round-trips each shape end-to-end through real bytecode.
- `tools/LoadCheck.java`: `-Xverify:all` + `Class.forName(name, false, loader)` + touching
  `getDeclaredMethods()/getDeclaredFields()` (forces member-signature resolution) over every
  class entry — a fast structural gate for broken references / illegal modifiers / bad frames.

### Stress-test recipe (the strongest correctness gate we have)

```
java -jar build\libs\vapor-0.2.0.jar -i cfr.jar -o cfr-obf.jar        # full v2 defaults
java -Xverify:all tools\LoadCheck.java cfr-obf.jar                     # expect 0 failures
java -jar cfr-obf.jar <some.jar> --outputdir dec-obf                   # run the obfuscated CFR
java -jar tools\cfr-0.152.jar <some.jar> --outputdir dec-base          # baseline
# diff dec-obf vs dec-base: byte-identical ⇒ freedom from behavioral change
```

Result (2026-10-18): 1302 classes, 1.7 s, 2.16 → 2.92 MB, LoadCheck 0 failures, hardening sample
6/6 identical, **cfr-obf.jar decompiling itself 733/733 identical**. Re-run this after any
transform change; it exercises parsing, regex, hashing, switch tables, exception handling and
huge methods far beyond the samples.

## v3 notes (coverage & control flow)

### v3.1 — concat-recipe encryption (`StringEncryptor`)

- Every `makeConcatWithConstants` call site is replaced in place by: store args to fresh locals
  (reverse descriptor order) → `new StringBuilder()` → per recipe component: literal →
  `LDC enc; push key; invokestatic decryptor` + `append(String)`, argument → load + `append`
  overload → `toString`. The sequence is straight-line, so no original jump can land inside it
  and no frame merge observes the temporaries; `maxLocals` grows by the site's argument width.
  No `BootstrapMethods` surgery is needed: the writer regenerates the table from the remaining
  indy instructions and the unreferenced recipe vanishes.
- Fail-safe skips (site left untouched): recipe contains `\u0002` (literal control characters are
  passed as extra bootstrap constants — measured with javac 21: `"cc\u0001-" + s` becomes recipe
  `\u0002\u0001` plus constant `cc\u0001-`), recipe/descriptor argument counts disagree,
  `bsmArgs.length != 1`, or the recipe is otherwise malformed. `makeConcat` (no recipe) is
  untouched; record `ObjectMethods` and switch bootstraps are never matched.
- **Measured factory semantics** (probe: synthetic `([C)Ljava/lang/String;` call site):
  `StringConcatFactory` converts *every* reference argument with `String.valueOf(Object)`
  semantics — a `char[]` yields `[C@hash`, **not** its characters. Therefore all reference sorts
  map to `StringBuilder.append(Object)`; `append(char[])` (or String/CharSequence special-casing)
  would change behavior for the `[C` case. Primitives use their exact overload: `append(int)` for
  `byte`/`short`, `append(char)` for `char` (decimal vs character!), `append(boolean)` for
  `boolean` (true/false vs 1/0).
- javac 21 pre-converts non-String reference operands with `String.valueOf(Object)` **before** the
  call, so those descriptor types only appear in classes from other compilers (ecj, kotlinc…);
  `ConcatEncryptionTest#rawDescriptorTypesMatchFactorySemantics` builds such sites by hand.
- Verification: `ConcatFixture` (differential transcript: multi-part/folded recipes, every
  primitive width, char[]/Object/String/null, surrogate pair, control-char skip, interface site)
  + plaintext scan of the class file + the hardening rig marker `concat-recipe-marker`. Identity
  hashes of fresh `char[]` must be normalized in differential transcripts (differ per invocation).

### v3.2 — float/double constants (`IntObfuscation`)

- Same stack-neutral pattern as int/long, on raw bits:
  `push (floatToRawIntBits(v) ^ k); push k; ixor; invokestatic Float.intBitsToFloat(I)F`
  (doubles analogous through `Double.longBitsToDouble(J)D`). Covers `FCONST_0/1/2`,
  `DCONST_0/1` and `LDC Float/Double`.
- NaN is skipped deliberately: XORing a NaN's bits changes the payload and `floatToRawIntBits`
  would observe it. Everything else (-0.0, infinities, denormals, `MIN_VALUE`/`MAX_VALUE`)
  round-trips bit-exactly; the test compares raw bits, not `==` (so `-0.0` cannot masquerade as
  `0.0`).
- No new config switch: `transformers.integerObfuscation` now means "numeric constants" (int,
  long, float, double). Naming kept for compatibility; documented in the README.

## Reference-tool findings (from the earlier REA investigation — for Phase 2+)

### Aidsfuscator (primary blueprint, v2.10.0)

- `dev.lvstrng.aidsfuscator`, 201 classes, unobfuscated; bundles ASM + Gson.
- Subsystems: pipeline (`PreProcessorPass → ObfuscationPass → PostProcessorPass`), transformers +
  typed `Setting<T>`, `Context` world model, `IDictionary`/`Mappings`/`MappingExport`,
  `RemapperImpl`, `SimpleHierarchy` + `HierarchyClassWriter`, `IStringInitializer` /
  `IStringDecryptor` + polymorph framework, `Exclusions` enum facets, resource handlers.
- Patterns to mirror:
  - Method renaming unifies override groups via **identity scopes** computed over the hierarchy
    (interfaces + implementors share one name).
  - `HierarchyClassWriter.getCommonSuperClass` backed by `SimpleHierarchy` (jar + libs + JDK) for
    `COMPUTE_FRAMES`.
  - String encryption: per-class injected static decrypt method; XOR over `char[]` with an
    obfuscated key, decoy index/trace locals (the "polymorph" variant randomizes decryptor
    bodies).
  - `mapInvokeDynamicMethodName` handled explicitly so lambda indy bootstrap args stay correct.
  - Record component fields: **not renamed** (ObjectMethods bootstrap would need patching).
  - Enum `values`/`valueOf` and synthetic members excluded from renaming.
  - After class renames: fixups for manifest `Main-Class` and `META-INF/services` (file name and
    contents).
  - Reference config (`obfuscators/v2.10.0/workspace/config.json`) transformers: trim,
    renameFields/Methods/Classes, localNames, lineNumbers, salting, integerEncrypt, encryptStrings,
    flowInts, controlFlowFlatten/Shuffle, cleanDeadCode, referenceObfuscate, hashIntegrity.
- Changelog: `obfuscators/v2.10.0/changelog.txt`.

### Skidfuscator (later-phase ideas only)

- 9.4k classes; MapleIR (`org.mapleir.*`) + vendored SSA VM (`dev.xdark.ssvm`).
- Event-driven transformers with strength strategies, opaque predicates, number generators,
  string generators incl. virtualized, method virtualizer, custom frame computation, picocli CLI,
  failsafe (keep class on transform failure), config migrations.
- Worth borrowing later: failsafe, deterministic seeds, strength strategies. **Not** the
  architecture for v1.

## Phase 2+ implementation gotchas (verify while building)

- ~~Manifest `Main-Class` + `META-INF/services` rewrite~~ — **done (phase 2, `ResourceFixups`)**,
  including agent attributes; nested `versions/` payloads stay passthrough.
- `invokedynamic` (lambdas, records' ObjectMethods, 9+ string concat): implement
  `mapInvokeDynamicMethodName`; ensure `Handle` owners/descriptors inside bootstrap args are
  remapped (ASM `ClassRemapper` covers most; custom indy needs care).
- Records: keep component names in v1; if ever renamed, patch the `RecordComponent` attribute
  and the `ObjectMethods` bootstrap args in the same pass.
- Sealed: remap `PermittedSubclasses` (ASM `visitPermittedSubclass`).
- Enums: anonymous subclasses (`Day$1`…, etc.) referenced via `InnerClasses` + the `Enum` name
  field — renaming them is fine but must stay consistent; `values()`/`valueOf()` are compiler
  magic — never rename; `valueOf` string args are compile-time constants unaffected by renames.
- switch-on-string stays correct with encrypted literals: the hash `case` label is a compile-time
  int; the equality check uses the LDC literal at runtime (decrypt returns the same string).
- Serializable: computed `serialVersionUID` changes with class/field renames (same-process round
  trip unaffected; cross-version compat needs the optional keep-names switch).
- Frames: `COMPUTE_FRAMES` needs common-superclass resolution for library types → load hierarchy
  info from `-lib` jars + the JDK (`jrt:/` filesystem); conservative fallback = `java/lang/Object`.
- Jar packaging: `java -jar` requires the manifest; JarIO deliberately uses `ZipInputStream`
  because `JarInputStream` swallows `MANIFEST.MF`.
