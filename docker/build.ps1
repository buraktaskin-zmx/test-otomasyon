# Builds the jars, then the images — in that order, always.
#
# The Dockerfiles copy a prebuilt jar rather than compiling inside Docker, which
# is fast and avoids re-downloading every dependency through the corporate
# proxy. The cost is that a forgotten `mvn package` ships a stale jar; this
# script exists so that cannot happen.
#
#   .\docker\build.ps1                      build both images
#   .\docker\build.ps1 -SkipMaven           images only (jars already fresh)
#   .\docker\build.ps1 -Tag 1.0.0           tag the images

param(
    [string]$Tag = "latest",
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not $SkipMaven) {
    Write-Host "==> mvn package" -ForegroundColor Cyan
    if (-not $env:JAVA_HOME) {
        throw "JAVA_HOME is not set. Example: `$env:JAVA_HOME='D:\dev\jdk\jdk-21.0.12.1+1'"
    }
    $mvn = if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME "bin\mvn.cmd" } else { "mvn" }
    $mvnArgs = @("-DskipTests", "package")
    if ($env:MAVEN_SETTINGS) { $mvnArgs = @("-s", $env:MAVEN_SETTINGS) + $mvnArgs }
    & $mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
}

foreach ($jar in @("uptest-executor\target\uptest-executor-*.jar",
                   "uptest-orchestrator\target\uptest-orchestrator-*.jar")) {
    if (-not (Get-ChildItem $jar -ErrorAction SilentlyContinue)) {
        throw "Missing $jar — run without -SkipMaven"
    }
}

Write-Host "==> docker build" -ForegroundColor Cyan
$env:TAG = $Tag
docker compose build
if ($LASTEXITCODE -ne 0) { throw "Image build failed" }

Write-Host "`nBuilt:" -ForegroundColor Green
docker images --filter "reference=uptest-*:$Tag" --format "  {{.Repository}}:{{.Tag}}  {{.Size}}"
Write-Host "`nStart with: docker compose up -d" -ForegroundColor Green
