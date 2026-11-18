$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$names=@('light_tap.ogg','end_cue.ogg','swipe_cue.ogg','double_tap.ogg','page_turn.ogg','record_start.wav')
foreach($name in $names) {
    $bytes=[IO.File]::ReadAllBytes("$root/app/src/main/assets/sfx/$name")
    if($name.EndsWith('.ogg')) {
        $pos=0; $sampleRate=0; $granule=0L
        while($pos -lt $bytes.Length) {
            if($pos+27 -gt $bytes.Length -or [Text.Encoding]::ASCII.GetString($bytes,$pos,4) -ne 'OggS'){throw "Ogg页损坏：$name"}
            $segments=[int]$bytes[$pos+26]; $payload=0
            if($pos+27+$segments -gt $bytes.Length){throw 'Ogg片段表损坏'}
            for($i=0;$i -lt $segments;$i++){ $payload += $bytes[$pos+27+$i] }
            $start=$pos+27+$segments
            if($start+$payload -gt $bytes.Length){throw 'Ogg页越界'}
            if($pos -eq 0) {
                if($payload -lt 16 -or $bytes[$start] -ne 1 -or [Text.Encoding]::ASCII.GetString($bytes,$start+1,6) -ne 'vorbis'){throw '不是Vorbis音频'}
                $sampleRate=[BitConverter]::ToInt32($bytes,$start+12)
            }
            $g=[BitConverter]::ToInt64($bytes,$pos+6)
            if($g -ge 0){$granule=[Math]::Max($granule,$g)}
            $pos=$start+$payload
        }
        if($sampleRate -le 0){throw '无有效采样率'}
        $ms=1000.0*$granule/$sampleRate
    } else {
        if([Text.Encoding]::ASCII.GetString($bytes,0,4) -ne 'RIFF' -or [Text.Encoding]::ASCII.GetString($bytes,8,4) -ne 'WAVE'){throw '无有效WAV头'}
        $pos=12; $rate=0; $data=0
        while($pos+8 -le $bytes.Length) {
            $kind=[Text.Encoding]::ASCII.GetString($bytes,$pos,4); $n=[BitConverter]::ToUInt32($bytes,$pos+4)
            if($pos+8+$n -gt $bytes.Length){throw 'WAV数据越界'}
            if($kind -eq 'fmt ' -and $n -ge 16){$rate=[BitConverter]::ToUInt32($bytes,$pos+16)}
            if($kind -eq 'data'){$data += $n}
            $pos += 8+$n+($n%2)
        }
        if($rate -le 0){throw '无有效WAV字节率'}
        $ms=1000.0*$data/$rate
    }
    if($ms -le 0 -or $ms -gt 5000){throw "音频时长异常：$name"}
    Write-Output "PASS $name containerDurationMs=$([Math]::Round($ms))"
}
Write-Output 'PASS 6 audio container checks; no playback or device data accessed'
