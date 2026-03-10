param(
    [string]$Image = "alpine:3.20"
)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$certDir = Join-Path $PSScriptRoot "certs"

New-Item -ItemType Directory -Force -Path $certDir | Out-Null

$script = @"
apk add --no-cache openssl >/dev/null
mkdir -p docker/fixture/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
  -subj '/CN=env-fixture' \
  -addext 'subjectAltName=DNS:env-fixture,DNS:localhost' \
  -keyout docker/fixture/certs/env-fixture.key \
  -out docker/fixture/certs/env-fixture.crt
"@

docker run --rm -v "${repoRoot}:/workspace" -w /workspace $Image sh -lc $script
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Generated local fixture certificates in docker/fixture/certs"
