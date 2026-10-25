# Vapor â€” Working Notes

> Durable notes for future sessions: environment facts, commands, gotchas, and reference-tool
> findings. Everything here is "stuff you'd otherwise have to rediscover".

## Environment & commands

- JDK 21.0.8 is the only JDK (`java` / `javac` / `jar` on PATH). Gradle is **not** installed
  system-wide.
- Gradle 9.8.1 was downloaded to `%TEMP%\opencode\gradle-9.8.1\bin\gradle.bat` (temp â€” may
  vanish). The project has a wrapper: prefer `.\gradlew.bat <task>`. First wrapper run downloads
  the dist into `~/.gradle/wrapper/dists/` (one-time, ~130 MB).
- Build: `.\gradlew.bat build` â†’ fat jar `build\libs\vapor-0.5.0.jar` (`shadowJar` alone builds
  just the jar).
- Run: `java -jar build\libs\vapor-0.5.0.jar -i in.jar -o out.jar`
- GUI: `java -jar build\libs\vapor-0.5.0.jar --gui` (or no arguments; `.\gradlew.bat gui` runs
  from classes). Swing is part of the JDK â€” no new dependency and nothing extra to package.
  Headless environments refuse to launch it and print the CLI help instead.
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
  results â€” obfuscated jar, `mapping.txt`, CFR decompiles of both sides, behavior check â€”
  land in `jars\output\<name>\`. Demo jars rebuild via `.\scripts\build-demo-jars.ps1`
  (sources in `jars\src\`).
- Decompilers: `java -jar tools\cfr-0.152.jar <jar> --outputdir <dir>` and
  `java -jar tools\vineflower-1.12.0.jar <jar> <dir>`.
- Reference jars (both unobfuscated; inspect with `javap -p -classpath <jar> <fqcn>`):
  - `obfuscators\v2.10.0\aidsfuscator.jar` â€” primary architecture blueprint
    (`dev.lvstrng.aidsfuscator`, Main-Class `dev.lvstrng.aidsfuscator.Main`).
  - `obfuscators\skidfuscator.jar` â€” later-phase ideas (`dev.skidfuscator.obfuscator`,
    Main-Class `dev.skidfuscator.obfuscator.SkidfuscatorMain`).
  - Deep-dive workflow: extract classes to `%TEMP%\opencode\refs\` and decompile with CFR /
    Vineflower. (JEB lane unavailable â€” MCP client not running, no install found.)
  - **Never copy code from these** (GPL-ecosystem projects) â€” behavior/design reference only.

## Rig gotchas (read before editing `scripts/rig.ps1`)

- `$ErrorActionPreference = "Stop"` + any native command writing to **stderr** (javac warnings,
  CFR progress chatter) = terminating `NativeCommandError`. Every native call in the rig is
  wrapped in a scoped `$ErrorActionPreference = "Continue"` block with an explicit
  `$LASTEXITCODE` check afterward â€” keep that pattern for new steps.
- javac obsolete-option warnings suppressed via `-Xlint:-options` (needed for `--release 8`).
- Don't pipe the rig through `Select-Object -First N` when you need its exit code â€” cutting the
  pipeline early aborts the script (reports exit -1, misleading).
- Behavior PASS = stdout text identical **and** same exit code. stderr is captured to files but
  not compared.
- Windows PowerShell 5.1: load zip support explicitly before using it â€”
  `Add-Type -AssemblyName System.IO.Compression` + `...System.IO.Compression.FileSystem`
  (the rig does this; the mapping and plaintext checks need it).
- **Never put non-ASCII in `.ps1` files.** Windows PowerShell 5.1 reads BOM-less scripts as
  ANSI; a UTF-8 em dash (`E2 80 94`) decodes to `Ã¢â‚¬â€` where the last byte `0x94` becomes `â€`
  (U+201D) â€” and PowerShell's tokenizer accepts **smart quotes as string delimiters**. One em
  dash inside a string opened a phantom string that swallowed the rest of the file:
  "missing terminator" errors hundreds of lines away while every line looked balanced.
  Keep scripts pure ASCII (scan with a byte check after writing), or save UTF-8 with BOM.
- `Start-Process -PassThru -NoNewWindow` on this PS 5.1 is a trap: (1) the timed
  `WaitForExit(ms)` overload returns before redirected streams are flushed â€” even the no-arg
  `WaitForExit()` follow-up left `ExitCode` empty here; (2) `-ArgumentList` array elements
  containing spaces are not quoted reliably (`java` received the path split at the first
  space â†’ "Unable to access jarfile"). Use the rig pattern instead: direct
  `& java -jar $path 1> out 2> err` under a scoped `$ErrorActionPreference = "Continue"`,
  then read `$LASTEXITCODE`.

## Sample rules (critical â€” the rig diffs stdout before/after obfuscation)

- Sample output must be **name-independent**: never print `Class.getName()`, record `toString()`
  (it embeds the class simple name), default `Object.toString()`, or `serialVersionUID` values â€”
  these legitimately change under renaming and would false-fail the diff. Print values computed
  consistently within the run instead (`recordHashStable`, `recordEq` booleans, etc.).
- `hello` intentionally depends on things surviving obfuscation (now exercised by the rig):
  - `Class.forName("dev.sample.EnglishGreeter")` + `getMethod("greet")` â†’ class + members kept by
    **config** (`samples/hello/vapor.json`, `keep` glob).
  - `Class.forName("dev.sample.FancyGreeter")` â†’ class kept by **`@VaporKeep`** (annotation).
  - `Outer.Inner.get` looked up via `getDeclaredMethod("get")` â†’ member kept by **`@VaporKeep`**
    (the class itself is renamed).
  - `META-INF/services/dev.sample.Plugin` â†’ services file name + contents rewritten
    consistently with the impl class rename (`ResourceFixups`).
- `legacy8` stays records/sealed-free and uses plain `if/else` string compares so javac's
  release-8 output shape (StringBuilder concat, no indy concat) is what gets tested.
- String-concat literals ARE eligible as plaintext markers since v3.1 (the call site is rewritten
  and the recipe entry disappears). Exclusions: literals shorter than `strings.minLength`, and
  recipes carrying `\u0002` bootstrap constants (literal control characters) â€” those keep their
  `invokedynamic` and stay plaintext.

## Gradle gotchas

- Do **not** re-add the `application` plugin: its `distZip`/`distTar` tasks consume the shadow
  jar's output location without a declared dependency â†’ Gradle validation failure ("implicit
  dependency"). Main-Class is set in `tasks.shadowJar.manifest` instead.
- `archiveClassifier ""` on `shadowJar` makes the fat jar the primary artifact
  (`build\libs\vapor-0.1.0.jar`); the thin jar becomes `...-plain.jar` (rig ignores `plain`).
- `org.gradle.configuration-cache=false` in `gradle.properties` â€” deliberate, for reliability
  with Shadow; revisit later.

## Pinned versions

ASM 9.11.1 (`asm`, `asm-tree`, `asm-commons`, `asm-analysis`) Â· Gson 2.14.0 Â·
JUnit 6.1.3 (BOM `org.junit:junit-bom` + `junit-jupiter` + `junit-platform-launcher`) Â·
Shadow `com.gradleup.shadow` 9.6.1 Â· Gradle 9.8.1 Â· Java toolchain 21.

## Engine notes (phase 1)

- `Context` holds everything a pass needs: classes (`JClass` wrappers over ASM `ClassNode`), the
  ordered `JarEntry` list (resources replay verbatim), the lazy `SimpleHierarchy` and the
  `LibraryLoader`. The class map is keyed by *current* name â€” the renamer calls
  `Context.rekeyClasses()` after committing new names (and drops the hierarchy cache) so
  post-rename `COMPUTE_FRAMES` resolves through the new keys. `Context.replaceEntry()` keeps entry
  order + the resource index consistent when a resource is rewritten.
- `LibraryLoader` resolves headers from `-lib` jars/dirs first, then the JDK via `jrt:/`
  (`/packages/<pkg>` lists modules; only the class header is parsed â€” nothing is loaded/executed).
  `members(name)` additionally parses declared method/field keys + bare names for the renamer.
- `SimpleHierarchy.getCommonSuperClass` follows the JVMS/ASM rule set; unknown types fall back to
  `java/lang/Object`. Arrays merge by element; class elements must be re-encoded as descriptors
  (`[L<name>;`) â€” keep the unit test covering that green.
- `VaporClassWriter` overrides `getCommonSuperClass`; `JarWrite` serializes every class with
  `COMPUTE_FRAMES` (frames are recomputed, never copied from input).
- `JarIO.read` uses `ZipFile` (central directory), **not** `ZipInputStream`: the streaming reader
  silently stops at the first local header it dislikes â€” a .NET `ZipArchive`-rewritten ASM jar
  truncated after entry 1, and vapor then "successfully" wrote a 0-class jar. Found by the
  `jars/` playground; `JarLoad` now warns when an input yields zero classes. (Unlike
  `JarInputStream`, `ZipFile` does not swallow `META-INF/MANIFEST.MF`.)
- Determinism: zip entries get a fixed timestamp (0 â†’ stored as 1980-01-01). Two runs over the same
  input produce byte-identical jars â€” use that to diff what a transform actually changed.
- Passthrough until later phases: `META-INF/*` (incl. multi-release `versions/` classes),
  `module-info.class` (warns), directory entries. Multi-release class entries are deliberately NOT
  parsed â€” they would collide with base names in the class map (phase 5 work).
- CLI: `-i -o -lib` + phase 2: `-config <json>`, `-mapping <file>`, `-keep <glob>` (repeatable).

## Phase 2 notes (renaming) â€” design rules that took thought

- **Global member keys** (`name+descriptor`, owner-independent): any two members with the same key
  rename identically. This makes override chains, bridge methods and interface dispatch consistent
  for free. The price: a key is renamable only if *nothing* requires it to stay â€” hence three
  anchor layers below.
- **Anchor layer 1 â€” special names**: `<init>/<clinit>`, `main`, enum `values()/valueOf()` +
  `ACC_ENUM` fields, `serialVersionUID` + serialization hooks, record component field/accessor,
  annotation element methods (annotation use-sites keep element names as plain strings â€” ASM does
  not remap them), agent entry points, `@VaporKeep`, config globs.
- **Anchor layer 2 â€” library declarations**: a key declared by a jar class *and* by any transitive
  library supertype (e.g. `toString`, `toString()V`â€¦, `Runnable.run`) stays. Otherwise a rename
  would silently stop satisfying an override contract.
- **Anchor layer 3 â€” reference resolution** (the subtle one): every method/field reference +
  method handle is resolved through the hierarchy, *including jar supertypes*, and if the nearest
  declaration is a **library** member, the key is anchored. Real bug caught by the rig:
  `invokevirtual Day.name()` (owner = jar enum, `name()` inherited from `java/lang/Enum`) was
  remapped because unrelated jar classes declared `name()` â€” `NoSuchMethodError` at runtime.
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
- `module-info.class` present â‡’ renaming disabled entirely (warn) â€” a modular jar's exports would
  lie about renamed packages.

## Phase 3+4 notes (metadata hygiene, string encryption)

- `MetadataHygiene` runs AFTER `Renamer` (sees final names) and BEFORE write-time
  `COMPUTE_FRAMES`. Flags map 1:1 to `VaporConfig.Transformers`; all default true.
- Stripped: LineNumberTable, LVT/LVTT, MethodParameters, generic signatures (class/method/field/
  record component), `SourceFile` â†’ `"a"`, `SourceDebugExtension` cleared. Kept: InnerClasses /
  nest attributes / EnclosingMethod (semantics + consistently remapped), runtime annotations.
- String encryption shape (per `LDC String`, length â‰¥ `strings.minLength`):
  `ldc <encoded>; ldc <key>; invokestatic <class>.<decryptor>(Ljava/lang/String;I)Ljava/lang/String;`.
  The decryptor (XOR `char[i] ^ (key + i)`, in-place loop, `new String`) is injected once per
  class that had eligible strings; frames are recomputed at write time, no manual frame math.
- **Interface dispatch traps** (both caught by the rig on legacy8, invisible in units):
  - `invokestatic` targeting an interface method MUST carry `itf=true` (InterfaceMethodref);
    otherwise `IncompatibleClassChangeError: ... must be InterfaceMethodref constant`.
  - **private** static interface methods require class-file v53+ â€” a v52 (Java 8) interface
    cannot declare them. Vapor injects `public static` decryptors into interfaces and
    `private static` into classes so Java 8 targets stay loadable. The decryptor is
    `ACC_SYNTHETIC`, so it reads as tool-generated in javap.
- Not encrypted in v3 (v5 pool mode also protects eligible `ConstantValue` strings; annotation
  element values, and `invokedynamic` bootstrap arguments other than string-concat recipes
  (record `toString`, enum/type-switch bootstraps). Concat recipes are covered since v3.1; rig
  plaintext markers must still avoid the first two categories.
- Decryptor name = first free base-26 name among the class's methods; deterministic. Synthetic
  methods are not in `mapping.txt` (retrace shows them as-is) â€” acceptable v1.

## Phase 5 notes (watermark)

- Watermark = `private static final String` + `ConstantValue` in the lexicographically smallest
  eligible class name (**pre-rename**), so the choice is deterministic and jar-order independent.
- Injected BEFORE `Renamer` on purpose: the placeholder name (`watermark`, `watermark2`, â€¦ if
  taken) is renamed by the normal dictionary flow, so no collision / shadowing is possible and
  kept classes simply keep the readable name. `MappingExport` shows it (`java.lang.String
  watermark -> a`).
- The value is deliberately NOT encrypted (it is a `ConstantValue`, not an `LDC`) â€” `javap -v`
  prints `ConstantValue: String ...` and `strings`-style scans find it. That is the feature:
  provenance, not secrecy.
- Rig: reads `watermark` from the sample config (`ConvertFrom-Json`) and scans every entry of the
  obfuscated jar; absent value or missing property skips the check.

## v2 notes (hardening transforms)

### Design rules that keep v2 safe (do not violate)

- **In-place insertions only.** No node reordering, no moving values across boundaries â€” the
  original instruction sequence stays contiguous. That is what makes `COMPUTE_FRAMES` + the
  JVM verifier trivially happy and keeps behavior provable.
- **Everything is budgeted.** `hardening.maxOverhead` (2.0 default) caps growth: each method gets
  `(maxOverheadâˆ’1) Ã— instructions-at-transform-start` extra instructions max; injections stop at
  the cap. Budget is computed from a snapshot at transform start (nested transform growth does not
  compound).
- **Every choice is deterministic**: `Mix.of(seed, owner, method)` / `Mix.Rand(xorshift32)` â€” no
  wall clock, no hash-map iteration order. Byte-identical output across runs is the invariant to
  re-check after any change.
- **Junk must be side-effect-free even if executed** (guards can be miscompiled by *other* tools;
  never make output behaviorally dependent on the noise).
- **Synthetic decryptors are "artificials"**: `Context.addArtificialMethod(owner, name+desc)` â€”
  `DeadCode` / `FlowShuffle` / `IntObfuscation` skip them, so the decryptor stays minimal and
  fast. Register any future injected method the same way.

### Per-transform specifics

- `DeadCode` insertion points come from ASM's `Analyzer<BasicVerifier>` frames: only frames with
  an empty stack are candidates (any declared type is fine â€” the locals are untouched).
  Analyzer failure â‡’ skip the method (failsafe). Constructors: nothing before the first
  `INVOKESPECIAL <init>`. Junk blocks use freshly extended `maxLocals` (salt + temporaries).
- `FlowShuffle`: guard splits insert `pred ? jump-over-junk : junk` (valid at any stack depth
  because the junk is self-contained: it neither reads nor extends the live stack); condition
  perturbation rewrites `(cond)` to `(cond ^ 0)`; **jump inversion runs last** because inverting a
  `JumpInsnNode` detaches and re-links nodes â€” no anchoring may happen after that. `<init>` is
  skipped entirely. The salt local is allocated and initialized **once at the method head**
  (lazily, on first need): the store dominates every use, and a back-edge that jumps to a label
  near the head re-enters after the store â€” so the salt is always initialized before any use.
- `IntObfuscation` is stack-neutral per push (`v ^ k`, `k`, `IXOR`) so it needs no analysis at
  all; it runs LAST in the pipeline so constants introduced by earlier transforms are obfuscated
  too. Since v3.2 it covers float/double via raw bits (`(bits ^ k)`, `k`, XOR,
  `Float.intBitsToFloat` / `Double.longBitsToDouble`); NaN constants are skipped so their payload
  bits stay exact, while `-0.0`, infinities and denormals round-trip exactly through the bit XOR.
- Polymorphic `StringEncryptor`: 12 shapes â€” math âˆˆ {XOR, add, mul, byte-swap} Ã— loop âˆˆ {forward
  `if_icmpge`, backward `iflt` from `lenâˆ’1`, equality-terminated}. Shape pick:
  `Mix.of(seed, class, "<decryptor>", 0x5A17)` â†’ `pick % 4`, `(pick >>> 8) % 3`.
  - The multiply shape computes `inv = (key|1)â»Â¹ mod 2Â¹â¶` **at runtime** with 4 unrolled Newton
    iterations (`inv *= 2 âˆ’ mÂ·inv`; correctness doubles per round: 1â†’2â†’4â†’8â†’16 bits). The inverse
    depends on the per-call-site key, so it cannot be baked in at class level.
  - All shapes are safe because the JVM's `I2C` masks to 16 bits; only the swap shape needed real
    bit surgery (see bug below).

### Bugs found (both would have shipped latent)

1. **Swap bit pollution** (caught by `StringEncryptorShapesTest`): `((x << 8) | (x >>> 8))`
   ORs `x`'s bits 16+ into bits 8â€“15 after the `>>> 8` â€” fine when `x` is a clean 16-bit char
   (encode side), wrong after `x = enc ^ key` with a full-int key (decode side). Fix:
   `((x & 0xFF) << 8) | ((x >>> 8) & 0xFF)`. Lesson: **mask before shifting, not after OR-ing**.
2. **v50 interfaces can't have `static` methods** (caught by obfuscating CFR): `cfr-0.152.jar`
   ships two classes with `major_version 50` interfaces; injecting the `public static` decryptor
   produced `ClassFormatError: Method a in class pl has illegal modifiers: 0x1009`
   (0x1009 = PUBLIC|STATIC|SYNTHETIC). Fix: when a decryptor is injected into an interface with
   `node.version < V1_8`, promote the class to 52. This bug existed latently since v1 (v1 simply
   never met a v50 interface in the samples). Lesson: **never assume input class versions** â€”
   the stress target (CFR) found it in minutes.

### Test infrastructure added in v2

- `FlowFixture` + `Reload`: differential execution â€” compile/construct a fixture, transform it
  with ASM, load it in-process with a child-first loader that shadows the parent's copy, then
  compare the transformed behavior with the expected. Reuse this for every future
  control-flow-touching transform.
- `OpaquePredicatesTest` executes each predicate form for edge values + 5000 random cases per
  form (generated host methods, loaded and run) â€” predicates are *proven*, not eyeballed.
- `StringEncryptorShapesTest` brute-forces class names until all 12 (math Ã— loop) shapes are hit,
  then round-trips each shape end-to-end through real bytecode.
- `tools/LoadCheck.java`: `-Xverify:all` + `Class.forName(name, false, loader)` + touching
  `getDeclaredMethods()/getDeclaredFields()` (forces member-signature resolution) over every
  class entry â€” a fast structural gate for broken references / illegal modifiers / bad frames.

### Stress-test recipe (the strongest correctness gate we have)

```
java -jar build\libs\vapor-0.5.0.jar -i cfr.jar -o cfr-obf.jar        # full v3 defaults
java -Xverify:all tools\LoadCheck.java cfr-obf.jar                     # expect 0 failures
java -jar cfr-obf.jar <some.jar> --outputdir dec-obf                   # run the obfuscated CFR
java -jar tools\cfr-0.152.jar <some.jar> --outputdir dec-base          # baseline
# diff dec-obf vs dec-base: byte-identical â‡’ freedom from behavioral change
```

Result (2026-10-18): 1302 classes, 1.7 s, 2.16 â†’ 2.92 MB, LoadCheck 0 failures, hardening sample
6/6 identical, **cfr-obf.jar decompiling itself 733/733 identical**. Re-run this after any
transform change; it exercises parsing, regex, hashing, switch tables, exception handling and
huge methods far beyond the samples.

Result (2026-10-25, 0.3.2): 1302 classes, 2399/8883 methods flattened (475 stack values spilled),
LoadCheck 1302/1302 clean, obfuscated CFR exit 0, **733/733 self-decompile files byte-identical**,
determinism `F6ED95782E1D3D1F8E5DCDCBADB0B29AAD7D34C68FD9A6F300C6920907D59F55`; 2.16 â†’ 4.52 MB.

## v3 notes (coverage & control flow)

### v3.1 â€” concat-recipe encryption (`StringEncryptor`)

- Every `makeConcatWithConstants` call site is replaced in place by: store args to fresh locals
  (reverse descriptor order) â†’ `new StringBuilder()` â†’ per recipe component: literal â†’
  `LDC enc; push key; invokestatic decryptor` + `append(String)`, argument â†’ load + `append`
  overload â†’ `toString`. The sequence is straight-line, so no original jump can land inside it
  and no frame merge observes the temporaries; `maxLocals` grows by the site's argument width.
  No `BootstrapMethods` surgery is needed: the writer regenerates the table from the remaining
  indy instructions and the unreferenced recipe vanishes.
- Fail-safe skips (site left untouched): recipe contains `\u0002` (literal control characters are
  passed as extra bootstrap constants â€” measured with javac 21: `"cc\u0001-" + s` becomes recipe
  `\u0002\u0001` plus constant `cc\u0001-`), recipe/descriptor argument counts disagree,
  `bsmArgs.length != 1`, or the recipe is otherwise malformed. `makeConcat` (no recipe) is
  untouched; record `ObjectMethods` and switch bootstraps are never matched.
- **Measured factory semantics** (probe: synthetic `([C)Ljava/lang/String;` call site):
  `StringConcatFactory` converts *every* reference argument with `String.valueOf(Object)`
  semantics â€” a `char[]` yields `[C@hash`, **not** its characters. Therefore all reference sorts
  map to `StringBuilder.append(Object)`; `append(char[])` (or String/CharSequence special-casing)
  would change behavior for the `[C` case. Primitives use their exact overload: `append(int)` for
  `byte`/`short`, `append(char)` for `char` (decimal vs character!), `append(boolean)` for
  `boolean` (true/false vs 1/0).
- javac 21 pre-converts non-String reference operands with `String.valueOf(Object)` **before** the
  call, so those descriptor types only appear in classes from other compilers (ecj, kotlincâ€¦);
  `ConcatEncryptionTest#rawDescriptorTypesMatchFactorySemantics` builds such sites by hand.
- Verification: `ConcatFixture` (differential transcript: multi-part/folded recipes, every
  primitive width, char[]/Object/String/null, surrogate pair, control-char skip, interface site)
  + plaintext scan of the class file + the hardening rig marker `concat-recipe-marker`. Identity
  hashes of fresh `char[]` must be normalized in differential transcripts (differ per invocation).

### v3.2 â€” float/double constants (`IntObfuscation`)

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

### v3.3 â€” bounded flattening (`FlowFlatten`)

- Shape: `state = key(start); loop: switch (state) { case key_i: <block_i> ... }`. Each block's
  terminator becomes `push key(successor); istore state; goto loop`; conditionals branch to two
  tiny assignment trampolines; switches are rebuilt with per-target trampolines; RETURN/ATHROW
  stay in place. The default case throws `IllegalStateException`.
- State values are unique, non-sequential ints from `Mix` (not `0..n`); the physical block order
  inside each dispatcher section is shuffled by a seeded `Mix.Rand`; output stays deterministic.
  `IntObfuscation` runs afterwards and XOR-obfuscates the state constants too.
- **One dispatcher per verifier-consistent entry-frame signature** â€” and this is the subtle part. A
  dispatcher is a frame-merge point: the switch merges the locals of *every* path that jumps back
  to it, and each case inherits that merged frame. Blocks may share a dispatcher only when the
  merged frame is exactly what each block verifies against. This was found the hard way: the first
  stress run failed `an.ny(Lad;)Z @1888: aload` with `Type top (current frame, locals[13]) is not
  assignable to reference type`. ASM's `BasicInterpreter` collapses all references to `Object` and
  merges by equality, so the signature comes from a custom interpreter that keeps exact reference
  types *and* merges them via `Context.hierarchy().getCommonSuperClass` â€” the same hierarchy
  `VaporClassWriter` uses for `COMPUTE_FRAMES`. Merged slots therefore keep the type the verifier
  will see instead of degrading to `UNINITIALIZED_VALUE`. Two "no type" cases stay distinct:
  - a Java-null slot / verifier-TOP (never initialized, or incompatible values merged under the
    hierarchy interpreter) â€” unreadable, shared `?` marker, safe to group;
  - an `UNINITIALIZED_VALUE` immediately after a long/double = the wide value's second slot (not
    independently addressable) â€” `W` marker, the preceding descriptor disambiguates.
  Without a hierarchy (unit fixtures pass no `Context`), unequal references stay ambiguous and the
  block gets a **private dispatcher** (`#<block>` key), which merges only its own predecessors and
  therefore reproduces its original entry frame. With the hierarchy path, `countBelow` dropped from
  25 private dispatchers to 6 grouped ones.
- v3 skip rules (superseded in v5 for try/catch and safe constructors): abstract/native, `<init>`, artificials (decryptors), any try/catch (covers
  `synchronized`), JSR/RET, analyzer failure, fewer than 3 blocks, more than
  `hardening.flattenMaxBlocks` blocks, more than 2000 instructions, and (in v3.3â€“v3.5) any block
  entry with a non-empty operand stack. The last rule mattered: values live across branches
  (ternaries, short-circuit expressions) cannot pass through an empty-stack dispatcher. v3.6
  removes the restriction by spilling those values (see below).
- **Pipeline interaction** (found while exercising the demo jars in 0.3.1). Two invariants were
  missing: (1) analyzers size their frames from `MethodNode.maxStack`, but the transforms insert
  code and rely on the writer's `COMPUTE_MAXS`, so by the time `FlowFlatten` ran the stored bound
  was stale and the analyzer threw `Insufficient maximum stack size`, silently skipping whole
  methods. `StringEncryptor`, `DeadCode` and `FlowShuffle` now raise `maxStack` by a safe constant
  when they insert (the writer still recomputes the real value). (2) `FlowShuffle` guard splits
  anchored at arbitrary stack depths; the resume label became a jump target carrying values, which
  disqualified the method under the empty-stack rule. Guard-split anchors now require
  `stackSize == 0` (measured with `BasicVerifier`; if analysis fails, guard splits are skipped and
  the other techniques still run). `-Dvapor.debugFlatten=true` prints the per-method skip reason
  on stderr.
- Reordering invalidates `LocalVariableTable` ranges (first version threw
  `ClassFormatError: Invalid length 65512 in LocalVariableTable` on the fixture), so flattened
  methods drop local-variable tables/annotations. The default pipeline strips them earlier.
- Reference study (REA inventory + CFR): Aidsfuscator filters blocks by analyzer frames, groups by
  frame signature, uses non-sequential switch keys and appends dispatchers at method end;
  Skidfuscator's `DriverTransformer` provides opaque per-method/block predicates used as state
  values. Vapor adopts the frame-grouping insight and randomized keys, not the code.
- Coverage after 0.3.1: 2094 of 8883 methods on the CFR stress jar (was 179 before the fixes
  above; constructors and exception-heavy code dominate the remaining skips; the live-stack skip
  was removed in 0.3.2 â€” see below), 0 verification failures, self-decompilation identical. Demo
  playground: demo-cli 6/40 methods flattened, demo-math 4/15 (`fnv1a`, `longest`, `main`,
  `countBelow`), both behavior-identical; jars grow ~2.2â€“2.8x.

### v3.5 â€” post-release flattening fixes (0.3.1)

- Running the demo jars through the playground exposed that `FlowFlatten` was effectively inert in
  the default pipeline (demo-math: 0 methods flattened although 4 were eligible). The three root
  causes and their fixes are documented in the v3.3 bullets above: stale `maxStack` after inserted
  code, guard-split resume labels at non-empty stack, and equality-only reference merging that
  fragmented methods into private dispatchers.
- Verification: 25/25 unit tests (new pipeline-order regression: `StringEncryptor` + `DeadCode` +
  `FlowShuffle` then `FlowFlatten` must still flatten); three rigs green; both demo jars
  behavior-identical; CFR stress 2094/8883 methods flattened, LoadCheck 0 failures, obfuscated CFR
  exit 0, 733/733 decompiled files byte-identical; hardening determinism PASS. The `--version`
  banner (hardcoded `0.2.0`) now reports `0.3.1`.

### v3.6 â€” spilled entry stacks (0.3.2)

- `FlowFlatten` no longer skips a method because a branch target expects values on the operand
  stack. Every edge into such a block stores the incoming values into fresh locals (top of stack
  first); the block's case reloads them bottom-to-top before its original code. Dispatchers are
  grouped by local **and** entry-stack signature; every edge into a group writes all of that
  group's spill locals, so the frame merge at the loop head keeps their types. Category-2 values
  get two local slots; references, arrays, `null` and uninitialized values use `ASTORE`/`ALOAD`.
  The initial edge is only allowed into a group with an empty entry stack (the JVM starts with an
  empty operand stack).
- **`NEW`/`<init>` tracking**: the analyzer assigns each `NEW` an allocation-site id and models
  the verifier transition at `invokespecial <init>` â€” every copy of that value on the stack and
  in locals becomes the initialized type. Without it, a value stored after construction
  (`new X(...); astore n`) kept the uninitialized marker, valid joins degraded to TOP, and the
  first stress run failed `AnonymousArray.resugarAnonymousArray` at `aload` of a TOP local
  (`Type top ... not assignable to reference type`). A custom `InitAnalyzer`/`InitFrame` performs
  the transition; two different `NEW` sites never merge or share a spill slot. The same pass
  fixed `null` + reference merges (JVMS keeps the reference, not `Object`).
- The stack signature includes the uninitialized marker, so genuinely uninitialized values live
  across a branch (constructor-argument ternaries) are spilled with their allocation identity and
  ASM's `COMPUTE_FRAMES` keeps the matching `uninitialized(offset)` type. `null` stack values are
  handled as references.
- Skip histogram on CFR (8883 methods): **2399 flattened** (was 2094), 475 spilled stack values;
  skips: 4131 fewer-than-3-blocks, 1365 constructors, 449 abstract/native, 395 artificials,
  141 try/catch, 3 instruction count. No analyzer, unreachable-block or unsupported-stack skips
  remain. That is 94% of structurally eligible methods (2399/2543); try/catch is the largest
  remaining flattenable category.
- Diagnostics: `-Dvapor.debugFlatten=true` prints the per-method ok/skip lines; adding
  `-Dvapor.debugFlattenMethod=<name>` dumps that method's block local/stack signatures,
  dispatcher groups and successor edges (this is how the `<init>` frame bug above was found).
- Verification: 25/25 unit tests (new differential fixtures: int/long/reference ternaries, a
  `null`/`String` merge, and a `NEW` live across a ternary in a constructor argument); rigs
  hello/legacy8/hardening green; demo-cli now 7/40 flattened (was 6/40) and demo-math 6/15
  (`stepsFor` and `render` join `fnv1a`, `longest`, `main`, `countBelow`), both
  behavior-identical; CFR stress LoadCheck 1302/1302 clean, obfuscated CFR exit 0, 733/733
  self-decompile files byte-identical; determinism PASS. Version 0.3.2.

### Hardening 2.0 / protection (0.5.0)

- `strings.pool` (default true) stores encoded literals and 32-bit keys in per-class
  `String[]` / `int[]` fields initialized in `<clinit>`; call sites carry only an index.
  `strings.encryptConstantValues` moves eligible static final `String` `ConstantValue`
  fields into the same `<clinit>` setup. The watermark field is deliberately excluded by
  value so `Watermark` remains discoverable; the rig checks this.
- `BlockSplit` (before `FlowFlatten`) adds an opaque entry branch to branch-free methods
  with at least `hardening.blockSplitMinInstructions` instructions. `FlowFlatten` now
  flattens try/catch (handlers copied outside the dispatcher) and safe post-`super()`
  constructor suffixes; see the class Javadoc for the exact proof obligations.
- `protection.encryptClasses` is opt-in and only for executable jars with `Main-Class`:
  class entries get a `VP01` header (CRC32 plus XOR ciphertext) or `VP00` when
  `verifyIntegrity` is off, the loader is embedded unencrypted, and the manifest points
  `Main-Class` at it. `VaporLoader` is patched to class-file v52 and reads classes through
  the system jar resource; it falls back to the parent for library classes without the
  magic. Use `java -jar`, not `java -cp`, for protected output.
- The CRC is tamper evidence, not cryptographic secrecy: the key lives in the loader and
  a process can dump decrypted classes. Signed, multi-release, modular and agent jars are
  deliberately skipped.
- `transformers.virtualize` is an int-only prototype. Eligible static `int`/`void`
  methods are translated into a program array and a generated interpreter class before
  the flow passes; helpers are committed after `FlowFlatten` so they are not flattened,
  then `IntObfuscation` covers their program constants. Objects, arrays, switches,
  exceptions, calls, fields and initializers are rejected. Each helper is a new class.
- `transformers.obfuscateRecords` renames the backing field, accessor and
  `RecordComponent` attribute together and rewrites the `ObjectMethods` name list. It
  requires both method and field renaming; the default is off because JSON/record
  serialization and tests often reflect on component names.
- `transformers.stripAnnotations` is opt-in and drops runtime annotations. Keep
  `@VaporKeep` is processed by the renamer before this pass.
- `protection.verifyIntegrity` defaults to true (CRC). A tampered ciphertext fails class
  loading; see `ClassEncryptionAcceptanceTest.tamperedCiphertextIsRejectedByIntegrityCheck`.
- Java 8 smoke: a protected `legacy8` jar and a protected+virtualized host compiled with
  `--release 8` both ran under Temurin 8 with `-Xverify:all` (exit 0). The loader and generated
  helpers are class-file v52 and use only Java 8 APIs.

### Desktop GUI (0.4.0)

- Shared core: `dev.vapor.Obfuscator.run(Request)` builds the same pipeline for the CLI and GUI.
  `Request` carries input/output/config/mapping/libraries, a `BooleanSupplier cancelled` and an
  optional `PipelineListener`; `Result` returns class/resource counts, sizes and elapsed millis.
  `Main` is now a thin parser + summary printer.
- Progress: `PipelineListener` reports `(stage, passName, index, total)` before/after every
  executed pass. Disabled transformers are excluded from `total`. `total` is
  preprocess + enabled transforms + postprocess.
- Cancellation: `Context` holds the supplier and `checkCancelled()` throws
  `java.util.concurrent.CancellationException`. Checks are at every pipeline stage and inside
  the per-class/per-entry loops (JarLoad, all transformers, Renamer phases, ResourceFixups,
  MappingExport, JarWrite). `Renamer` rethrows `CancellationException` before its failsafe
  `catch (RuntimeException | Error)` so Cancel is not downgraded to "left un-renamed".
- Safe output: the GUI creates `.vapor-out-*` / `.vapor-map-*` temp files in the target
  directories, runs with those paths, then moves them over the real targets with
  `ATOMIC_MOVE` (fallback `REPLACE_EXISTING`). Cancel/failure deletes the temps, so an existing
  output jar is never clobbered.
- Config form: `readConfigFromForm()` builds a fresh `VaporConfig` from every control;
  `applyConfigToForm()` loads one. `VaporConfig.save(Path)` writes pretty JSON via Gson, making
  the GUI's Save config and the CLI's `-config` round-trip the same file.
- Log capture gotcha: the GUI swaps `System.out`/`System.err` to a `LineOutputStream`. Never emit
  a partial line from `flush()`: `PrintStream` flushes formatter chunks, which chops a line into
  tokens. Split only on `\n`; call `finish()` after the run for a trailing partial line. The
  first GUI smoke run exposed exactly this (311 short lines instead of 26).
- Verification: 30/30 unit tests (new `ObfuscatorTest` and `PipelineTest`); all three rigs green;
  offscreen GUI smoke on `demo-cli` produced a loadable 7-class jar + 1.8 KB mapping and a
  normal log; cancellation smoke on the CFR jar produced `=== cancelled ===`, no output and no
  leftover temp files; GUI config load/read matched every field. CFR stress re-run on 0.4.0:
  1302/1302 LoadCheck clean, obfuscated CFR exit 0, output hash unchanged
  (`F6EDâ€¦7D59F55`). The GUI is tested by driving the private form/worker via reflection in a
  throwaway harness; batch tests stay headless.

## Reference-tool findings (from the earlier REA investigation â€” for Phase 2+)

### Aidsfuscator (primary blueprint, v2.10.0)

- `dev.lvstrng.aidsfuscator`, 201 classes, unobfuscated; bundles ASM + Gson.
- Subsystems: pipeline (`PreProcessorPass â†’ ObfuscationPass â†’ PostProcessorPass`), transformers +
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
  - Record component fields: **not renamed in v3** (v5 adds opt-in triplet renaming with ObjectMethods patching).
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

- ~~Manifest `Main-Class` + `META-INF/services` rewrite~~ â€” **done (phase 2, `ResourceFixups`)**,
  including agent attributes; nested `versions/` payloads stay passthrough.
- `invokedynamic` (lambdas, records' ObjectMethods, 9+ string concat): implement
  `mapInvokeDynamicMethodName`; ensure `Handle` owners/descriptors inside bootstrap args are
  remapped (ASM `ClassRemapper` covers most; custom indy needs care).
- Records: keep component names in v1; if ever renamed, patch the `RecordComponent` attribute
  and the `ObjectMethods` bootstrap args in the same pass.
- Sealed: remap `PermittedSubclasses` (ASM `visitPermittedSubclass`).
- Enums: anonymous subclasses (`Day$1`â€¦, etc.) referenced via `InnerClasses` + the `Enum` name
  field â€” renaming them is fine but must stay consistent; `values()`/`valueOf()` are compiler
  magic â€” never rename; `valueOf` string args are compile-time constants unaffected by renames.
- switch-on-string stays correct with encrypted literals: the hash `case` label is a compile-time
  int; the equality check uses the LDC literal at runtime (decrypt returns the same string).
- Serializable: computed `serialVersionUID` changes with class/field renames (same-process round
  trip unaffected; cross-version compat needs the optional keep-names switch).
- Frames: `COMPUTE_FRAMES` needs common-superclass resolution for library types â†’ load hierarchy
  info from `-lib` jars + the JDK (`jrt:/` filesystem); conservative fallback = `java/lang/Object`.
- Jar packaging: `java -jar` requires the manifest; JarIO deliberately uses `ZipInputStream`
  because `JarInputStream` swallows `MANIFEST.MF`.
