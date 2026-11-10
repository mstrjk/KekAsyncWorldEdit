param(
    [switch]$Check
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http, System.IO.Compression, System.IO.Compression.FileSystem

$root  = Split-Path $PSCommandPath -Parent
$cache = "$root\.cache"
$src   = "$root\src\main\java"
$res   = "$root\src\main\resources"
$out   = "$root\build"

$compileTarget = "folia\26.3"
$targets = @(
    "paper\26.1.1", "paper\26.1.2", "paper\26.2", "paper\26.3",
    "folia\26.1.2", "folia\26.2", "folia\26.3"
)
$libraries = @(
    @{ Name = "annotations-26.0.2.jar";  Url = "https://repo1.maven.org/maven2/org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar";            Sha = "2037be378980d3ba9333e97955f3b2cde392aa124d04ca73ce2eee6657199297" },
    @{ Name = "checker-qual-3.49.2.jar"; Url = "https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.49.2/checker-qual-3.49.2.jar"; Sha = "33b1d8b2c4b00f7d4f4b4931424274f3f729474ce3a87cea09a879976a1dcaeb" }
)

$fancy = -not [Console]::IsOutputRedirected
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMinutes(30)
$http.DefaultRequestHeaders.UserAgent.ParseAdd("KekAsyncWorldEdit-build/1.0")

$script:step = 0
$script:steps = 0
$script:prefix = ""
$script:clock = $null
$script:tick = [Diagnostics.Stopwatch]::StartNew()

function Write-Line([object[]]$parts) {
    $width = [Math]::Max(20, [Console]::BufferWidth - 1)
    $length = 0
    Write-Host "`r" -NoNewline
    for ($i = 0; $i -lt $parts.Count; $i += 2) {
        $text = [string]$parts[$i]
        if ($length + $text.Length -gt $width) { $text = $text.Substring(0, [Math]::Max(0, $width - $length)) }
        $length += $text.Length
        Write-Host $text -NoNewline -ForegroundColor $parts[$i + 1]
    }
    if ($length -lt $width) { Write-Host (' ' * ($width - $length)) -NoNewline }
}

function Start-Step([string]$name) {
    $script:step++
    $script:prefix = "  [{0}/{1}] {2,-16}" -f $script:step, $script:steps, $name
    $script:clock = [Diagnostics.Stopwatch]::StartNew()
    if ($fancy) { Write-Line @($script:prefix, 'Gray') } else { Write-Host "$($script:prefix)..." }
}

function Test-Tick {
    if ($script:tick.ElapsedMilliseconds -lt 80) { return $false }
    $script:tick.Restart()
    return $true
}

function Show-Bar([double]$fraction, [string]$text) {
    if (-not $fancy) { return }
    $fraction = [Math]::Min(1, [Math]::Max(0, $fraction))
    $size = 28
    $filled = [int][Math]::Floor($fraction * $size)
    Write-Line @($script:prefix, 'Gray', [string]::new([char]0x2588, $filled), 'Cyan', [string]::new([char]0x2591, $size - $filled), 'DarkGray', (" {0,3}%  {1}" -f [int][Math]::Floor($fraction * 100), $text), 'Gray')
}

function Show-Spin([string]$text) {
    if (-not $fancy) { return }
    $size = 28
    $span = 6
    $position = [int](($script:clock.ElapsedMilliseconds / 60) % (2 * ($size - $span)))
    if ($position -gt $size - $span) { $position = 2 * ($size - $span) - $position }
    Write-Line @($script:prefix, 'Gray', [string]::new([char]0x2591, $position), 'DarkGray', [string]::new([char]0x2588, $span), 'Cyan', [string]::new([char]0x2591, $size - $span - $position), 'DarkGray', ("  {0,5:N1} s  {1}" -f $script:clock.Elapsed.TotalSeconds, $text), 'Gray')
}

function Complete-Step([string]$text) {
    $time = "{0:N1} s" -f $script:clock.Elapsed.TotalSeconds
    if ($fancy) {
        Write-Line @($script:prefix, 'Gray', "done", 'Green', "  $text", 'Gray', "  $time", 'DarkGray')
        Write-Host ""
    } else {
        Write-Host "$($script:prefix)done  $text  $time"
    }
}

function Stop-Build([string]$message, [string[]]$details) {
    if ($fancy) {
        Write-Line @($script:prefix, 'Gray', "failed", 'Red')
        Write-Host ""
    }
    $details | Where-Object { $_ } | ForEach-Object { Write-Host "    $_" }
    Write-Host ""
    Write-Host "  $message" -ForegroundColor Red
    exit 1
}

function Format-Size([double]$bytes) {
    return "{0:N1} MB" -f ($bytes / 1MB)
}

function Get-Sha([string]$path) {
    return (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Json([string]$url) {
    $response = $http.GetAsync($url).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) { Stop-Build "Request failed ($([int]$response.StatusCode)): $url" }
    return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
}

function Save-File([string]$url, [string]$path, [string]$sha) {
    New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
    $part = "$path.part"
    $response = $http.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) { Stop-Build "Download failed ($([int]$response.StatusCode)): $url" }
    $total = $response.Content.Headers.ContentLength
    $source = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $output = [IO.File]::Create($part)
    $buffer = New-Object byte[] 262144
    $done = 0L
    try {
        while (($read = $source.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $output.Write($buffer, 0, $read)
            $done += $read
            if (Test-Tick) {
                if ($total) {
                    Show-Bar ($done / $total) ("{0} / {1}" -f (Format-Size $done), (Format-Size $total))
                } else {
                    Show-Spin (Format-Size $done)
                }
            }
        }
    } finally {
        $output.Dispose()
        $source.Dispose()
        $response.Dispose()
    }
    if ($sha -and (Get-Sha $part) -ne $sha.ToLowerInvariant()) {
        Remove-Item $part -Force
        Stop-Build "Checksum mismatch for $url"
    }
    Move-Item $part $path -Force
}

function Expand-Zip([string]$zip, [string]$destination) {
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $entries = $archive.Entries
        $count = $entries.Count
        $index = 0
        foreach ($entry in $entries) {
            $index++
            $target = [IO.Path]::Combine($destination, $entry.FullName)
            if ($entry.FullName.EndsWith('/')) {
                New-Item -ItemType Directory -Force $target | Out-Null
            } else {
                New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
            if (Test-Tick) { Show-Bar ($index / $count) "unpacking $index / $count files" }
        }
    } finally {
        $archive.Dispose()
    }
}

function Quote([string]$value) {
    return '"' + ($value -replace '\\', '/') + '"'
}

function Invoke-Watched([string]$exe, [string[]]$arguments, [string]$directory, [scriptblock]$progress) {
    $stdout = "$cache\process.out"
    $stderr = "$cache\process.err"
    $process = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $directory -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null = $process.Handle
    while (-not $process.HasExited) {
        if ($fancy) {
            $text = ""
            try {
                $stream = [IO.File]::Open($stderr, 'Open', 'Read', 'ReadWrite')
                $reader = New-Object IO.StreamReader($stream)
                $text = $reader.ReadToEnd()
                $reader.Dispose()
            } catch {
            }
            & $progress $text
        }
        Start-Sleep -Milliseconds 80
    }
    $process.WaitForExit()
    return @{
        Code = $process.ExitCode
        Out  = @(Get-Content $stdout -ErrorAction SilentlyContinue)
        Err  = @(Get-Content $stderr -ErrorAction SilentlyContinue)
    }
}

function Get-Jdk {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe") -and (Test-Path "$env:JAVA_HOME\release")) {
        $line = Select-String -Path "$env:JAVA_HOME\release" -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($line -and [int]$line.Matches[0].Groups[1].Value -ge 25) {
            Complete-Step "JAVA_HOME"
            return "$env:JAVA_HOME\bin"
        }
    }
    $jdkHome = "$cache\jdk"
    if (Test-Path "$jdkHome\bin\javac.exe") {
        Complete-Step "cached"
        return "$jdkHome\bin"
    }
    $asset = @(Get-Json "https://api.adoptium.net/v3/assets/latest/25/hotspot?os=windows&architecture=x64&image_type=jdk&vendor=eclipse")[0]
    $zip = "$cache\jdk.zip"
    Save-File $asset.binary.package.link $zip $asset.binary.package.checksum
    $unpack = "$cache\jdk-unpack"
    if (Test-Path $unpack) { Remove-Item $unpack -Recurse -Force }
    Expand-Zip $zip $unpack
    $inner = @(Get-ChildItem $unpack -Directory)[0].FullName
    Move-Item $inner $jdkHome
    Remove-Item $unpack, $zip -Recurse -Force
    Complete-Step $asset.release_name
    return "$jdkHome\bin"
}

function Get-Server([string]$target) {
    $type, $version = $target.Split('\')
    $dir = "$cache\servers\$type\$version"
    $jar = "$dir\$type-$version.jar"
    if ((Test-Path $jar) -and (Test-Path "$dir\libraries")) {
        Complete-Step "cached"
        return
    }
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    $build = Get-Json "https://fill.papermc.io/v3/projects/$type/versions/$version/builds/latest"
    $download = $build.downloads.'server:default'
    Save-File $download.url "$dir\paperclip.jar" $download.checksums.sha256
    $result = Invoke-Watched "$jdk\java.exe" @("-Dpaperclip.patchonly=true", "-jar", "paperclip.jar") $dir {
        param($text)
        Show-Spin "patching"
    }
    $patched = "$dir\versions\$version\$type-$version.jar"
    if ($result.Code -ne 0 -or -not (Test-Path $patched)) { Stop-Build "Patching $type $version failed." ($result.Out + $result.Err) }
    Move-Item $patched $jar
    Remove-Item "$dir\paperclip.jar", "$dir\versions", "$dir\cache" -Recurse -Force -ErrorAction SilentlyContinue
    Complete-Step "build $($build.id)"
}

function Get-Libraries {
    $fetched = 0
    foreach ($library in $libraries) {
        $path = "$cache\libs\$($library.Name)"
        if ((Test-Path $path) -and (Get-Sha $path) -eq $library.Sha) { continue }
        Save-File $library.Url $path $library.Sha
        $fetched++
    }
    Complete-Step $(if ($fetched -gt 0) { "$fetched downloaded" } else { "cached" })
}

function Get-Classpath([string]$target) {
    $dir = "$cache\servers\$target"
    $jars = @(Get-ChildItem $dir -Filter *.jar | ForEach-Object { $_.FullName })
    $jars += @(Get-ChildItem "$dir\libraries" -Recurse -Filter *.jar | ForEach-Object { $_.FullName })
    return ($jars + $annotations) -join ';'
}

$serverTargets = if ($Check) { $targets } else { @($compileTarget) }
$script:steps = 4 + $serverTargets.Count + $(if ($Check) { 1 } else { 0 })

$versionFile = "$root\version.txt"
$parts = @((Get-Content $versionFile -Raw).Trim().Split('.') | ForEach-Object { [int]$_ })
$major = $parts[0]; $minor = $parts[1]; $patch = $parts[2] + 1
if ($patch -gt 9) { $patch = 0; $minor++ }
if ($minor -gt 99) { $minor = 0; $major++ }
$version = "{0}.{1:D2}.{2}" -f $major, $minor, $patch

$cursor = $true
if ($fancy) {
    $cursor = [Console]::CursorVisible
    [Console]::CursorVisible = $false
}
$total = [Diagnostics.Stopwatch]::StartNew()
try {
    New-Item -ItemType Directory -Force $cache, $out | Out-Null
    Write-Host ""
    Write-Host "  KekAsyncWorldEdit " -NoNewline
    Write-Host $version -ForegroundColor Cyan
    Write-Host ""

    Start-Step "Java 25"
    $jdk = Get-Jdk

    foreach ($target in $serverTargets) {
        $type, $serverVersion = $target.Split('\')
        Start-Step ("{0} {1}" -f (Get-Culture).TextInfo.ToTitleCase($type), $serverVersion)
        Get-Server $target
    }

    Start-Step "Libraries"
    Get-Libraries
    $annotations = @($libraries | ForEach-Object { "$cache\libs\$($_.Name)" })

    Start-Step "Compile"
    $classes = "$out\classes"
    if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
    New-Item -ItemType Directory -Force $classes | Out-Null
    $sources = @(Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $argfile = "$out\javac.txt"
    $arguments = @("--release", "25", "-encoding", "UTF-8", "-proc:none", "-Xlint:all", "-Xmaxerrs", "500", "-Xmaxwarns", "500", "-verbose",
                   "-cp", (Quote (Get-Classpath $compileTarget)), "-d", (Quote $classes)) + @($sources | ForEach-Object { Quote $_ })
    [IO.File]::WriteAllLines($argfile, $arguments, (New-Object System.Text.UTF8Encoding($false)))
    $count = $sources.Count
    $result = Invoke-Watched "$jdk\javac.exe" @(Quote "@$argfile") $root {
        param($text)
        $parsed = [regex]::Matches($text, '(?m)^\[parsing completed').Count
        $checked = [regex]::Matches($text, '(?m)^\[checking ').Count
        $wrote = [regex]::Matches($text, '(?m)^\[wrote ').Count
        if ($checked -eq 0) {
            Show-Bar (0.15 * $parsed / $count) "parsing $parsed / $count files"
        } else {
            Show-Bar (0.15 + 0.85 * [Math]::Min(1, $checked / $count)) "compiling $checked / $count classes, $wrote written"
        }
    }
    $diagnostics = @($result.Err | Where-Object { $_ -and -not $_.StartsWith('[') })
    $warnings = @($diagnostics | Where-Object { $_ -match ': warning: ' }).Count
    if ($result.Code -ne 0) { Stop-Build "Compile failed." $diagnostics }
    Complete-Step ("{0} files{1}" -f $count, $(if ($warnings -gt 0) { ", $warnings warnings" } else { "" }))
    if ($warnings -gt 0) { $diagnostics | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow } }

    Start-Step "Package"
    Copy-Item "$res\*" $classes -Recurse -Force
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $pluginYml = "$classes\plugin.yml"
    $yml = [IO.File]::ReadAllText($pluginYml) -replace '(?m)^version: .*$', "version: `"$version`""
    [IO.File]::WriteAllText($pluginYml, $yml, $utf8NoBom)
    Get-ChildItem $out -Filter *.jar | Remove-Item -Force
    $jar = "$out\KekAsyncWorldEdit-Folia-$version.jar"
    $result = Invoke-Watched "$jdk\jar.exe" @("--create", "--file", (Quote $jar), "-C", (Quote $classes), ".") $root {
        param($text)
        Show-Spin "writing jar"
    }
    if ($result.Code -ne 0) { Stop-Build "Packaging failed." ($result.Out + $result.Err) }
    [IO.File]::WriteAllText($versionFile, "$version`n", $utf8NoBom)
    Complete-Step ("{0}, {1}" -f (Split-Path $jar -Leaf), (Format-Size (Get-Item $jar).Length))

    if ($Check) {
        Start-Step "Link check"
        $failed = @()
        $report = @()
        $index = 0
        foreach ($t in $targets) {
            Show-Bar ($index / $targets.Count) $t.Replace('\', ' ')
            $index++
            $lines = @(& "$jdk\java.exe" "$root\tools\LinkCheck.java" $jar "$cache\servers\$t" @annotations)
            $code = $LASTEXITCODE
            $warns = @($lines | Where-Object { $_ -like 'warn:*' })
            $errors = @($lines | Where-Object { $_ -notlike 'warn:*' })
            if ($code -ne 0 -or $warns.Count -gt 0) {
                $report += "== $t ($($errors.Count) problems, $($warns.Count) warnings)"
                $report += $errors | ForEach-Object { "  $_" }
                $report += $warns | Select-Object -First 10 | ForEach-Object { "  $_" }
            }
            if ($code -ne 0) { $failed += $t }
        }
        if ($failed.Count -gt 0) { Stop-Build "Link check failed: $($failed -join ', ')" $report }
        Complete-Step "all $($targets.Count) targets resolve"
        $report | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
    }

    Write-Host ""
    Write-Host "  Built " -NoNewline
    Write-Host $jar -ForegroundColor Green -NoNewline
    Write-Host (" in {0:N1} s" -f $total.Elapsed.TotalSeconds) -ForegroundColor DarkGray
    Write-Host ""
} finally {
    if ($fancy) { [Console]::CursorVisible = $cursor }
    $http.Dispose()
    Remove-Item "$cache\process.out", "$cache\process.err" -Force -ErrorAction SilentlyContinue
}
