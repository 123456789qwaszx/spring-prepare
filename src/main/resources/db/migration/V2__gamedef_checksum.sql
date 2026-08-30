-- V2: game_definitions 에 checksum 추가 (D-007)
--
-- 왜: PLAN.md M1 은 POST /content/definition 이 같은 파일 재수입 시 200(이미 있음)을 반환하라고 하는데,
--     schema.sql v1 의 game_definitions 에는 checksum 컬럼이 없어 "같은 파일인가"를 판정할 수단이 없었다.
--     chapter_contents 와 같은 규칙(checksum UNIQUE = 재수입 방지)을 맞춘다.
--
-- 적용 대상: game, game_test 두 DB 모두. (Flyway 도입 전이라 수동 — 그 불편이 PLAN#5 의 근거다.)
-- 전제: 이 테이블이 비어 있다. NOT NULL 컬럼을 기본값 없이 추가하므로 기존 행이 있으면 실패한다.
--       M1 이전에 definition 을 넣은 적이 없으므로 문제 없다.
--
-- 실행:
--   USE game;      -- 그리고 다시 USE game_test; 로 한 번 더
--   (아래 ALTER)

ALTER TABLE game_definitions
    ADD COLUMN checksum CHAR(64) NOT NULL AFTER body,
    ADD UNIQUE KEY uk_gamedef_checksum (checksum);
