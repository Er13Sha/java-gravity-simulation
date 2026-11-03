$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# JDK 22 или новее (видеокарта вызывается через Foreign Function API Java).
# Порядок поиска: JAVA_HOME, javac из PATH, C:\Program Files\Java\jdk-*; берётся первый подходящий.
function Get-JdkMajor($home_) {
    $release = Join-Path $home_ 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($javac) {
    $ErrorActionPreference = 'Continue' # javac печатает свойства в stderr — это не ошибка
    $props = & $javac.Source -J-XshowSettings:properties -version 2>&1 | Out-String
    $ErrorActionPreference = 'Stop'
    if ($props -match 'java\.home = (.+)') { $candidates += $Matches[1].Trim() }
}
$candidates += @(Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object FullName)
$jdk = $candidates | Where-Object { (Test-Path (Join-Path $_ 'bin\javac.exe')) -and (Get-JdkMajor $_) -ge 22 } | Select-Object -First 1
if (-not $jdk) { throw 'JDK 22 or newer is required: set JAVA_HOME to it.' }
Write-Host "JDK: $jdk"

$classesPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/classes'))
$workspacePath = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
if (-not $classesPath.StartsWith($workspacePath, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid build output path.' }
if (Test-Path -LiteralPath $classesPath) { Remove-Item -LiteralPath $classesPath -Recurse -Force }
New-Item -ItemType Directory -Force build/classes,build/input | Out-Null

$sources = @(Get-ChildItem src/main/java,src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 22 -encoding UTF-8 -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }

& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp build/classes uif.FormulaTest
if ($LASTEXITCODE -ne 0) { throw 'Formula tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp build/classes uif.ui.GlSceneTest
if ($LASTEXITCODE -ne 0) { throw 'Shader tests failed.' }
& "$jdk/bin/java.exe" '-Dstdout.encoding=UTF-8' --enable-native-access=ALL-UNNAMED -cp build/classes uif.ui.CloudTest
if ($LASTEXITCODE -ne 0) { throw 'Cloud tests failed.' }

# Enable-Native-Access — чтобы окно могло вызывать драйвер видеокарты без предупреждений
$manifest = Join-Path $PSScriptRoot 'build/manifest.txt'
[System.IO.File]::WriteAllText($manifest, "Enable-Native-Access: ALL-UNNAMED`r`n")
& "$jdk/bin/jar.exe" --create --file build/input/universal-interaction-formula.jar --main-class uif.Main --manifest $manifest -C build/classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
# run.bat запускает тем же JDK
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'build/jdk.txt'), $jdk)
Write-Host 'Build and tests passed.'
