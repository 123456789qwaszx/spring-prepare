# M1 검증 절차 — 콘텐츠 수입·배포

> PLAN §2.6. 위에서 아래로 따라가면 M1 완료 기준을 전부 확인한다. 결과는 §6 표에.
> 선행: M0 `검증됨`. 결정 D-006(body=JSON), D-007(definition checksum) 반영됨.

---

## 0. 스키마 마이그레이션 (한 번만)

`db/migrations/V2__gamedef_checksum.sql` 을 **`game` 과 `game_test` 두 DB 모두에** 적용한다.
Flyway 도입 전이라 수동이다 — 이 불편이 PLAN#5(M6)의 근거다.

Workbench 에서:

```sql
USE game;
ALTER TABLE game_definitions
    ADD COLUMN checksum CHAR(64) NOT NULL AFTER body,
    ADD UNIQUE KEY uk_gamedef_checksum (checksum);

USE game_test;
ALTER TABLE game_definitions
    ADD COLUMN checksum CHAR(64) NOT NULL AFTER body,
    ADD UNIQUE KEY uk_gamedef_checksum (checksum);
```

확인:

```sql
SHOW COLUMNS FROM game.game_definitions;       -- checksum 이 body 뒤에
SHOW COLUMNS FROM game_test.game_definitions;
```

> 이 테이블에 행이 있으면 `NOT NULL` 추가가 실패한다. M1 이전에 definition 을 넣은 적이 없으므로 비어 있어야 정상이다.
> `docs/schema.sql` 은 **고치지 않는다** — 스키마 변경은 마이그레이션 파일로 남긴다 (PLAN §3).
> 새 환경을 만들 때는 `schema.sql` → `V2__…` 순으로 적용한다.

---

## 1. 샘플 파일

`src/test/resources/content/qwer.progression.json` (5,686바이트, CRLF).
Unity 레포 `Assets/@Dialogue/ChapterProgression/` 에서 복사한 실제 산출물이다.

미리 알아 둘 값 — 아래 확인의 기대값이 된다.

| 항목 | 값 |
|---|---|
| ChapterId / StartEpisodeId | `qwer` / `EP01` |
| 노드 수 | 8 (EP01, EP02_01, EP02_02, EP02_03, EP03_01, EP03_02, EP04_01, EP04_02) |
| 선택지 수 | EP01=3, EP03_02=2, EP02_01=EP02_02=EP02_03=1, EP03_01=EP04_01=EP04_02=0 |
| EventKey | **전부 빈 문자열** (M3 이벤트 테스트에는 변형 파일이 필요하다) |
| SHA-256 | `785b808cc6e7aaeca791d948a41769b7ad6cb077902dd6f98ce3ac766edfbda1` |

---

## 2. 서버 실행

```powershell
.\gradlew.bat bootRun
```

M1 에서 처음 쓰는 것: Jackson 3(`tools.jackson`), `NamedParameterJdbcTemplate`(배치). 기동에 실패하면 로그 전문을 공유.

---

## 3. API 시나리오 (PowerShell)

파일을 **바이트 그대로** 보내야 한다. `-Body (Get-Content -Raw)` 는 문자열로 읽었다가 다시 인코딩하므로 체크섬이 달라질 수 있다. `-InFile` 을 쓴다.

M0 에서 만든 `Call-Api` 대신 파일용 헬퍼를 하나 더 둔다.

```powershell
function Post-File {
    param($Uri, $Path)
    try {
        $r = Invoke-WebRequest -Method Post -Uri $Uri -ContentType 'application/json' -InFile $Path
        "{0}`n{1}" -f [int]$r.StatusCode, $r.Content
    } catch {
        "{0}`n{1}" -f [int]$_.Exception.Response.StatusCode, $_.ErrorDetails.Message
    }
}
$SAMPLE = "src\test\resources\content\qwer.progression.json"
```

### 3.1 첫 수입 → 201, version 1

```powershell
Post-File 'http://localhost:8080/content/chapters' $SAMPLE
```

기대: `201` / `{"chapterId":"qwer","version":1,"episodeCount":8}`

### 3.2 같은 파일 재수입 → 200, 행이 늘지 않음

```powershell
Post-File 'http://localhost:8080/content/chapters' $SAMPLE
```

기대: `200` / `{"chapterId":"qwer","version":1,"episodeCount":8}`

**409 가 아니라 200 인 것이 핵심이다.** 정상 경로는 checksum 조회이고, UNIQUE 제약은 그 로직이 뚫렸을 때의 안전망이다.

### 3.3 바이트를 바꾸면 새 버전 → 201, version 2

```powershell
Copy-Item $SAMPLE "$env:TEMP\qwer2.json"
Add-Content -Path "$env:TEMP\qwer2.json" -Value ""     # 개행 하나 추가
Post-File 'http://localhost:8080/content/chapters' "$env:TEMP\qwer2.json"
```

기대: `201` / `version: 2`.

JSON 의 **의미는 완전히 같은데** 버전이 오른다. 재수입 판정이 바이트 기준이라는 뜻이고, 버그가 아니라 정의다 (`common/Checksum` 주석 참조). VnTool 이 내보내기 형식을 바꾸면 같은 일이 일어난다.

### 3.4 목록·버전·본문 조회

```powershell
Invoke-RestMethod 'http://localhost:8080/content/chapters'
# [{ chapterId = qwer; latestVersion = 2; displayName = qwer }]   ← 챕터마다 최신 한 줄

Invoke-RestMethod 'http://localhost:8080/content/chapters/qwer/versions'
# version 1, 2 각각 importedAt 과 checksum

Invoke-WebRequest 'http://localhost:8080/content/chapters/qwer/1' | Select-Object -Expand Content
Invoke-WebRequest 'http://localhost:8080/content/chapters/qwer/latest' | Select-Object -Expand Content
```

### 3.5 원본 보존 확인 (D-006 의 실물)

```powershell
Invoke-WebRequest 'http://localhost:8080/content/chapters/qwer/1' -OutFile "$env:TEMP\down.json"
(Get-Item $SAMPLE).Length            # 5686
(Get-Item "$env:TEMP\down.json").Length   # 약 3240 — 43% 가 사라졌다
```

바이트는 다르다. MySQL `JSON` 컬럼이 공백·들여쓰기를 제거하고 키 순서를 정규화했기 때문이다.
**의미가 같은지**는 파싱해서 본다.

```powershell
$a = Get-Content $SAMPLE -Raw | ConvertFrom-Json
$b = Get-Content "$env:TEMP\down.json" -Raw | ConvertFrom-Json
$a.ChapterId -eq $b.ChapterId
$a.Nodes.Count -eq $b.Nodes.Count
$a.Nodes[0].NextOptions[0].ChoiceLabel      # "선택지 골라." — 한글이 살아 있어야 한다
$b.Nodes[0].NextOptions[0].ChoiceLabel
```

한글이 깨지면 `StringHttpMessageConverter` 의 charset 문제다 (`ChapterContentController` 주석 참조).

### 3.6 400 두 가지

```powershell
$tmp = "$env:TEMP\bad.json"
'{ "ChapterId": "x", "StartEpisodeId": "EP01", "Nodes": [] }' | Set-Content $tmp -Encoding UTF8
Post-File 'http://localhost:8080/content/chapters' $tmp
# → 400 BAD_REQUEST  (Nodes 가 비었다)

'{ "StartEpisodeId": "EP01", "Nodes": [ { "EpisodeId": "EP01" } ] }' | Set-Content $tmp -Encoding UTF8
Post-File 'http://localhost:8080/content/chapters' $tmp
# → 400 BAD_REQUEST  (ChapterId 가 없다)
```

### 3.7 롤백 확인 — M1 의 핵심

같은 `EpisodeId` 가 두 번 들어간 챕터를 올린다. `chapter_episodes` 의 PK `(chapter_content_id, episode_id)` 가 막는다.

```powershell
$dup = "$env:TEMP\dup.json"
@'
{
  "ChapterId": "dup",
  "DisplayName": "중복 에피소드",
  "StartEpisodeId": "EP01",
  "Stats": [],
  "Nodes": [
    { "EpisodeId": "EP01", "Title": "", "EventKey": "", "NextOptions": [] },
    { "EpisodeId": "EP01", "Title": "", "EventKey": "", "NextOptions": [] }
  ]
}
'@ | Set-Content $dup -Encoding UTF8
Post-File 'http://localhost:8080/content/chapters' $dup
```

기대: **4xx**. 상태 코드가 409(`DUPLICATE`)일지 400(`CONSTRAINT_VIOLATION`)일지는 드라이버가 배치 실패를 어떤 SQLState 로 보고하는지에 달렸다 — **어느 쪽이 나왔는지 §6 에 적어 두자.** M1 이 지키는 것은 상태 코드가 아니라 그다음이다.

```sql
SELECT COUNT(*) FROM game.chapter_contents WHERE chapter_id = 'dup';   -- 0 이어야 한다
```

본문 INSERT 는 성공했다가 색인에서 실패했는데도 **아무것도 남지 않는다.** 이것이 `@Transactional` 하나가 한 일이다.

### 3.8 definition

```powershell
$def = "$env:TEMP\game.definition.json"
'{ "SchemaVersion": 1, "Stats": [], "Unlocks": [] }' | Set-Content $def -Encoding UTF8

Post-File 'http://localhost:8080/content/definition' $def     # 201 {"version":1}
Post-File 'http://localhost:8080/content/definition' $def     # 200 {"version":1}   ← V2 마이그레이션의 checksum
Invoke-WebRequest 'http://localhost:8080/content/definition/latest' | Select -Expand Content
```

> `Set-Content -Encoding UTF8` 은 PowerShell 5.1 에서 **BOM 을 붙인다.** BOM 이 붙으면 바이트가 달라져 체크섬이 바뀌고, Jackson 도 BOM 을 만나면 파싱에 실패할 수 있다. 재수입이 200 이 아니라 계속 201 이 나오면 이것을 의심한다 (PowerShell 7 은 BOM 없는 UTF-8 이 기본).

---

## 4. Workbench 로 직접 보기

```sql
USE game;

-- 수입된 콘텐츠
SELECT id, chapter_id, version, display_name, start_episode_id,
       LEFT(checksum, 12) AS checksum_head, imported_at
FROM chapter_contents ORDER BY chapter_id, version;

-- 색인 — 노드 수만큼, option_count 가 정확한가
SELECT chapter_content_id, episode_id, event_key, option_count
FROM chapter_episodes ORDER BY chapter_content_id, episode_id;

-- JSON 컬럼이 정말 파싱된 상태로 들어갔는가
SELECT id, JSON_LENGTH(body, '$.Nodes') AS node_count, LENGTH(body) AS stored_bytes
FROM chapter_contents;
-- node_count = 8, stored_bytes 는 원본 5686 보다 작다 (정규화)

-- M5 예고편: 라벨을 JSON 에서 뽑아 보기
SELECT JSON_UNQUOTE(JSON_EXTRACT(body, '$.Nodes[0].NextOptions[0].ChoiceLabel')) AS label
FROM chapter_contents WHERE chapter_id = 'qwer' AND version = 1;
-- "선택지 골라."
```

`JSON_EXTRACT` 가 이렇게 자연스럽게 되는 것이 D-006 에서 `JSON` 을 고른 이유다. `LONGTEXT` 였다면 매번 `CAST(body AS JSON)` 이 필요했다.

---

## 5. 자동 테스트

```powershell
.\gradlew.bat test --tests "com.sparta.springprepare.common.ChecksumTest" `
                   --tests "com.sparta.springprepare.content.*"
```

기대: `ChecksumTest` 5건 + `ChapterContentApiTest` 10건 + `GameDefinitionApiTest` 6건.

자주 걸리는 곳:

| 메시지 | 원인 |
|---|---|
| `Unknown column 'checksum'` | §0 마이그레이션을 `game_test` 에 안 함 |
| `테스트 리소스가 없다: /content/qwer.progression.json` | 샘플 파일이 `src/test/resources/content/` 에 없음 |
| `episodeCount` 가 8 이 아님 | 샘플 파일이 다른 버전. §1 의 SHA-256 으로 대조 |
| 롤백 테스트만 실패 | 배치 INSERT 가 PK 위반을 다르게 보고. 예외 클래스명을 공유 |

---

## 6. 결과 기록

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §0 마이그레이션 | 두 DB 모두 checksum 컬럼 | | |
| §2 bootRun | 기동 성공 | | |
| §3.1 첫 수입 | 201, v1, 8 | | |
| §3.2 재수입 | **200**, v1, 행 그대로 | | |
| §3.3 바이트 변경 | 201, v2 | | |
| §3.4 조회 | 목록/버전/본문 | | |
| §3.5 원본 보존 | 바이트↓, 의미 동일, 한글 정상 | | |
| §3.6 400 두 가지 | BAD_REQUEST | | |
| §3.7 롤백 | 4xx + 두 테이블 0행 | | 상태 코드를 적을 것 → |
| §3.8 definition | 201 → 200 | | |
| §5 테스트 | 21건 통과 | | |
