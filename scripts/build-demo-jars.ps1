<#
.SYNOPSIS
  Compiles the demo jars (jars/src) into jars/input.

.DESCRIPTION
  Two demos are built:
    demo-cli.jar   small task-scheduler CLI (enum, record, interface, streams, switch-on-string)
    demo-math.jar  ASCII lab (mandelbrot, collatz, fnv-1a, sieve -- constant/loop heavy)

  Drop your own jars into jars/input as well; scripts\obfuscate-jars.ps1 picks
  up everything there.

.EXAMPLE
  .\scripts\build-demo-jars.ps1
#>
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$SrcRoot = Join-Path $Root "jars\src"
$InDir = Join-Path $Root "jars\input"
New-Item -ItemType Directory -Path $InDir -Force | Out-Null

$demos = @(
    @{ Name = "demo-cli";  Main = "demo.tasks.Main" },
    @{ Name = "demo-math"; Main = "demo.lab.Main" }
)

foreach ($demo in $demos) {
    $src = Join-Path $SrcRoot $demo.Name
    if (-not (Test-Path $src)) { Write-Host "[demos] FAIL: missing $src" -ForegroundColor Red; exit 1 }

    $work = Join-Path $env:TEMP "opencode\vapor-demos\$($demo.Name)"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    $classes = Join-Path $work "classes"
    New-Item -ItemType Directory -Path $classes -Force | Out-Null

    $sources = @(Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })

    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # javac may warn on stderr
    try {
        & javac -d $classes $sources
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPref
    }
    if ($code -ne 0) { Write-Host "[demos] FAIL: javac $($demo.Name)" -ForegroundColor Red; exit 1 }

    $jarPath = Join-Path $InDir "$($demo.Name).jar"
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & jar --create --file $jarPath --main-class $demo.Main -C $classes .
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPref
    }
    if ($code -ne 0) { Write-Host "[demos] FAIL: jar $($demo.Name)" -ForegroundColor Red; exit 1 }

    $classCount = @(Get-ChildItem $classes -Recurse -Filter *.class).Count
    $kb = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
    Write-Host "[demos] $($demo.Name).jar -> jars\input ($classCount classes, $kb KB)" -ForegroundColor Green
}
