param([switch]$LaboratoryOnly)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Отдельный прогон тестов: сборка (build.ps1) их больше не запускает.
# Сначала соберите проект: build.ps1, затем этот скрипт.
function Get-JdkMajor($home_) {
    $release = Join-Path $home_ 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$jdkTxt = Join-Path $PSScriptRoot 'build/jdk.txt'
if (Test-Path $jdkTxt) { $candidates += (Get-Content $jdkTxt -Raw).Trim() }
$jdk = $candidates | Where-Object { (Test-Path (Join-Path $_ 'bin\javac.exe')) -and (Get-JdkMajor $_) -ge 22 } | Select-Object -First 1
if (-not $jdk) { throw 'JDK 22 or newer is required: set JAVA_HOME to it.' }

$main = Join-Path $PSScriptRoot 'build/classes'
if (-not (Test-Path $main)) { throw 'Сначала build.ps1: нет build/classes.' }
$testOut = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/test-classes'))
$testWorkspace = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
if (-not $testOut.StartsWith($testWorkspace, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid test output path.' }
if (Test-Path $testOut) { Remove-Item -LiteralPath $testOut -Recurse -Force }
New-Item -ItemType Directory -Force $testOut | Out-Null

$sources = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 22 -encoding UTF-8 -cp $main -d $testOut $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }

if (-not $LaboratoryOnly) {
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.FormulaTest
if ($LASTEXITCODE -ne 0) { throw 'Formula tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.ui.GlSceneTest
if ($LASTEXITCODE -ne 0) { throw 'Shader tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.ui.VisualDeformationTest
if ($LASTEXITCODE -ne 0) { throw 'Visual deformation tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.ui.EquatorOverlayTest
if ($LASTEXITCODE -ne 0) { throw 'Equator overlay tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.ui.CloudTest
if ($LASTEXITCODE -ne 0) { throw 'Cloud tests failed.' }
}
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.MoleculeLabTest
if ($LASTEXITCODE -ne 0) { throw 'Molecule laboratory tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.LabRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Legacy laboratory regression tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp "$testOut;$main" uif.SurfaceContactTest
if ($LASTEXITCODE -ne 0) { throw 'Surface contact tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' '-Djava.awt.headless=true' -cp "$testOut;$main" uif.ui.LabUiRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Laboratory UI regression tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' '-Djava.awt.headless=true' -cp "$testOut;$main" uif.ui.MoleculeLabPanelTest
if ($LASTEXITCODE -ne 0) { throw 'Molecule laboratory panel tests failed.' }
Write-Host 'Tests passed.'
