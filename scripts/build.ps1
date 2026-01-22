$ErrorActionPreference = 'Stop'

Write-Host "[VinylMatch] Building (Maven)..."

# Note: This build script skips tests for fast local iteration.
# When tests are skipped, JaCoCo coverage checks cannot be satisfied, so we skip JaCoCo as well.
$MavenArgs = @("-DskipTests", "-Djacoco.skip=true", "package")

if (Test-Path ".\\.tools\\maven") {
  # Use locally downloaded Maven if present (keeps onboarding easy on Windows)
  $mvn = Get-ChildItem ".\\.tools\\maven" -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($mvn) {
    & $mvn.FullName @MavenArgs
    exit $LASTEXITCODE
  }
}

& mvn @MavenArgs

