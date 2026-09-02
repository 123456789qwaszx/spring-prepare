package com.sparta.springprepare.save;

import com.sparta.springprepare.support.AuthSupport;
import com.sparta.springprepare.support.DbCleaner;
import com.sparta.springprepare.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선택 이력·이벤트 로그 (PLAN M3 완료 기준 + docs/plans/M3.md §7).
 *
 * <p>M2 의 SaveSlotApiTest 와 나눈 이유: 저 쪽이 확인하는 것은 "스냅샷 하나가 왕복하는가"이고,
 * 이 쪽이 확인하는 것은 "한 요청에서 세 테이블이 함께 움직이는가"다. 같은 PUT 을 쓰지만 질문이 다르다.
 *
 * <p><b>이 클래스의 절반은 실패 경로다.</b> 트랜잭션은 성공했을 때가 아니라 실패했을 때 증명된다 —
 * 400 이 났을 때 세 테이블이 전부 그대로이고 revision 조차 오르지 않았음을 보는 것이 M3 의 핵심이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaveHistoryApiTest {

    private static final String SNAPSHOT = """
            {"nodeName":"qwer_EP02_01","variables":{"$int":5}}
            """;

    /**
     * 클라가 KST 오프셋으로 보내는 시각. 같은 순간의 UTC 는 11:40:19 다.
     * D-009 는 "클라는 Z 로 보낸다"이지만, 규약을 어긴 요청이 와도 서버가 조용히 9시간
     * 틀린 값을 저장하지 않는지를 보려면 어긴 쪽을 넣어 봐야 한다.
     */
    private static final String KST_TIME = "2026-08-29T20:40:19+09:00";
    private static final String SAME_INSTANT_UTC = "2026-08-29T11:40:19Z";
    private static final String SAME_INSTANT_DB = "2026-08-29 11:40:19";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private long playthroughId;
    private String bearer;

    /** 콘텐츠 수입(M1 API) 테스트 하나가 POST /content 를 쓴다 — M6 부터 관리자 키가 필요하다 (D-013). */
    @Value("${app.admin-key}")
    String adminKey;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        long userId = Fixtures.insertUser(jdbc, "amiya");
        playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);

        // EventKey 가 빈 것과 있는 것을 둘 다 둔다 — 그 차이가 곧 두 갈래의 테스트다.
        Fixtures.insertEpisode(jdbc, contentId, "EP01", "");
        Fixtures.insertEpisode(jdbc, contentId, "EP02_01", "");
        Fixtures.insertEpisode(jdbc, contentId, "EP03_01", "MILESTONE_MIDPOINT");
        Fixtures.insertEpisode(jdbc, contentId, "EP04_01", "ENDING_A");
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 보호 경로
    }

    // ── 정상 경로 ────────────────────────────────────────────────────

    @Test
    void 선택_3개와_이벤트_1개가_한_요청에_기록된다() throws Exception {
        String body = bodyWith("""
                "choices":[
                  {"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"},
                  {"seq":2,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:10:11Z"},
                  {"seq":3,"episodeId":"EP03_01","optionIndex":1,"chosenAt":"2026-08-29T11:20:13Z"}
                ],
                "events":[
                  {"episodeId":"EP04_01","occurredAt":"2026-08-29T11:30:17Z"}
                ]
                """);

        mockMvc.perform(putSlot(1, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.acceptedChoices").value(3))
                .andExpect(jsonPath("$.acceptedEvents").value(1));

        assertThat(count("save_slots")).isEqualTo(1);
        assertThat(count("choice_history")).isEqualTo(3);
        assertThat(count("event_log")).isEqualTo(1);
    }

    @Test
    void 이벤트_조회는_챕터_정보를_JOIN해서_준다() throws Exception {
        mockMvc.perform(putSlot(1, bodyWith("""
                "events":[{"episodeId":"EP04_01","occurredAt":"2026-08-29T11:30:17Z"}]
                """))).andExpect(status().isOk());

        // event_log 는 chapter_content_id 라는 숫자만 들고 있다.
        // 그것이 어느 챕터의 몇 번째 버전인지, 표시명이 무엇인지는 chapter_contents 가 안다 — 첫 JOIN.
        mockMvc.perform(get("/playthroughs/{pid}/events", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // eventKey 는 클라가 보낸 값이 아니다. 서버가 chapter_episodes 에서 찾아 넣었다.
                .andExpect(jsonPath("$[0].eventKey").value("ENDING_A"))
                .andExpect(jsonPath("$[0].chapterId").value("qwer"))
                .andExpect(jsonPath("$[0].chapterVersion").value(1))
                .andExpect(jsonPath("$[0].chapterDisplayName").value("qwer"))
                .andExpect(jsonPath("$[0].episodeId").value("EP04_01"))
                .andExpect(jsonPath("$[0].occurredAt").isNotEmpty());
        // 시각의 정확성은 아래 D-009 테스트가 따로 본다 — 여기서 볼 것은 JOIN 이다.
    }

    @Test
    void 선택_조회는_afterSeq_다음부터_준다() throws Exception {
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[
                  {"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"},
                  {"seq":2,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:10:11Z"},
                  {"seq":3,"episodeId":"EP03_01","optionIndex":1,"chosenAt":"2026-08-29T11:20:13Z"}
                ]
                """))).andExpect(status().isOk());

        // 기본값 0 → 전부
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}/choices", playthroughId, 1).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].episodeId").value("EP01"))
                .andExpect(jsonPath("$[2].optionIndex").value(1));

        // afterSeq=2 → "2 다음부터"이므로 3 하나만
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}/choices", playthroughId, 1)
                        .header("Authorization", bearer).param("afterSeq", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(3));
    }

    @Test
    void 선택은_슬롯에_이벤트는_회차에_속한다() throws Exception {
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"}],
                "events":[{"episodeId":"EP03_01","occurredAt":"2026-08-29T11:05:03Z"}]
                """))).andExpect(status().isOk());

        mockMvc.perform(putSlot(2, bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:10:11Z"}],
                "events":[{"episodeId":"EP04_01","occurredAt":"2026-08-29T11:15:05Z"}]
                """))).andExpect(status().isOk());

        // 같은 seq 1 이 두 슬롯에 나란히 존재한다 — UNIQUE 가 (save_slot_id, seq) 이지 seq 단독이 아니다.
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}/choices", playthroughId, 1).header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].episodeId").value("EP01"));
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}/choices", playthroughId, 2).header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].episodeId").value("EP02_01"));

        // 이벤트는 슬롯으로 나뉘지 않는다. 두 슬롯에서 난 것이 회차 하나의 목록에 함께 있다.
        mockMvc.perform(get("/playthroughs/{pid}/events", playthroughId).header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventKey").value("MILESTONE_MIDPOINT"))
                .andExpect(jsonPath("$[1].eventKey").value("ENDING_A"));
    }

    // ── 시각 (D-009) ────────────────────────────────────────────────

    @Test
    void 오프셋이_붙어_와도_UTC로_저장되고_UTC로_돌아온다() throws Exception {
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"%s"}],
                "events":[{"episodeId":"EP04_01","occurredAt":"%s"}]
                """.formatted(KST_TIME, KST_TIME)))).andExpect(status().isOk());

        // ① DB 에 실제로 들어 있는 벽시계를 문자열로 직접 확인한다.
        //    드라이버의 읽기 변환을 거치지 않는 유일한 방법이다 — 여기서 20:40:19 가 나오면
        //    쓰기 변환이 빠진 것이고, 회수까지 같은 실수를 하면 왕복 단언만으로는 잡히지 않는다.
        assertThat(rawDateTime("choice_history", "chosen_at")).isEqualTo(SAME_INSTANT_DB);
        assertThat(rawDateTime("event_log", "occurred_at")).isEqualTo(SAME_INSTANT_DB);

        // ② API 로 돌아올 때는 오프셋이 붙는다. 클라가 "이게 무슨 시간대지?" 를 짐작할 필요가 없다.
        //    문자열을 그대로 비교하지 않고 파싱해 순간으로 비교한다 — Jackson 의 ISO 출력은
        //    같은 순간을 "…Z" 로도 "…+00:00" 으로도 쓸 수 있고, 우리가 지키려는 규약은 표기가 아니라 순간이다.
        assertThat(instantOf(
                readString("/playthroughs/" + playthroughId + "/saves/1/choices", "chosenAt")))
                .isEqualTo(OffsetDateTime.parse(SAME_INSTANT_UTC).toInstant());
        assertThat(instantOf(
                readString("/playthroughs/" + playthroughId + "/events", "occurredAt")))
                .isEqualTo(OffsetDateTime.parse(SAME_INSTANT_UTC).toInstant());

        // 다만 "오프셋이 아예 없는 로컬 시각"으로 나가면 안 된다 — 그러면 클라가 짐작해야 한다.
        assertThat(readString("/playthroughs/" + playthroughId + "/events", "occurredAt"))
                .matches(".*(Z|[+-]\\d{2}:\\d{2})$");
    }

    // ── 실패 경로: 하나라도 틀리면 전부 없던 일 ──────────────────────

    @Test
    void 없는_에피소드가_섞이면_400이고_세_테이블이_그대로다() throws Exception {
        // 먼저 성공적으로 한 번 올려 둔다 — "원래 있던 것"이 있어야 "그대로"를 말할 수 있다.
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"}]
                """))).andExpect(status().isOk());

        // 두 번째 요청: seq 2 는 멀쩡하고 seq 3 만 없는 에피소드다.
        mockMvc.perform(putSlot(1, bodyWith(1, """
                "choices":[
                  {"seq":2,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:10:11Z"},
                  {"seq":3,"episodeId":"EP99_NOPE","optionIndex":0,"chosenAt":"2026-08-29T11:20:13Z"}
                ],
                "events":[{"episodeId":"EP04_01","occurredAt":"2026-08-29T11:30:17Z"}]
                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 멀쩡했던 seq 2 도 들어가지 않았다. 이벤트도 없다.
        assertThat(count("choice_history")).isEqualTo(1);
        assertThat(count("event_log")).isZero();
        // 그리고 revision 이 오르지 않았다 — 슬롯 upsert 까지 되돌려졌다는 뜻이다.
        assertThat(revisionOfSlot1()).isEqualTo(1);
    }

    @Test
    void EventKey가_없는_에피소드에는_이벤트를_기록할_수_없다() throws Exception {
        // EP01 의 event_key 는 빈 문자열이다. NOT NULL 은 통과하므로 DB 는 막지 않는다 —
        // 막는 것은 서비스이고, 이유는 event_key='' 인 행이 M5 의 통계에 섞이면 안 되기 때문이다.
        mockMvc.perform(putSlot(1, bodyWith("""
                "events":[{"episodeId":"EP01","occurredAt":"2026-08-29T11:30:17Z"}]
                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count("event_log")).isZero();
        assertThat(count("save_slots")).isZero();
    }

    @Test
    void 같은_요청을_다시_보내면_200_replayed다() throws Exception {
        String same = bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"}]
                """);

        mockMvc.perform(putSlot(1, same))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.acceptedChoices").value(1));

        // M3 에서는 여기가 409 DUPLICATE 였다 — (save_slot_id, seq) UNIQUE 위반.
        // M4 는 그 전에 "내가 방금 보낸 그 요청"임을 알아보고 200 을 준다 (D-010).
        // 응답을 못 받아 재전송한 클라 입장에서 이 요청의 목적은 이미 달성돼 있다.
        mockMvc.perform(putSlot(1, same))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.acceptedChoices").value(0));

        assertThat(count("choice_history")).isEqualTo(1);
        // 아무것도 쓰지 않았으므로 revision 도 그대로다.
        assertThat(revisionOfSlot1()).isEqualTo(1);
    }

    @Test
    void 한_요청에_같은_seq가_두_번이면_400이다() throws Exception {
        // 이것을 DB 까지 보내면 UNIQUE 가 막아 409 가 나지만, 409 는 "이미 서버에 있다"는 뜻이다.
        // 요청 자체가 모순인 것은 400 이 맞다 — 상태 코드는 원인을 가리켜야 한다.
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[
                  {"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"},
                  {"seq":1,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:10:11Z"}
                ]
                """)))
                .andExpect(status().isBadRequest());

        assertThat(count("choice_history")).isZero();
        assertThat(count("save_slots")).isZero();
    }

    @Test
    void 선택의_필수값이_빠지면_400이다() throws Exception {
        // seq 를 int 가 아니라 Integer 로 받은 이유가 이것이다.
        // int 면 Jackson 이 0 을 채워 넣고 서버는 "0번 선택"이라는 없는 사실을 저장한다.
        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[{"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T11:00:07Z"}]
                """)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(putSlot(1, bodyWith("""
                "choices":[{"seq":1,"episodeId":"EP01","optionIndex":0}]
                """)))
                .andExpect(status().isBadRequest());

        assertThat(count("save_slots")).isZero();
    }

    // ── 없음과 0개 ──────────────────────────────────────────────────

    @Test
    void 이벤트가_하나도_없으면_빈_배열이고_없는_회차는_404다() throws Exception {
        // "회차는 있는데 이벤트가 없다"와 "회차가 없다"는 다른 사실이다. 클라의 대응도 다르다.
        mockMvc.perform(get("/playthroughs/{pid}/events", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/playthroughs/{pid}/events", 999_999).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}/choices", playthroughId, 1).header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이력을_빼고_세이브만_올려도_정상이다() throws Exception {
        // M2 의 요청 형태가 그대로 통해야 한다. choices·events 는 없어도 되는 필드다.
        mockMvc.perform(putSlot(1, bodyWith(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedChoices").value(0))
                .andExpect(jsonPath("$.acceptedEvents").value(0));

        // 빈 배열로 보내도 같다 — batchUpdate 를 호출하지 않고 IN () 도 만들지 않는다.
        mockMvc.perform(putSlot(2, bodyWith("\"choices\":[],\"events\":[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedChoices").value(0));

        assertThat(count("save_slots")).isEqualTo(2);
        assertThat(count("choice_history")).isZero();
    }

    @Test
    void 장면_단위로_접힌_큰_배치도_한_요청에_다_들어간다() throws Exception {
        // M8 핸드오프 §1: Unity 가 장면(scene) 단위로 접어 보내므로 한 PUT 의 choices 가 수십~수백이 된다.
        // 서버 코드는 바뀌지 않았다 — 바뀌지 않았음을 실물 크기로 확인하는 테스트다 (plans/M8 §4-A "바뀌지 않는 것").
        // 300 = 클라 백로그 상한과 같은 수. 이벤트는 회차당 1회(D-011)라 키가 있는 에피소드 둘만.
        StringBuilder choices = new StringBuilder("\"choices\":[");
        String[] episodes = {"EP01", "EP02_01", "EP03_01"};
        for (int seq = 1; seq <= 300; seq++) {
            if (seq > 1) choices.append(',');
            choices.append("{\"seq\":").append(seq)
                    .append(",\"episodeId\":\"").append(episodes[seq % 3])
                    .append("\",\"optionIndex\":").append(seq % 2)
                    .append(",\"chosenAt\":\"2026-08-29T11:00:07Z\"}");
        }
        choices.append("],\"events\":[")
                .append("{\"episodeId\":\"EP03_01\",\"occurredAt\":\"2026-08-29T11:30:17Z\"},")
                .append("{\"episodeId\":\"EP04_01\",\"occurredAt\":\"2026-08-29T11:31:17Z\"}]");

        mockMvc.perform(putSlot(1, bodyWith(choices.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedChoices").value(300))
                .andExpect(jsonPath("$.acceptedEvents").value(2));

        assertThat(count("choice_history")).isEqualTo(300);
        assertThat(count("event_log")).isEqualTo(2);

        // 이어서 다음 장면 — 큐는 지난 PUT 이후의 선택만 싣는다(seq 이어짐). 서버는 그것을 그냥 덧붙인다.
        mockMvc.perform(putSlot(1, bodyWith(1, """
                "choices":[
                  {"seq":301,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T12:00:07Z"},
                  {"seq":302,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T12:00:09Z"}
                ]
                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.acceptedChoices").value(2));
        assertThat(count("choice_history")).isEqualTo(302);
    }

    // ── 수입한 콘텐츠와의 연결 ───────────────────────────────────────

    @Test
    void 수입한_콘텐츠의_EventKey가_이벤트에_그대로_쓰인다() throws Exception {
        // 여기만 준비를 SQL 이 아니라 M1 의 수입 API 로 한다.
        // 확인하려는 것이 "파일의 EventKey → 색인 → event_log" 전 구간이기 때문이다.
        // (qwer.progression.json 은 EventKey 가 전부 비어 있어 쓸 수 없다 → 이 fixture 를 따로 둔다.)
        byte[] fixture = readResource("/content/qwer-events.progression.json");
        mockMvc.perform(post("/content/chapters")
                        .header("X-Admin-Key", adminKey)     // M6-7: 콘텐츠 수입은 관리자만
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chapterId").value("qwer-events"))
                .andExpect(jsonPath("$.episodeCount").value(8));

        String body = """
                {"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP04_01",
                 "snapshot":%s,"playSeconds":600,"deviceKey":"device-A","baseRevision":0,
                 "events":[{"episodeId":"EP04_01","occurredAt":"2026-08-29T11:30:17Z"}]}
                """.formatted(SNAPSHOT);

        mockMvc.perform(putSlot(1, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedEvents").value(1));

        mockMvc.perform(get("/playthroughs/{pid}/events", playthroughId).header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1))
                // 파일에 적힌 값이 그대로 나온다. 클라는 이 문자열을 보낸 적이 없다.
                .andExpect(jsonPath("$[0].eventKey").value("ENDING_A"))
                .andExpect(jsonPath("$[0].chapterDisplayName").value("이벤트 테스트 챕터"));

        // 같은 파일의 EventKey 없는 노드에는 여전히 이벤트를 걸 수 없다.
        mockMvc.perform(putSlot(2, """
                {"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP01",
                 "snapshot":%s,"baseRevision":0,
                 "events":[{"episodeId":"EP02_01","occurredAt":"2026-08-29T11:30:17Z"}]}
                """.formatted(SNAPSHOT)))
                .andExpect(status().isBadRequest());
    }

    // ── helper ──────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder putSlot(int slotNo, String jsonBody) {
        return put("/playthroughs/{pid}/saves/{slotNo}", playthroughId, slotNo)
                .header("Authorization", bearer)     // M6: 토큰 필수
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    /** M2 의 필수 필드를 채우고 그 뒤에 M3 의 부분만 붙인다. null 이면 이력 없는 요청. baseRevision 은 0(신규). */
    private static String bodyWith(String historyJson) {
        return bodyWith(0, historyJson);
    }

    private static String bodyWith(long baseRevision, String historyJson) {
        String base = """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP02_01",
                 "snapshot":%s,"playSeconds":120,"deviceKey":"device-A","baseRevision":%d
                """.formatted(SNAPSHOT, baseRevision).stripTrailing();
        return historyJson == null ? base + "}" : base + ",\n" + historyJson + "}";
    }

    /**
     * 드라이버의 읽기 변환을 거치지 않고 DB 의 벽시계를 문자열 그대로 본다.
     * DATE_FORMAT 은 MySQL 서버가 문자열을 만들므로 시간대 변환이 개입할 자리가 없다.
     * ({@code %T} 는 hh:mm:ss 다 — 형식 문자열에 콜론을 넣지 않으려고 골랐다.
     *  NamedParameterJdbcTemplate 는 따옴표 안을 건너뛰지만, 굳이 시험할 이유가 없다.)
     */
    private String rawDateTime(String table, String column) {
        return jdbc.sql("SELECT DATE_FORMAT(" + column + ", '%Y-%m-%d %T') FROM " + table)
                .query(String.class).single();
    }

    /** 배열 응답의 첫 원소에서 필드 하나를 문자열로 꺼낸다. */
    private String readString(String url, String field) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .get(0).path(field).stringValue();
    }

    private static Instant instantOf(String isoText) {
        return OffsetDateTime.parse(isoText).toInstant();
    }

    private long revisionOfSlot1() {
        return jdbc.sql("SELECT revision FROM save_slots WHERE playthrough_id = :pid AND slot_no = 1")
                .param("pid", playthroughId)
                .query(Long.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = SaveHistoryApiTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("테스트 리소스를 찾을 수 없습니다: " + path);
            }
            return in.readAllBytes();
        }
    }
}
