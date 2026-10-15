<#
  VDI frontend probe -- can this machine get React at all, and how?

  Answers three SEPARATE questions that are easy to conflate:

    1. Does npm read METADATA from a registry?        (npm ping / npm view)
    2. Does npm actually DOWNLOAD a tarball?          (npm install -- the real test)
    3. Does a plain HTTPS GET for a .js file work?    (the no-build route needs
                                                       exactly 3 such files, once)

  Question 1 passing does not imply 2: a proxy can serve the registry's JSON and
  still block the tarball CDN. Question 3 can pass while 1 and 2 both fail, which
  is the case the no-build route is designed for.

  Installs into LOCALAPPDATA, never the repo: the VDI runs this checkout from a
  UNC share mapped to Z:, and node_modules is tens of thousands of small files.
  Putting that on SMB is the same mistake the H2 database file already had to be
  moved off (see application.properties, "OUTSIDE").

  Run from any directory. Nothing is installed permanently; the probe directory
  is left behind on purpose so you can look at it, and the path is printed.
#>

$ErrorActionPreference = "Continue"

# Windows PowerShell 5.1 can still default to TLS 1.0, which the endpoints
# below refuse outright -- that failure looks identical to a blocked proxy.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
$probe = Join-Path $env:LOCALAPPDATA "uptest-frontend-probe"
$log   = Join-Path $probe "probe-output.txt"

function Section($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

function Try-Run($label, $block) {
    Write-Host ("-- " + $label)
    try {
        & $block
        if ($LASTEXITCODE -ne $null -and $LASTEXITCODE -ne 0) {
            Write-Host ("   EXIT CODE " + $LASTEXITCODE) -ForegroundColor Yellow
        }
    } catch {
        Write-Host ("   FAILED: " + $_.Exception.Message) -ForegroundColor Red
    }
}

New-Item -ItemType Directory -Force $probe | Out-Null
Start-Transcript -Path $log -Force | Out-Null

Write-Host "Probe directory: $probe"
Write-Host "Transcript:      $log"

Section "0. Is node even here"
Try-Run "node --version"  { node --version }
Try-Run "npm --version"   { npm --version }
Try-Run "where node"      { where.exe node }

Section "1. How is npm pointed"
Try-Run "registry"        { npm config get registry }
Try-Run "proxy"           { npm config get proxy }
Try-Run "https-proxy"     { npm config get https-proxy }
Try-Run "strict-ssl"      { npm config get strict-ssl }
Try-Run "cafile"          { npm config get cafile }
Write-Host ("-- env HTTP_PROXY  = " + $env:HTTP_PROXY)
Write-Host ("-- env HTTPS_PROXY = " + $env:HTTPS_PROXY)
Write-Host ("-- env NO_PROXY    = " + $env:NO_PROXY)
Write-Host ("-- env NODE_EXTRA_CA_CERTS = " + $env:NODE_EXTRA_CA_CERTS)

Section "2. Metadata reachable? (does NOT prove downloads work)"
Try-Run "npm ping"                  { npm ping }
Try-Run "npm view react version"    { npm view react version }
Try-Run "npm view react-dom version" { npm view react-dom version }
Try-Run "npm view vite version"     { npm view vite version }

Section "3. THE REAL TEST -- actually download tarballs"
Set-Location $probe
if (-not (Test-Path (Join-Path $probe "package.json"))) {
    Try-Run "npm init -y" { npm init -y | Out-Null }
}
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Try-Run "npm install react@18.3.1 react-dom@18.3.1" {
    npm install react@18.3.1 react-dom@18.3.1 --no-audit --no-fund
}
$sw.Stop()
Write-Host ("   elapsed: " + [math]::Round($sw.Elapsed.TotalSeconds,1) + " s")

$nm = Join-Path $probe "node_modules"
if (Test-Path $nm) {
    $files = (Get-ChildItem $nm -Recurse -File -ErrorAction SilentlyContinue).Count
    Write-Host ("   node_modules files: " + $files) -ForegroundColor Green
} else {
    Write-Host "   node_modules MISSING -- install did not work" -ForegroundColor Red
}

Section "4. No-build route -- plain HTTPS GET for 3 files"
# React 18.3.1 on purpose: React 19 no longer ships UMD builds, and UMD is what
# a page can load with a classic <script> tag and no bundler.
$targets = @(
  @{ url = "https://unpkg.com/react@18.3.1/umd/react.production.min.js";          out = "react.production.min.js" },
  @{ url = "https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js";  out = "react-dom.production.min.js" },
  @{ url = "https://unpkg.com/htm@3.1.1/dist/htm.module.js";                      out = "htm.module.js" }
)
foreach ($t in $targets) {
    $dest = Join-Path $probe $t.out
    Try-Run $t.url {
        Invoke-WebRequest -Uri $t.url -OutFile $dest -UseBasicParsing -TimeoutSec 30
        $size = (Get-Item $dest).Length
        Write-Host ("   OK " + $t.out + " = " + $size + " bytes") -ForegroundColor Green
    }
}

Section "Verdict"
$npmWorked = Test-Path (Join-Path $probe "node_modules\react\package.json")
$getWorked = Test-Path (Join-Path $probe "react-dom.production.min.js")
if ($npmWorked) {
    Write-Host "npm CAN install packages here. Building on the VDI is possible." -ForegroundColor Green
} else {
    Write-Host "npm CANNOT install packages here. Do not plan a VDI build." -ForegroundColor Yellow
}
if ($getWorked) {
    Write-Host "Plain HTTPS download works. The no-build route can fetch its 3 files here." -ForegroundColor Green
} else {
    Write-Host "Plain HTTPS download blocked. Fetch the 3 files elsewhere and hand-copy." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Paste the transcript back:" -ForegroundColor Cyan
Write-Host "  $log"
Stop-Transcript | Out-Null
