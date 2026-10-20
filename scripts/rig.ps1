<#
.SYNOPSIS
  Vapor test rig: compile a sample, capture baseline output, obfuscate,
  run the obfuscated jar, diff behavior, decompile both with CFR + Vineflower.

.DESCRIPTION
  The rig is Vapor's acceptance test. A phase is "green" when:
    1. the sample compiles and runs,
    2. vapor produces an obfuscated jar (exit code 0),
    3. the obfuscated jar produces byte-identical stdout and the same exit code,
    4. both jars decompile (CFR + Vineflower) so obfuscation quality can be inspected.

.EXAMPLE
  .\scripts\rig.ps1 -Sample hello -Build
.EXAMPLE
  .\scripts\rig.ps1 -Sample legacy8
#>
param(
    [string]$Sample = "hello",
    [int]$Release = 0,           # 0 = auto per sample
    [string]$MainClass = "",     # "" = auto per sample
    [switch]$Build,              # build the vapor fat jar first
    [switch]$SkipDecompile
)

$ErrorActionPreference = "Stop"

# ZipFile for mapping/plaintext checks (Windows PowerShell 5.1 needs the assembly loaded explicitly)
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Get-VaporJar {
    $libs = Join-Path $Root "build\libs"
    if (-not (Test-Path $libs)) { return $null }
    return Get-ChildItem $libs -Filter "vapor-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc|plain' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Invoke-VaporBuild {
    Push-Location $Root
    try {
        $gradlew = Join-Path $Root "gradlew.bat"
        if (Test-Path $gradlew) {
            & $gradlew shadowJar --console=plain -q
        } else {
            & gradle shadowJar --console=plain -q
        }
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    } finally {
        Pop-Location
    }
}

function Fail($msg) {
    Write-Host ""
    Write-Host "[rig] FAIL: $msg" -ForegroundColor Red
    exit 1
}

function Step($name) {
    Write-Host "[rig] $name" -ForegroundColor Cyan
}

$Root = Split-Path -Parent $PSScriptRoot

# --- resolve per-sample defaults -------------------------------------------
if ($Release -eq 0) {
    $Release = switch ($Sample) { "hello" { 21 } "legacy8" { 8 } "hardening" { 21 } default { 21 } }
}
if ([string]::IsNullOrEmpty($MainClass)) {
    $MainClass = switch ($Sample) {
        "hello" { "dev.sample.Main" }
        "legacy8" { "dev.legacy.Main" }
        "hardening" { "dev.hardening.Main" }
        default { "dev.sample.Main" }
    }
}

$SampleDir = Join-Path $Root "samples\$Sample"
if (-not (Test-Path $SampleDir)) { Fail "sample not found: $SampleDir" }

# --- locate / build the obfuscator ------------------------------------------
$Fat = Get-VaporJar
if ($Build -or -not $Fat) {
    Step "building vapor fat jar ..."
    Invoke-VaporBuild
    $Fat = Get-VaporJar
}
if (-not $Fat) { Fail "no vapor jar in build\libs (run with -Build)" }

# --- workspace ---------------------------------------------------------------
$Work = Join-Path $env:TEMP "opencode\vapor-rig\$Sample-$Release"
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
$ClassesDir = Join-Path $Work "classes"
New-Item -ItemType Directory -Path $ClassesDir -Force | Out-Null

# --- compile ------------------------------------------------------------------
Step "compiling sample '$Sample' with --release $Release"
$sources = Get-ChildItem (Join-Path $SampleDir "src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"   # javac warns on stderr (e.g. "--release 8 is obsolete")
try {
    & javac -Xlint:-options --release $Release -d $ClassesDir $sources
    $javacCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevPref
}
if ($javacCode -ne 0) { Fail "javac failed" }

$ResDir = Join-Path $SampleDir "resources"
if (Test-Path $ResDir) {
    Copy-Item -Recurse -Force (Join-Path $ResDir "*") $ClassesDir
}

$BaseJar = Join-Path $Work "$Sample.jar"
Step "packaging $BaseJar"
& jar cfe $BaseJar $MainClass -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { Fail "jar packaging failed" }

# --- baseline run --------------------------------------------------------------
$BaseOut = Join-Path $Work "baseline.out"
$BaseErr = Join-Path $Work "baseline.err"
Step "running baseline jar"
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & java -jar $BaseJar 1> $BaseOut 2> $BaseErr
    $baseCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevPref
}

# --- obfuscate -------------------------------------------------------------------
$ObfJar = Join-Path $Work "$Sample-obf.jar"
$Mapping = Join-Path $Work "mapping.txt"
$VaporArgs = @("-i", $BaseJar, "-o", $ObfJar, "-mapping", $Mapping)
$ConfigFile = Join-Path $SampleDir "vapor.json"
if (Test-Path $ConfigFile) { $VaporArgs += @("-config", $ConfigFile) }
Step "obfuscating with $($Fat.Name)"
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & java -jar $Fat.FullName @VaporArgs
    $vaporCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevPref
}
if ($vaporCode -ne 0) { Fail "vapor exited with $vaporCode" }
if (-not (Test-Path $ObfJar)) { Fail "obfuscated jar was not produced" }

# --- mapping sanity (renamed names must reverse to original ones) ----------------
Step "checking mapping.txt"
if (-not (Test-Path $Mapping)) { Fail "mapping.txt not produced" }
$origClass = $null
$newClass = $null
foreach ($line in Get-Content $Mapping) {
    if ($line -match '^(\S+) -> (\S+):$' -and $Matches[1] -ne $Matches[2]) {
        $origClass = $Matches[1]
        $newClass = $Matches[2]
        break
    }
}
if (-not $origClass) { Fail "mapping.txt contains no renamed class entry" }
$zip = [System.IO.Compression.ZipFile]::OpenRead($ObfJar)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
} finally {
    $zip.Dispose()
}
$origPath = $origClass.Replace('.', '/') + '.class'
$newPath = $newClass.Replace('.', '/') + '.class'
if ($names -contains $origPath) { Fail "original class path still present in obfuscated jar: $origPath" }
if (-not ($names -contains $newPath)) { Fail "renamed class missing from obfuscated jar: $newPath" }
Write-Host "[rig] mapping: PASS ($origClass -> $newClass)" -ForegroundColor Green

# --- plaintext marker scan (per-sample file, added with string encryption) -------
$MarkerFile = Join-Path $SampleDir "plaintext-markers.txt"
if (Test-Path $MarkerFile) {
    Step "scanning obfuscated jar for plaintext markers"
    $markers = @(Get-Content $MarkerFile | Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") })
    $leaks = @()
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ObfJar)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.Length -eq 0) { continue }
            $stream = $entry.Open()
            try {
                $buffer = New-Object System.IO.MemoryStream
                $stream.CopyTo($buffer)
                $bytes = $buffer.ToArray()
            } finally {
                $stream.Dispose()
            }
            $text = [System.Text.Encoding]::GetEncoding("ISO-8859-1").GetString($bytes)
            foreach ($marker in $markers) {
                if ($text.Contains($marker)) { $leaks += "$($entry.FullName): $marker" }
            }
        }
    } finally {
        $zip.Dispose()
    }
    if ($leaks.Count -gt 0) {
        Write-Host "[rig] plaintext markers found in obfuscated jar:" -ForegroundColor Yellow
        $leaks | Select-Object -First 10 | ForEach-Object { Write-Host "       $_" }
        Fail "plaintext leak: $($leaks[0])"
    }
    Write-Host "[rig] plaintext scan: PASS ($($markers.Count) markers absent)" -ForegroundColor Green
}

# --- watermark presence (config watermark must be embedded in the obfuscated jar) ---
if (Test-Path $ConfigFile) {
    $wm = (Get-Content $ConfigFile -Raw | ConvertFrom-Json).watermark
    if ($wm) {
        Step "checking watermark"
        $found = $false
        $zip = [System.IO.Compression.ZipFile]::OpenRead($ObfJar)
        try {
            foreach ($entry in $zip.Entries) {
                if ($entry.Length -eq 0) { continue }
                $stream = $entry.Open()
                try {
                    $buffer = New-Object System.IO.MemoryStream
                    $stream.CopyTo($buffer)
                    $bytes = $buffer.ToArray()
                } finally {
                    $stream.Dispose()
                }
                $text = [System.Text.Encoding]::GetEncoding("ISO-8859-1").GetString($bytes)
                if ($text.Contains($wm)) { $found = $true; break }
            }
        } finally {
            $zip.Dispose()
        }
        if (-not $found) { Fail "watermark not embedded in obfuscated jar" }
        Write-Host "[rig] watermark: PASS (embedded)" -ForegroundColor Green
    }
}

# --- obfuscated run + comparison ----------------------------------------------------
$ObfOut = Join-Path $Work "obfuscated.out"
$ObfErr = Join-Path $Work "obfuscated.err"
Step "running obfuscated jar"
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & java -jar $ObfJar 1> $ObfOut 2> $ObfErr
    $obfCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevPref
}

$ok = $true
if ($baseCode -ne $obfCode) {
    Write-Host "[rig] exit code mismatch: baseline=$baseCode obfuscated=$obfCode" -ForegroundColor Yellow
    $ok = $false
}
$baseText = Get-Content $BaseOut -Raw
$obfText = Get-Content $ObfOut -Raw
if ($baseText -ne $obfText) {
    Write-Host "[rig] stdout differs:" -ForegroundColor Yellow
    Compare-Object (Get-Content $BaseOut) (Get-Content $ObfOut) | ForEach-Object {
        Write-Host ("       {0} {1}" -f $_.SideIndicator, $_.InputObject)
    }
    $ok = $false
}
if (-not $ok) { Fail "behavior mismatch (see $Work)" }

Write-Host "[rig] behavior: PASS (stdout identical, exit code $baseCode)" -ForegroundColor Green

# --- decompile -------------------------------------------------------------------
if (-not $SkipDecompile) {
    Step "decompiling baseline + obfuscated (CFR + Vineflower)"
    $cfr = Join-Path $Root "tools\cfr-0.152.jar"
    $vf = Join-Path $Root "tools\vineflower-1.12.0.jar"

    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # decompilers chatter on stderr
    try {
        & java -jar $cfr $BaseJar --outputdir (Join-Path $Work "dec-cfr-base") *> (Join-Path $Work "dec-cfr-base.log")
        & java -jar $cfr $ObfJar  --outputdir (Join-Path $Work "dec-cfr-obf")  *> (Join-Path $Work "dec-cfr-obf.log")
        & java -jar $vf $BaseJar (Join-Path $Work "dec-vf-base") *> (Join-Path $Work "dec-vf-base.log")
        & java -jar $vf $ObfJar  (Join-Path $Work "dec-vf-obf")  *> (Join-Path $Work "dec-vf-obf.log")
    } finally {
        $ErrorActionPreference = $prevPref
    }

    foreach ($dir in @("dec-cfr-base", "dec-cfr-obf", "dec-vf-base", "dec-vf-obf")) {
        $full = Join-Path $Work $dir
        if (-not (Test-Path $full) -or -not (Get-ChildItem $full -Recurse -File | Select-Object -First 1)) {
            Fail "decompiler produced no output in $dir (see $dir.log)"
        }
    }
}

# --- summary -------------------------------------------------------------------------
Write-Host ""
Write-Host "[rig] done: $Sample (release $Release)" -ForegroundColor Green
Write-Host "       workspace:      $Work"
Write-Host "       baseline jar:   $BaseJar"
Write-Host "       obfuscated jar: $ObfJar"
if (-not $SkipDecompile) {
    Write-Host "       decompiled:     $Work\dec-cfr-obf"
    Write-Host "                       $Work\dec-vf-obf"
}
