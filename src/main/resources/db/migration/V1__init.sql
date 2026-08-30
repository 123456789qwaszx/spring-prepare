-- =====================================================================
--  V1__init.sql — 스키마 v1 (= docs/schema.sql)
-- =====================================================================
--  docs/schema.sql 이 DDL 의 읽기용 정본이고, **적용은 이 파일이 한다** (D-012).
--  두 파일의 관계는 docs/M6-check.md 에 적혀 있다. 내용을 고칠 일이 생기면
--  여기(V1)를 고치는 것이 아니라 새 V{n} 마이그레이션을 더한다 — V1 은 이미 적용된 과거다.
--
--  schema.sql 과 다른 점 하나: 머리의 `CREATE DATABASE`/`USE game` 두 줄을 **뺐다** (D-012 갱신).
--  Flyway 는 접속 URL 의 스키마(game 또는 game_test) 안에서 이 파일을 실행하는데,
--  `USE game` 이 남아 있으면 game_test 마이그레이션이 도중에 game 으로 갈아타
--  테이블은 전부 game 에 생기고 이력만 game_test 에 남는다 — R4 류의 조용한 드리프트다.
--  (db/seed.sql 에 스키마 이름이 없는 것과 같은 이유. DB 생성은 사람이 한다 — M6-check §1.)
-- =====================================================================

-- ked VN 게임 서버 스키마 v1
-- 역할: 콘텐츠 창고 + 세이브 금고. 판정·검증 없음.
-- MySQL 8.x. (V1 에서는 Flyway 가 실행한다. Workbench 로 직접 볼 때는 docs/schema.sql 을 연다.)


-- ─────────────────────────────────────────────
-- 계정 (M0)
-- ─────────────────────────────────────────────
CREATE TABLE users (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(30)  NOT NULL,
    password   VARCHAR(100) NOT NULL,          -- M6에서 해시로 전환
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username)
);

-- 어느 기기에서 올린 세이브인지. 충돌 해소(M5)의 근거.
CREATE TABLE devices (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    device_key   VARCHAR(64)  NOT NULL,        -- 클라가 생성한 설치 고유값
    display_name VARCHAR(50)  NOT NULL DEFAULT '',
    last_seen_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_devices_user_key (user_id, device_key),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- ─────────────────────────────────────────────
-- 콘텐츠 창고 (M1) — VnTool 산출물을 버전 붙여 보관
-- ─────────────────────────────────────────────

-- game.definition.json: 스탯 카탈로그, EventKey → 해금 규칙 등.
-- 클라와 서버가 같은 파일을 읽는다. 서버는 해석하지 않는다.
CREATE TABLE game_definitions (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version     INT      NOT NULL,
    body        JSON     NOT NULL,
    imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gamedef_version (version)
);

-- *.progression.json 한 개 = 한 행. 같은 챕터를 다시 내보내면 version이 오른다.
CREATE TABLE chapter_contents (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    chapter_id       VARCHAR(50)  NOT NULL,     -- JSON의 ChapterId
    version          INT          NOT NULL,
    display_name     VARCHAR(100) NOT NULL DEFAULT '',
    start_episode_id VARCHAR(50)  NOT NULL,
    body             JSON         NOT NULL,     -- 원본 그대로
    checksum         CHAR(64)     NOT NULL,     -- SHA-256, 같은 파일 재수입 방지
    imported_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_version (chapter_id, version),
    UNIQUE KEY uk_chapter_checksum (checksum)
);

-- 수입 시 JSON에서 뽑아 두는 에피소드 색인.
-- 목적: event_log·choice_history를 JOIN으로 읽기 위함. 판정용 아님.
CREATE TABLE chapter_episodes (
    chapter_content_id BIGINT       NOT NULL,
    episode_id         VARCHAR(50)  NOT NULL,
    title              VARCHAR(100) NOT NULL DEFAULT '',
    event_key          VARCHAR(50)  NOT NULL DEFAULT '',
    option_count       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (chapter_content_id, episode_id),
    CONSTRAINT fk_episodes_content FOREIGN KEY (chapter_content_id)
        REFERENCES chapter_contents(id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────
-- 세이브 금고 (M2~M5)
-- ─────────────────────────────────────────────

-- [1] 영구 계층의 그릇. 회차 하나 = 새 게임 한 번.
CREATE TABLE playthroughs (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at   DATETIME NULL,
    CONSTRAINT fk_playthroughs_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- [2]+[3] 스냅샷. 슬롯 하나 = 클라 로컬 세이브 하나.
-- snapshot = { nodeName, lineId, 변수, StageState, ProgressionState } 통째로.
CREATE TABLE save_slots (
    id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
    playthrough_id     BIGINT      NOT NULL,
    slot_no            TINYINT     NOT NULL,
    chapter_content_id BIGINT      NOT NULL,   -- 어느 버전의 챕터에서 만든 세이브인가
    current_episode_id VARCHAR(50) NOT NULL,
    snapshot           JSON        NOT NULL,
    revision           BIGINT      NOT NULL DEFAULT 0,  -- 올릴 때마다 +1. 충돌 감지 키(M5)
    play_seconds       INT         NOT NULL DEFAULT 0,
    device_id          BIGINT      NULL,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_save_slot (playthrough_id, slot_no),
    CONSTRAINT fk_saves_playthrough FOREIGN KEY (playthrough_id) REFERENCES playthroughs(id),
    CONSTRAINT fk_saves_content     FOREIGN KEY (chapter_content_id) REFERENCES chapter_contents(id),
    CONSTRAINT fk_saves_device      FOREIGN KEY (device_id) REFERENCES devices(id)
);

-- 선택 이력. seq는 클라가 매기는 슬롯 내 순번 → 같은 선택이 두 번 오면 UNIQUE가 막는다(M5 멱등성).
CREATE TABLE choice_history (
    id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
    save_slot_id       BIGINT      NOT NULL,
    seq                INT         NOT NULL,
    chapter_content_id BIGINT      NOT NULL,
    episode_id         VARCHAR(50) NOT NULL,
    option_index       INT         NOT NULL,   -- NextOptions 배열의 인덱스
    chosen_at          DATETIME    NOT NULL,   -- 클라 시각 (오프라인 플레이 반영)
    received_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_choice_seq (save_slot_id, seq),
    CONSTRAINT fk_choice_save    FOREIGN KEY (save_slot_id) REFERENCES save_slots(id) ON DELETE CASCADE,
    CONSTRAINT fk_choice_episode FOREIGN KEY (chapter_content_id, episode_id)
        REFERENCES chapter_episodes(chapter_content_id, episode_id)
);

-- [1] 영구 계층의 실체. EventKey가 붙은 에피소드를 다 보면 한 행.
-- 챕터 해금·엔딩 통계는 전부 이 테이블에 대한 쿼리.
CREATE TABLE event_log (
    id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
    playthrough_id     BIGINT      NOT NULL,
    event_key          VARCHAR(50) NOT NULL,
    chapter_content_id BIGINT      NOT NULL,
    episode_id         VARCHAR(50) NOT NULL,
    occurred_at        DATETIME    NOT NULL,   -- 클라 시각
    received_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_once (playthrough_id, event_key, chapter_content_id, episode_id),
    KEY ix_event_key (event_key),
    CONSTRAINT fk_event_playthrough FOREIGN KEY (playthrough_id) REFERENCES playthroughs(id),
    CONSTRAINT fk_event_episode FOREIGN KEY (chapter_content_id, episode_id)
        REFERENCES chapter_episodes(chapter_content_id, episode_id)
);
