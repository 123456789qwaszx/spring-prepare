# scripts/api.ps1 — 서버 API 를 손으로 부르는 도구 (PowerShell 5.1 이상)
#
# 쓰는 법 (한 번):
#   . .\scripts\api.ps1                              # 점 + 공백 + 경로 (dot-source)
#   Ked-Connect -AdminKey '<app.admin-key 값>'       # 기본 http://localhost:8080
#   Ked-Login m8 secret-pw                           # 없는 사용자면 가입까지
# 그다음:
#   Ked GET "/users/$($KED.UserId)/playthroughs"     # Bearer 토큰
#   Ked PUT "/users/$($KED.UserId)/bookmarks/bm1" '{"label":"x", ...}'
#   Ked-Admin GET /stats/events                      # 관리자 키
#   Ked-Import 'C:\...\qwer.progression.json'        # 콘텐츠 수입 (-InFile, 바이트 그대로)
#   Test-Ked / Stop-Ked                              # 서버 생존 확인 / 8080 프로세스 종료
#
# 출력은 언제나 두 줄: 상태 코드, 본문. 화면이 해석하지 않는다 — 서버의 {code, message} 가 그대로 보인다.
#
# PS 5.1 함정 셋을 여기서 막는다 (docs/RUNBOOK.md §7):
#   - 응답: $r.Content 는 charset 없는 JSON 을 ISO-8859-1 로 읽어 한글을 깨뜨린다 → 바이트를 UTF-8 로 직접 읽는다.
#   - 요청: 문자열 -Body 는 재인코딩된다 → UTF-8 바이트로 보낸다. 파일은 -InFile.
#   - 본문 없는 POST 에 PS 가 form Content-Type 을 몰래 붙인다 → 본문이 없으면 Content-Type 을 아예 안 보낸다(Unity 와 같은 모양).

$global:KED = @{ Base = 'http://localhost:8080'; AdminKey = $null; Token = $null; UserId = $null; Username = $null }

function Ked-Connect {
    param([string]$Base = 'http://localhost:8080', [string]$AdminKey)
    $global:KED.Base = $Base.TrimEnd('/')
    if ($AdminKey) { $global:KED.AdminKey = $AdminKey }
    "base=$($global:KED.Base) adminKey=$(if ($global:KED.AdminKey) { '설정됨' } else { '없음' })"
}

# 핵심 호출. 결과는 "상태`n본문" 문자열. 실패(4xx/5xx)도 같은 모양 — throw 하지 않는다.
function Invoke-Ked {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Body,
        [hashtable]$Headers = @{},
        [string]$InFile
    )
    $args = @{ Method = $Method; Uri = ($global:KED.Base + $Path); UseBasicParsing = $true; Headers = $Headers }
    if ($InFile) {
        $args.ContentType = 'application/json'
        $args.InFile = $InFile
    } elseif ($Body) {
        $args.ContentType = 'application/json; charset=utf-8'
        $args.Body = [Text.Encoding]::UTF8.GetBytes($Body)
    }
    try {
        $r = Invoke-WebRequest @args
        "{0}`n{1}" -f [int]$r.StatusCode, [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }   # HTTP 응답이 없는 예외(연결 거부 등)는 그대로 보인다
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
        "{0}`n{1}" -f [int]$resp.StatusCode, $text
    }
}

# Bearer 토큰으로. Ked GET /path  /  Ked PUT /path '{...}'
function Ked {
    param([Parameter(Mandatory)][string]$Method, [Parameter(Mandatory)][string]$Path, [string]$Body)
    if (-not $global:KED.Token) { Write-Warning '토큰 없음 — 먼저 Ked-Login'; return }
    Invoke-Ked -Method $Method -Path $Path -Body $Body -Headers @{ Authorization = 'Bearer ' + $global:KED.Token }
}

# 관리자 키로. Ked-Admin GET /stats/events
function Ked-Admin {
    param([Parameter(Mandatory)][string]$Method, [Parameter(Mandatory)][string]$Path, [string]$Body)
    if (-not $global:KED.AdminKey) { Write-Warning '관리자 키 없음 — Ked-Connect -AdminKey'; return }
    Invoke-Ked -Method $Method -Path $Path -Body $Body -Headers @{ 'X-Admin-Key' = $global:KED.AdminKey }
}

# 로그인. 401 이면 가입하고 다시 로그인한다. 토큰·userId 는 $KED 에.
function Ked-Login {
    param([Parameter(Mandatory)][string]$Username, [Parameter(Mandatory)][string]$Password)
    $cred = '{"username":"' + $Username + '","password":"' + $Password + '"}'
    $out = Invoke-Ked -Method POST -Path '/auth/login' -Body $cred
    $status, $body = $out -split "`n", 2
    if ([int]$status -eq 401) {
        $signup = Invoke-Ked -Method POST -Path '/users' -Body $cred
        "가입: $($signup -replace "`n", ' ')"
        $out = Invoke-Ked -Method POST -Path '/auth/login' -Body $cred
        $status, $body = $out -split "`n", 2
    }
    if ([int]$status -ne 200) { Write-Warning "로그인 실패 — $status $body"; return }
    $json = $body | ConvertFrom-Json
    $global:KED.Token = $json.token
    $global:KED.UserId = $json.userId
    $global:KED.Username = $Username
    "로그인 — $Username (userId $($json.userId)), 만료 $($json.expiresAt)"
}

# 콘텐츠 수입. 반드시 -InFile — 재인코딩하면 checksum 이 달라져 Unity 가 버전을 못 찾는다 (D-015, F45).
function Ked-Import {
    param([Parameter(Mandatory)][string]$File)
    if (-not $global:KED.AdminKey) { Write-Warning '관리자 키 없음 — Ked-Connect -AdminKey'; return }
    if (-not (Test-Path $File)) { Write-Warning "파일 없음: $File"; return }
    Invoke-Ked -Method POST -Path '/content/chapters' -InFile $File -Headers @{ 'X-Admin-Key' = $global:KED.AdminKey }
}

# 서버가 살아 있나. 공개 GET 하나로 본다.
function Test-Ked {
    try {
        $null = Invoke-WebRequest -Uri ($global:KED.Base + '/content/chapters') -UseBasicParsing -TimeoutSec 3
        "살아 있음 — $($global:KED.Base)"
    } catch {
        if ($_.Exception.Response) { "살아 있음 — HTTP $([int]$_.Exception.Response.StatusCode)" }
        else { "죽어 있음 — $($global:KED.Base)" }
    }
}

# 8080 을 LISTENING 중인 프로세스를 죽인다. bootRun 은 Ctrl+C 로 안 죽는다 (M7-check §2).
function Stop-Ked {
    $port = ([uri]$global:KED.Base).Port
    $lines = netstat -ano | Select-String ":$port\s+.*LISTENING\s+(\d+)$"
    if (-not $lines) { "포트 $port 에 아무것도 없음"; return }
    $pids = $lines | ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
    foreach ($p in $pids) { taskkill /PID $p /F | Out-Null; "PID $p 종료" }
}

# seed 는 Workbench 에서 한다 — 안내만.
function Ked-Seed {
    @'
seed (db/seed.sql) 는 Workbench 에서:
  1. 좌측 SCHEMAS 에서 game 을 더블클릭해 기본 스키마로 (굵게).   확인: SELECT DATABASE();
  2. File > Open SQL Script > db\seed.sql > Execute All (Ctrl+Shift+Enter).
  ! game 의 모든 데이터가 지워진다. 기대 숫자는 StatsApiTest 상수 (회차 20, EP01 50/30/20, 도달률 75/40/20).
'@
}

"api.ps1 로드됨 — Ked-Connect, Ked-Login, Ked, Ked-Admin, Ked-Import, Test-Ked, Stop-Ked, Ked-Seed"
