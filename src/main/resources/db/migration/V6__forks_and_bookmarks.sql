-- =====================================================================
--  V6 — 회차의 클라 신원·갈래, 세이브 시간 둘·챕터 완료, 즐겨찾기 (M8-A, D-019~D-022)
-- =====================================================================
--  Unity 저장 모델 개편(handoff/unity-2026-09-02.md)이 서버에 요구한 것 셋:
--    1. 회차 생성이 멱등이어야 한다 → 클라가 매긴 회차 id 를 받는다 (D-019)
--    2. 회차가 갈라진다 → 부모를 클라 id 로 잇고, 서버 id 는 해석되면 채운다 (D-020)
--    3. 즐겨찾기(유저 소유의 두 번째 스냅샷) (D-021)
--  전부 **추가**뿐이다 — 기존 행·기존 UNIQUE·기존 FK 는 손대지 않는다.
--  (F39 — FK 를 지탱하는 인덱스는 단독 DROP 이 안 된다 — 는 이번엔 해당 없다.)
-- =====================================================================

-- ---------------------------------------------------------------------
--  1. playthroughs — 클라 신원과 갈래
-- ---------------------------------------------------------------------
--  client_id 가 NULL 허용인 이유: seed 와 M0~M7 이 만든 회차는 id 없이 이미 있다.
--  UNIQUE (user_id, client_id) 는 NULL 을 여럿 허용하므로 그 행들은 그대로 산다.
--  새 회차는 앱이 client_id 를 필수로 요구한다(D-019) — DB 가 아니라 앱이 막는 이유는
--  "id 없이 만든 옛 회차"와 "id 를 빠뜨린 새 요청"을 DB 는 구분할 수 없기 때문이다.
--
--  forked_from_client_id 는 갈래면 항상 있고, forked_from_id 는 부모가 서버에 있을 때만 있다.
--  부모가 나중에 오면 앱이 되채운다(D-020) — 그래서 둘을 따로 둔다.
--  자기 참조 FK 는 기본 동작(RESTRICT). 부모를 지우려면 자식을 먼저 풀어야 한다 —
--  seed.sql 과 DbCleaner 가 DELETE 앞에 forked_from_id 를 NULL 로 만드는 이유다.
ALTER TABLE playthroughs
    ADD COLUMN client_id             VARCHAR(32) NULL AFTER user_id,
    ADD COLUMN forked_from_id        BIGINT      NULL AFTER client_id,
    ADD COLUMN forked_from_client_id VARCHAR(32) NULL AFTER forked_from_id,
    ADD COLUMN forked_scene_index    INT         NULL AFTER forked_from_client_id,
    ADD UNIQUE KEY uk_playthroughs_client (user_id, client_id),
    ADD CONSTRAINT fk_playthroughs_fork FOREIGN KEY (forked_from_id) REFERENCES playthroughs(id);

-- ---------------------------------------------------------------------
--  2. save_slots — 시간 둘, 챕터 완료
-- ---------------------------------------------------------------------
--  play_seconds 는 그대로 "둘의 합"이다(클라도 그렇게 보낸다). 목록·통계가 스냅샷을 열지 않고
--  "물려받은 시간 / 이 회차의 시간 / 끝낸 회차"를 그리기 위한 열 셋 (핸드오프 R4).
--  DEFAULT 가 있어 옛 클라(F6 전)가 안 보내도 0 / FALSE 로 선다.
ALTER TABLE save_slots
    ADD COLUMN inherited_play_seconds INT     NOT NULL DEFAULT 0     AFTER play_seconds,
    ADD COLUMN own_play_seconds       INT     NOT NULL DEFAULT 0     AFTER inherited_play_seconds,
    ADD COLUMN chapter_completed      BOOLEAN NOT NULL DEFAULT FALSE AFTER own_play_seconds;

-- ---------------------------------------------------------------------
--  3. bookmarks — 유저 소유의 두 번째 스냅샷
-- ---------------------------------------------------------------------
--  회차가 아니라 유저에 매단다(D-021, 핸드오프 D-a): 스스로 완결된 사본이라 출처 회차가
--  없어도 산다. playthrough_id 는 출처를 잇는 참고 링크일 뿐이고(해석되면 채운다),
--  집계와 조회는 playthrough_client_id 로 한다 — 그것이 항상 있는 값이다.
--
--  snapshot 은 열지 않는다(PLAN 1.4). 서버가 보는 것은 크기뿐이다(D-022, 1MB).
--  삭제는 deleted_at (soft) — 목록에서 빠지고 row 는 남는다. 같은 client_id 로 다시 PUT 하면 되살아난다.
--  created_at 은 클라가 찍은 시각(UTC, D-009)이고 updated_at 은 DB 의 것이다.
CREATE TABLE bookmarks (
    id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT       NOT NULL,
    client_id             VARCHAR(32)  NOT NULL,
    chapter_content_id    BIGINT       NOT NULL,
    playthrough_id        BIGINT       NULL,
    playthrough_client_id VARCHAR(32)  NULL,
    scene_index           INT          NOT NULL,
    label                 VARCHAR(100) NOT NULL,
    preview               VARCHAR(200) NOT NULL,
    snapshot              JSON         NOT NULL,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                       ON UPDATE CURRENT_TIMESTAMP,
    deleted_at            DATETIME     NULL,
    UNIQUE KEY uk_bookmarks_client (user_id, client_id),
    CONSTRAINT fk_bookmarks_user        FOREIGN KEY (user_id)            REFERENCES users(id),
    CONSTRAINT fk_bookmarks_content     FOREIGN KEY (chapter_content_id) REFERENCES chapter_contents(id),
    CONSTRAINT fk_bookmarks_playthrough FOREIGN KEY (playthrough_id)     REFERENCES playthroughs(id)
);
