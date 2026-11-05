param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$EvidenceDir = ".work/lock-screen-reminder",
    [switch]$ExitProcess,
    # 提前量越大，应用越可能在到点前被厂商后台策略冻结；验收矩阵需要覆盖这两种情形。
    [int]$DelaySec = 45
)
$ErrorActionPreference = 'Stop'
$package = 'com.voicetodo.app'
$driver = "$package/com.voicetodo.debug.AlarmTestDriverReceiver"
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $result" }
    return ($result -join "`n")
}
function Save-Snapshot([string]$Name, [string[]]$Arguments) {
    $text = Invoke-Adb $Arguments
    [IO.File]::WriteAllText((Join-Path $EvidenceDir "$Name.txt"), $text)
    return $text
}

# 不清空数据、不更改权限或音量；只新增并最终删除本次测试事项。
$itemId = $null
try {
    Invoke-Adb @('shell', 'input', 'keyevent', '224') | Out-Null
        # 唤醒并进入前台再调度：厂商系统会丢弃发给后台空闲应用的 adb 广播，
        # 这不是验收项本身要观察的行为。
        Invoke-Adb @('shell', 'am', 'start', '-n', "$package/com.voicetodo.MainActivity") | Out-Null
        Start-Sleep -Seconds 2
    $scheduled = Save-Snapshot 'schedule' @('shell', 'am', 'broadcast', '-a',
        'com.voicetodo.debug.SCHEDULE', '-n', $driver, '--ei', 'delaySec', "$DelaySec")
    if ($scheduled -notmatch 'scheduledItem=([a-zA-Z0-9-]+)') { throw 'No scheduled item returned' }
    $itemId = $Matches[1]
    if ($scheduled -notmatch 'fireAt=(\d+)') { throw 'No trigger time returned' }
    $fireAt = [long]$Matches[1]
    Invoke-Adb @('shell', 'input', 'keyevent', '3') | Out-Null
    if ($ExitProcess) {
        Invoke-Adb @('shell', 'am', 'broadcast', '-a', 'com.voicetodo.debug.EXIT', '-n', $driver) | Out-Null
        Start-Sleep -Seconds 1
        $remainingPid = & $Adb -s $Serial shell pidof $package
        if ($remainingPid) { throw "Process did not exit: $remainingPid" }
    }
    Invoke-Adb @('shell', 'input', 'keyevent', '223') | Out-Null
    $before = Save-Snapshot 'before-alarm' @('shell', 'dumpsys', 'alarm')
    if ($before -notmatch 'RTC_WAKEUP[^\r\n]*com\.voicetodo\.app') { throw 'Alarm was not registered' }
    $state = Save-Snapshot 'before-package' @('shell', 'dumpsys', 'package', $package)
    if ($state -notmatch 'stopped=false') { throw 'App is force-stopped, not normal background' }
    $power = Save-Snapshot 'before-power' @('shell', 'dumpsys', 'power')
    if ($power -notmatch 'mWakefulness=(Asleep|Dozing)') { throw 'Device did not sleep' }

    # 以设备时钟为准，避免宿主时钟或时区偏差。
    $nowText = ''
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $candidate = (Invoke-Adb @('shell', 'date', '+%s')).Trim()
        if ($candidate -match '^\d+$') {
            $nowText = $candidate
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($nowText -notmatch '^\d+$') { throw "Unexpected device time: $nowText" }
    $now = [long]::Parse($nowText, [Globalization.CultureInfo]::InvariantCulture) * 1000
    $wait = [Math]::Max(1, [Math]::Ceiling(($fireAt - $now) / 1000) + 3)
    if ($wait -gt ($DelaySec + 30)) { throw "Unexpected trigger time: wait=$wait" }
    Start-Sleep -Seconds $wait
    Save-Snapshot 'after-alarm' @('shell', 'dumpsys', 'alarm') | Out-Null
    Save-Snapshot 'after-broadcasts' @('shell', 'dumpsys', 'activity', 'broadcasts', $package) | Out-Null
    $notifications = Save-Snapshot 'after-notifications' @('shell', 'dumpsys', 'notification')
    $services = Save-Snapshot 'after-services' @('shell', 'dumpsys', 'activity', 'services', $package)
    $audio = Save-Snapshot 'after-audio' @('shell', 'dumpsys', 'audio')
    $activities = Save-Snapshot 'after-activities' @('shell', 'dumpsys', 'activity', 'activities')
    Save-Snapshot 'logcat' @('logcat', '-d', '-v', 'threadtime', 'AlarmFix:I', 'AlarmReceiver:V',
        'RingingService:V', 'ActivityManager:I', 'ActivityTaskManager:I', 'MediaPlayer:V', '*:S') | Out-Null
    if ($services -notmatch 'isForeground=true') { throw 'No ringing foreground service' }
    if ($notifications -notmatch 'pkg=com.voicetodo.app') { throw 'No reminder notification' }
    $uid = [regex]::Match($state, '(?:userId|appId)=(\d+)').Groups[1].Value
    if (-not $uid) { throw 'Cannot identify app UID' }
    if ($audio -notmatch "u/pid:$uid/[^\r\n]*state:started[^\r\n]*usage=USAGE_ALARM") { throw 'No playing alarm audio' }
    if ($activities -notmatch '(mResumedActivity|topResumedActivity)[^\r\n]*com.voicetodo.alarm.AlarmRingActivity') {
        throw 'No full-screen reminder'
    }

    Invoke-Adb @('shell', 'screencap', '-p', '/sdcard/issue25-ringing.png') | Out-Null
    Invoke-Adb @('pull', '/sdcard/issue25-ringing.png', (Join-Path $EvidenceDir 'ringing.png')) | Out-Null
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/issue25-ui.xml') | Out-Null
    $ui = Invoke-Adb @('shell', 'cat', '/sdcard/issue25-ui.xml')
    [IO.File]::WriteAllText((Join-Path $EvidenceDir 'ui.xml'), $ui)
    [xml]$tree = $ui
    $button = $tree.SelectSingleNode('//node[@text="关闭本次提醒"]')
    if (-not $button -or $button.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        throw 'Dismiss button not found'
    }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Seconds 1
    $stopped = Save-Snapshot 'closed-services' @('shell', 'dumpsys', 'activity', 'services', $package)
    if ($stopped -match 'isForeground=true') { throw 'Dismiss did not stop ringing service' }
    $closedAudio = Save-Snapshot 'closed-audio' @('shell', 'dumpsys', 'audio')
    if ($closedAudio -match "u/pid:$uid/[^\r\n]*state:started[^\r\n]*usage=USAGE_ALARM") {
        throw 'Dismiss did not stop alarm audio'
    }
    $closedNotifications = Save-Snapshot 'closed-notifications' @('shell', 'dumpsys', 'notification')
    if ($closedNotifications -match 'NotificationRecord\([^\r\n]*pkg=com.voicetodo.app') {
        throw 'Dismiss did not remove reminder notification'
    }
    "PASS: lock-screen reminder, alarm playback and dismiss; ExitProcess=$ExitProcess; item=$itemId"
} finally {
    if ($itemId) {
        Invoke-Adb @('shell', 'am', 'broadcast', '-a', 'com.voicetodo.debug.CLEANUP', '-n', $driver,
            '--es', 'itemId', $itemId) | Out-Null
    }
}
