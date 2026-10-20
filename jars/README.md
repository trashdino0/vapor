# Sample jars — hands-on playground

A place to *look at* obfuscation results. Drop jars into `input/`, run the runner,
browse `output/`. This is separate from `samples/` (the automated acceptance rig
that diffs behavior for the CI-style checks).

## Layout

```
jars/
├── input/                  jars to obfuscate (samples + your own)
│   ├── demo-cli.jar        compiled from jars/src/demo-cli
│   ├── demo-math.jar       compiled from jars/src/demo-math
│   └── asm-9.11.1.jar      real-world library (org.ow2.asm, BSD-3, from Maven Central;
│                           module-info.class removed from this copy so renaming applies)
├── output/                 generated results, one folder per jar
│   └── <name>/
│       ├── <name>-obf.jar           the obfuscated jar
│       ├── mapping.txt              ProGuard-style class/member mapping
│       ├── decompiled-original/     CFR decompile of the input
│       ├── decompiled-obfuscated/   CFR decompile of the result
│       ├── vapor.log                obfuscator output
│       └── cfr-*.log                decompiler output
└── src/                    sources for the two demo jars
```

## Quick start

```powershell
.\scripts\build-demo-jars.ps1      # (re)compile demo-cli.jar + demo-math.jar into input/
.\scripts\obfuscate-jars.ps1       # obfuscate everything, decompile both sides
# or, fully fresh:  .\scripts\obfuscate-jars.ps1 -Clean
```

Then open, side by side:

- `jars/output/demo-cli/decompiled-original/demo/tasks/Main.java`
- `jars/output/demo-cli/decompiled-obfuscated/<renamed>.java`

and `mapping.txt` to know which class became which single letter.

## Adding your own jars

1. Copy any jar into `jars/input/` (anything with `.class` entries; `*-obf.jar`
   names are skipped so you can copy results back without loops).
2. Run `.\scripts\obfuscate-jars.ps1`.
3. Read `jars/output/<name>/`.

If the jar has a `Main-Class`, the runner also executes the original and the
obfuscated jar and reports whether stdout and the exit code match — your
quick "it still works" signal. Libraries without a `Main-Class` get `SKIP`
(visual inspection still works). A jar that blocks (e.g. waits for input)
blocks the runner with it; stop with Ctrl+C.

Run options: `-Build` (rebuild vapor first), `-Clean` (wipe `output/`),
`-NoDecompile` (faster, jars only), `-Config my.json` (apply a vapor config to
every jar).

## What to look for in the results

- **Renaming** — class files become `a.class`, `b.class`, …; `mapping.txt` reverses it.
  Package structure is flattened to the root.
- **String encryption** — try searching the obfuscated jar for a string you remember
  from the source (`grep -a "internal.example.com" jars/output/demo-cli/demo-cli-obf.jar`):
  literal `LDC` strings are gone. Exceptions you will find: string-concat bootstrap
  recipes, `static final` constant fields, annotation values (documented limitations).
- **Integer obfuscation** — constants in the decompiled output appear as
  `0x519C08D6 ^ 0x3953F42D`-style pairs.
- **Dead code + flow noise** — decoy `if (...)` blocks that never run, perturbed
  conditions (`x != (1 ^ 1)`), inverted jumps (`while (true) { if (...) break; }`).
- **Polymorphic decryptors** — different classes get different decryptor shapes
  (XOR / add / multiply / byte-swap × 3 loop forms); compare two renamed classes.

## Caveats

- Obfuscation is designed for *your application* jars. Obfuscating a third-party
  **library** mainly changes how it reads — reflection-heavy libraries usually need
  keep rules to stay usable at runtime (`-keep`, `@VaporKeep`). The behavior check
  only applies to jars with a `Main-Class`.
- `jars/output/` is regenerated per run (each jar's folder is rebuilt); don't store
  notes there. Use `-Clean` to also drop folders of jars you removed from `input/`.
- This folder is for eyeballing. Correctness is proven by `.\gradlew.bat test` and
  `.\scripts\rig.ps1` — see `docs/PROGRESS.md`.
