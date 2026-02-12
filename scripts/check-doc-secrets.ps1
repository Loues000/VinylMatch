$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$docs = @(
  Join-Path $root 'README.md'
  Join-Path $root 'docs'
)

$patterns = @(
  'SPOTIFY_CLIENT_SECRET\s*=\s*(?!your_|\$\{|<)[A-Za-z0-9_\-]{12,}',
  'DISCOGS_TOKEN\s*=\s*(?!your_|\$\{|<)[A-Za-z0-9_\-]{12,}',
  'DOCKER_PASSWORD\s*=\s*(?!your_|\$\{|<)[^\s]+'
)

$targets = @()
foreach ($path in $docs) {
  if (Test-Path $path -PathType Leaf) {
    $targets += $path
  } elseif (Test-Path $path -PathType Container) {
    $targets += Get-ChildItem $path -Recurse -File -Include *.md | Select-Object -ExpandProperty FullName
  }
}

$findings = @()
foreach ($file in $targets) {
  $content = Get-Content $file -Raw
  foreach ($pattern in $patterns) {
    $matches = [regex]::Matches($content, $pattern)
    foreach ($m in $matches) {
      $findings += [PSCustomObject]@{
        File = $file
        Pattern = $pattern
        Match = $m.Value
      }
    }
  }
}

if ($findings.Count -gt 0) {
  Write-Host "[FAIL] Potential secret-like values found in docs:" -ForegroundColor Red
  $findings | ForEach-Object {
    Write-Host "- $($_.File): $($_.Match)" -ForegroundColor Yellow
  }
  exit 1
}

Write-Host "[OK] No secret-like values found in markdown docs." -ForegroundColor Green
