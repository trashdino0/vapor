<#
.SYNOPSIS
  Playground batch runner: obfuscate every jar in jars/input and make the
  result browsable in jars/output.

.DESCRIPTION
  For each input jar (name = base):
    jars/output/<base>/
      <base>-obf.jar            the obfuscated jar
      mapping.txt               ProGuard-style class/member mapping
      decompiled-original/      CFR decompile of the input jar
      decompiled-obfuscated/    CFR decompile of the obfuscated jar
      vapor.log                 obfuscator output
      cfr-*.log                 decompiler output

  When the jar has a Main-Class, the runner also executes both jars and
  compares stdout + exit code -- a quick "did it survive?" signal. A jar
  that blocks (e.g. waits for input) will block the runner; stop with Ctrl+C.

  The per-jar output folder is regenerated on every run. Jars already named
  *-obf.jar are skipped, so you can copy outputs back into the folder safely.

.EXAMPLE
  .\scripts\obfuscate-jars.ps1 -Build        # build vapor, then run everything
.EXAMPLE
  .\scripts\obfuscate-jars.ps1 -Clean        # wipe jars/output first
.EXAMPLE
  .\scripts\obfuscate-jars.ps1 -Config my.json   # apply a vapor config to every jar
#>
param(
    [switch]$Build,          # build the vapor fat jar first
    [switch]$Clean,          # wipe jars/output before running
    [switch]$NoDecompile,    # skip CFR decompiles (faster)
    [string]$Config = ""     # optional vapor JSON config applied to every jar
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$Root = Split-Path -Parent $PSScriptRoot
$JarsDir = Join-Path $Root "jars"
$InDir = Join-Path $JarsDir "input"
$OutDir = Join-Path $JarsDir "output"
$Tools = Join-Path $Root "tools"

function Fail($msg) {
    Write-Host ""
    Write-Host "[jars] FAIL: $msg" -ForegroundColor Red
    exit 1
}

function Step($msg) {
    Write-Host "[jars] $msg" -ForegroundColor Cyan
}

# Native commands chatter on stderr; PS 5.1 turns that into NativeCommandError,
# so each call runs under a scoped Continue and its exit code is checked.
function Run-Cmd {
    param([string]$Exe, [string[]]$Arguments, [string]$Log = "")
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Log) { & $Exe @Arguments *> $Log } else { & $Exe @Arguments }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPref
    }
}

function Get-VaporJar {
    $libs = Join-Path $Root "build\libs"
    if (-not (Test-Path $libs)) { return $null }
    return Get-ChildItem $libs -Filter "vapor-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc|plain' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-ZipClassCount($jarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        return @($zip.Entries | Where-Object { $_.FullName -like '*.class' }).Count
    } finally {
        $zip.Dispose()
    }
}

function Get-MainClass($jarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            $text = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
        foreach ($line in ($text -split "`r?`n")) {
            if ($line -match '^Main-Class:\s*(.+?)\s*$') { return $Matches[1] }
        }
        return $null
    } finally {
        $zip.Dispose()
    }
}

function Invoke-JarCapture($jarPath) {
    $out = Join-Path $env:TEMP ("vapor-run-" + [guid]::NewGuid().ToString("N") + ".out")
    $err = $out -replace '\.out$', '.err'
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # jar stderr is not an error here
    try {
        & java -jar $jarPath 1> $out 2> $err
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPref
    }
    $stdout = ""
    if (Test-Path $out) { $stdout = Get-Content $out -Raw }
    if ($null -eq $stdout) { $stdout = "" }
    Remove-Item $out, $err -Force -ErrorAction SilentlyContinue
    return @{ code = $code; out = $stdout }
}

# --- locate / build vapor ----------------------------------------------------
$vapor = Get-VaporJar
if ($Build -or -not $vapor) {
    Step "building vapor fat jar ..."
    Push-Location $Root
    try {
        $code = Run-Cmd (Join-Path $Root "gradlew.bat") @("shadowJar", "--console=plain", "-q")
    } finally {
        Pop-Location
    }
    if ($code -ne 0) { Fail "gradle build failed" }
    $vapor = Get-VaporJar
}
if (-not $vapor) { Fail "no vapor jar in build\libs (run with -Build)" }

# --- input / output ----------------------------------------------------------
if ($Clean -and (Test-Path $OutDir)) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
if (-not (Test-Path $InDir)) { Fail "jars\input not found" }

$jars = @(Get-ChildItem $InDir -Filter *.jar |
    Where-Object { $_.Name -notmatch '-obf\.jar$' } |
    Sort-Object Name)
if ($jars.Count -eq 0) {
    Fail "no jars in jars\input -- run .\scripts\build-demo-jars.ps1 first, or drop your own jars there"
}

$cfr = Join-Path $Tools "cfr-0.152.jar"
if (-not $NoDecompile -and -not (Test-Path $cfr)) { Fail "missing $cfr" }

$failures = 0
$rows = @()

foreach ($jar in $jars) {
    $base = [System.IO.Path]::GetFileNameWithoutExtension($jar.Name)
    $jdir = Join-Path $OutDir $base
    if (Test-Path $jdir) { Remove-Item $jdir -Recurse -Force }   # fresh results every run
    New-Item -ItemType Directory -Path $jdir -Force | Out-Null

    Step "== $($jar.Name) =="
    $obf = Join-Path $jdir "$base-obf.jar"
    $mapping = Join-Path $jdir "mapping.txt"
    $vaporArgs = @("-jar", $vapor.FullName, "-i", $jar.FullName, "-o", $obf, "-mapping", $mapping)
    if ($Config) { $vaporArgs += @("-config", (Resolve-Path $Config).Path) }

    $code = Run-Cmd "java" $vaporArgs -Log (Join-Path $jdir "vapor.log")
    if ($code -ne 0 -or -not (Test-Path $obf)) {
        Write-Host "[jars] FAIL: obfuscator exited with $code (see vapor.log)" -ForegroundColor Red
        $rows += [pscustomobject]@{ Jar = $base; In = ""; Out = ""; Behavior = "FAIL(obfuscate)"; }
        $failures++
        continue
    }

    $inClasses = Get-ZipClassCount $jar.FullName
    $outClasses = Get-ZipClassCount $obf
    $inSize = [math]::Round($jar.Length / 1KB, 1)
    $outSize = [math]::Round((Get-Item $obf).Length / 1KB, 1)

    # behavior smoke test (runnable jars only)
    $behavior = "SKIP (no Main-Class)"
    $mainClass = Get-MainClass $jar.FullName
    if ($mainClass) {
        $original = Invoke-JarCapture $jar.FullName
        $obfuscated = Invoke-JarCapture $obf
        if ($original.code -ne $obfuscated.code) {
            $behavior = "DIFF (exit $($original.code) vs $($obfuscated.code))"
        } elseif ($original.out -ne $obfuscated.out) {
            $behavior = "DIFF (stdout differs)"
        } else {
            $behavior = "PASS (identical stdout, exit $($original.code))"
        }
    }

    if (-not $NoDecompile) {
        $code = Run-Cmd "java" @("-jar", $cfr, $jar.FullName, "--outputdir", (Join-Path $jdir "decompiled-original"), "--silent", "true") -Log (Join-Path $jdir "cfr-original.log")
        $code = Run-Cmd "java" @("-jar", $cfr, $obf, "--outputdir", (Join-Path $jdir "decompiled-obfuscated"), "--silent", "true") -Log (Join-Path $jdir "cfr-obfuscated.log")
    }

    $color = if ($behavior -like "PASS*") { "Green" } elseif ($behavior -like "SKIP*") { "Gray" } else { "Yellow" }
    Write-Host "[jars] classes $inClasses -> $outClasses, size $inSize -> $outSize KB" -ForegroundColor Gray
    Write-Host "[jars] behavior: $behavior" -ForegroundColor $color

    $rows += [pscustomobject]@{
        Jar      = $base
        In       = "$inClasses cl / $inSize KB"
        Out      = "$outClasses cl / $outSize KB"
        Behavior = $behavior
    }
}

# --- summary -----------------------------------------------------------------
Write-Host ""
Write-Host "[jars] summary" -ForegroundColor Cyan
$rows | Format-Table -AutoSize | Out-String | ForEach-Object { Write-Host $_.TrimEnd() }
Write-Host "[jars] output: $OutDir" -ForegroundColor Cyan
Write-Host "       per jar: obfuscated jar, mapping.txt, decompiled-original/, decompiled-obfuscated/"

if ($failures -gt 0) {
    Write-Host "[jars] $failures jar(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host "[jars] done" -ForegroundColor Green
