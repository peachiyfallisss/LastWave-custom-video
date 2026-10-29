param([string]$KotlinLib)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $KotlinLib) {
    $compiler = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\wrapper\dists" -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse |
        Sort-Object FullName | Select-Object -Last 1
    if (-not $compiler) { throw 'Run the Gradle wrapper once, or pass -KotlinLib with a Kotlin compiler library folder.' }
    $KotlinLib = $compiler.DirectoryName
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ('lastwave-core-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
function Read-Source($path) { (Get-Content -LiteralPath (Join-Path $repo $path) -Raw).Replace("`r`n", "`n") }
try {
    & javac -cp "$KotlinLib\*" -d $temp (Join-Path $PSScriptRoot 'CheckKotlinSyntax.java')
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile syntax checker.' }
    $changedKotlin = @(& git -C $repo diff --name-only -- '*.kt') + @(& git -C $repo ls-files --others --exclude-standard -- '*.kt') |
        Sort-Object -Unique | ForEach-Object { Join-Path $repo $_ }
    & java -cp "$temp;$KotlinLib\*" CheckKotlinSyntax @changedKotlin
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin syntax checks failed.' }
    # Compile actual pure models and the repository's actual companion parser;
    # omit only Android/DI/provider wiring. This is NOT an Android compilation.
    $lyrics = Read-Source 'app/src/main/java/com/lastwave/app/data/lyrics/LyricsRepository.kt'
    $models = $lyrics.Substring($lyrics.IndexOf('fun isRtlText('), $lyrics.IndexOf('@Singleton') - $lyrics.IndexOf('fun isRtlText('))
    $companion = $lyrics.Substring($lyrics.IndexOf('    companion object {'))
    $companion = $companion.Substring(0, $companion.LastIndexOf('}'))
    "package com.lastwave.app.data.lyrics`n$models`nclass LyricsRepository {`n$companion`n}" | Set-Content -LiteralPath "$temp\LyricsCore.kt"
    $player = Read-Source 'app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt'
    $track = $player.Substring($player.IndexOf('@Serializable' + "`n" + 'data class PlayableTrack('))
    $track = $track.Substring(0, $track.IndexOf('@Serializable' + "`n" + 'internal data class PersistedPlaybackSession'))
    $state = $player.Substring($player.IndexOf('data class MusicPlayerState('))
    $state = $state.Substring(0, $state.IndexOf("`n)`n") + 3).Replace('Player.REPEAT_MODE_OFF', '0')
    "package com.lastwave.app.playback`nimport kotlinx.serialization.Serializable`n$track`n$state" | Set-Content -LiteralPath "$temp\PlaybackModels.kt"
    $concert = Read-Source 'app/src/main/java/com/lastwave/app/data/video/ConcertVideoRepository.kt'
    $concertModels = $concert.Substring($concert.IndexOf('@Serializable'), $concert.IndexOf('@Singleton') - $concert.IndexOf('@Serializable'))
    $timestamp = $concert.Substring($concert.IndexOf('fun parseConcertTimestampMs('))
    "package com.lastwave.app.data.video`nimport kotlinx.serialization.Serializable`nimport java.util.UUID`n$concertModels`n$timestamp" | Set-Content -LiteralPath "$temp\ConcertModels.kt"
    foreach ($dependency in @(@('junit','junit','4.13.2'), @('org/hamcrest','hamcrest-core','1.3'))) {
        $group, $name, $version = $dependency
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/$group/$name/$version/$name-$version.jar" -OutFile "$temp\$name.jar"
    }
    $runtimeJars = Get-ChildItem -LiteralPath $KotlinLib -File | Where-Object { $_.Name -match '^kotlin-stdlib.*\.jar$|^kotlinx-serialization-core.*\.jar$|^kotlinx-coroutines-core.*\.jar$|^annotations.*\.jar$' }
    $classpath = (($runtimeJars.FullName + @("$temp\junit.jar", "$temp\hamcrest-core.jar")) -join [IO.Path]::PathSeparator)
    $sources = @("$temp\LyricsCore.kt", "$temp\PlaybackModels.kt", "$temp\ConcertModels.kt") + @(
        'app/src/main/java/com/lastwave/app/data/lyrics/CustomLyrics.kt',
        'app/src/main/java/com/lastwave/app/data/lyrics/LyricsTranslation.kt',
        'app/src/main/java/com/lastwave/app/playback/ConcertListeningState.kt',
        'app/src/main/java/com/lastwave/app/data/download/DownloadEnrichment.kt',
        'app/src/test/java/com/lastwave/app/data/lyrics/CustomLyricsTest.kt',
        'app/src/test/java/com/lastwave/app/playback/ConcertListeningStateTest.kt',
        'app/src/test/java/com/lastwave/app/data/download/DownloadEnrichmentTest.kt'
    ) | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $repo $_ } }
    & java -cp "$KotlinLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d "$temp\classes" @sources
    if ($LASTEXITCODE -ne 0) { throw 'Portable feature-core compilation failed.' }
    & java -cp "$temp\classes;$classpath" org.junit.runner.JUnitCore com.lastwave.app.data.lyrics.CustomLyricsTest com.lastwave.app.playback.ConcertListeningStateTest com.lastwave.app.data.download.DownloadEnrichmentTest
    if ($LASTEXITCODE -ne 0) { throw 'Feature-core tests failed.' }
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf).StartsWith('lastwave-core-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
