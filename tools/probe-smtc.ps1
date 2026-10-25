# SPW SMTC 能力探测脚本
# 用途：查询 Windows 媒体会话（SMTC）里各播放器暴露了哪些字段
# 关键判断：Salt Player for Windows 是否出现，以及是否提供可用的播放位置（timeline）
# 用法：保持 SPW 正在播放音乐，然后运行本脚本

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]

Write-Host "=============================================="
Write-Host " Windows SMTC session probe"
Write-Host "=============================================="
Write-Host ""

try {
    $mgr = Await ($mgrType::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
} catch {
    Write-Host "[ERROR] Cannot request SMTC manager: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$sessions = $mgr.GetSessions()
Write-Host "Found $($sessions.Count) media session(s)."
Write-Host ""

if ($sessions.Count -eq 0) {
    Write-Host "No active media session." -ForegroundColor Yellow
    Write-Host "Start playing a song in Salt Player for Windows, then run this script again."
    exit 0
}

$index = 0
foreach ($s in $sessions) {
    $index++
    Write-Host "----------------------------------------------"
    Write-Host "[Session $index]  $($s.SourceAppUserModelId)"
    Write-Host "----------------------------------------------"

    try {
        $ai = $s.GetPlaybackInfo()
        Write-Host ("  PlaybackStatus : {0}" -f $ai.PlaybackStatus)
        Write-Host ("  AutoRepeatMode : {0}" -f $ai.AutoRepeatMode)
        Write-Host ("  PlaybackType   : {0}" -f $ai.PlaybackType)
    } catch {
        Write-Host "  [PlaybackInfo unavailable] $($_.Exception.Message)" -ForegroundColor DarkYellow
    }

    try {
        $p = Await ($s.TryGetMediaPropertiesAsync()) ($propsType)
        Write-Host ("  Title          : {0}" -f $p.Title)
        Write-Host ("  Artist         : {0}" -f $p.Artist)
        Write-Host ("  AlbumTitle     : {0}" -f $p.AlbumTitle)
        Write-Host ("  AlbumArtist    : {0}" -f $p.AlbumArtist)
        Write-Host ("  TrackNumber    : {0}" -f $p.TrackNumber)
        Write-Host ("  Genres         : {0}" -f ($p.Genres -join ' | '))
        Write-Host ("  HasThumbnail   : {0}" -f ($null -ne $p.Thumbnail))
    } catch {
        Write-Host "  [MediaProperties unavailable] $($_.Exception.Message)" -ForegroundColor DarkYellow
    }

    try {
        $tl = $s.GetTimelineProperties()
        $pos = $tl.Position.TotalMilliseconds
        $start = $tl.StartTime.TotalMilliseconds
        $end = $tl.EndTime.TotalMilliseconds
        Write-Host ("  Timeline       : position={0} ms  start={1} ms  end={2} ms" -f $pos, $start, $end)
        if ($end -gt 0) {
            $dur = $end - $start
            Write-Host ("  Duration       : {0:N1} s" -f ($dur / 1000))
            Write-Host ("  POSITION OK    : YES  (timeline usable)" ) -ForegroundColor Green
        } else {
            Write-Host "  POSITION OK    : NO   (end time is 0 - scrolling lyrics will NOT work)" -ForegroundColor Red
        }
    } catch {
        Write-Host "  [Timeline unavailable] $($_.Exception.Message)" -ForegroundColor DarkYellow
    }

    Write-Host ""
}

$mgr = $null
[GC]::Collect()
[GC]::WaitForPendingFinalizers()

Write-Host "=============================================="
Write-Host " Done. If SPW appears above with POSITION OK = YES,"
Write-Host " existing taskbar-lyrics apps can drive it directly."
Write-Host "=============================================="
