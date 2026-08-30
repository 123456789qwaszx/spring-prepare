package com.sparta.springprepare.save;

import com.sparta.springprepare.support.AuthSupport;
import com.sparta.springprepare.support.DbCleaner;
import com.sparta.springprepare.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 두 기기가 같은 슬롯에 <b>동시에</b> 쓴다 (PLAN M4).
 *
 * <h3>이 테스트는 M4 구현 전에 먼저 쓰였고, 그때는 실패했다</h3>
 * 고칠 것이 무엇인지를 **말이 아니라 실행 결과로** 못박기 위해서다. M3 코드는
 * `ON DUPLICATE KEY UPDATE` 하나로 쓰기를 처리했으므로:
 *
 * <pre>
 *   기대: 200 하나, 409 하나        — 한 쪽은 자기 것이 밀렸다는 사실을 안다
 *   실제: 200 둘, revision 3        — 나중 것이 **조용히** 이기고 아무도 모른다
 * </pre>
 *
 * 데이터가 사라지는 것이 아니라 <b>사라졌다는 사실이 사라지는 것</b>이 문제다. A가 30분 플레이한 세이브를
 * B가 덮어써도 A의 화면에는 "저장 완료"가 뜬다. M4는 이 조용함을 없앤다.
 *
 * <h3>왜 락이 아니라 테스트로 시작하나</h3>
 * `SELECT … FOR UPDATE`로 막을 수도 있다. 그러나 PLAN은 "락이 아니라 데이터(UNIQUE, revision)로 푼다"를
 * M4의 학습 목표로 잡았고, 이 테스트는 **락 없이도 정확함**을 증명하는 자리다.
 * 테스트가 먼저 있어야 "통과했다"가 의미를 갖는다.
 *
 * <h3>동시 출발을 어떻게 보장하나</h3>
 * 스레드 두 개를 그냥 띄우면 대개 순차로 끝나 경합이 일어나지 않는다. `CountDownLatch` 하나로 둘을
 * 세워 두었다가 동시에 놓아준다. 그래도 완벽히 같은 순간은 아니지만, DB 행 락을 다투기엔 충분히 가깝다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaveSlotConcurrencyTest {

    /** 경합을 10번 반복한다 — 한 번 통과는 우연일 수 있다. 슬롯 번호를 바꿔 가며 서로 간섭하지 않게 한다. */
    private static final int ROUNDS = 10;

    private static final String SNAPSHOT = """
            {"nodeName":"qwer_EP01","variables":{"$int":1}}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private long playthroughId;
    private String bearer;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        long userId = Fixtures.insertUser(jdbc, "amiya");
        playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);
        Fixtures.insertEpisode(jdbc, contentId, "EP01", "");
        Fixtures.insertEpisode(jdbc, contentId, "EP02_01", "");
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 두 스레드가 같은 토큰을 쓴다 — 같은 사용자의 두 기기다
    }

    @Test
    void 동시에_같은_슬롯에_쓰면_정확히_하나만_성공한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int slotNo = 1; slotNo <= ROUNDS; slotNo++) {
                // 준비: 슬롯을 하나 만들어 둔다 → revision 1.
                // 두 기기가 이 상태를 각자 읽어 갔다고 보면 된다.
                mockMvc.perform(putSlot(slotNo, body(0, "EP01", 10, "device-A")))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .status().isOk());

                List<Integer> statuses = race(pool, slotNo);

                long ok = statuses.stream().filter(s -> s == 200).count();
                long conflict = statuses.stream().filter(s -> s == 409).count();

                // 이 두 줄이 M4의 완료 기준이다. 둘 다 base 1 로 보냈지만
                // 조건부 UPDATE 를 통과하는 것은 먼저 도착한 하나뿐이다.
                assertThat(ok).as("round %d: 200 의 수", slotNo).isEqualTo(1L);
                assertThat(conflict).as("round %d: 409 의 수", slotNo).isEqualTo(1L);

                // revision 이 2 여야 한다. 3 이면 둘 다 썼다는 뜻 —
                // 즉 한 기기의 저장이 아무 신호 없이 사라졌다.
                assertThat(revisionOf(slotNo)).as("round %d: revision", slotNo).isEqualTo(2L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 두 요청을 세워 두었다가 동시에 놓아준다. 돌려주는 것은 각자의 상태 코드. */
    private List<Integer> race(ExecutorService pool, int slotNo) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        // 둘 다 base 1 로 보낸다 — "내가 읽었을 때 서버는 revision 1 이었다".
        // 실제로 그랬고 둘 다 맞는 말이다. 그래서 하나만 이기는 것이 정답이다.
        Callable<Integer> deviceA = task(ready, start, slotNo, body(1, "EP02_01", 100, "device-A"));
        Callable<Integer> deviceB = task(ready, start, slotNo, body(1, "EP02_01", 200, "device-B"));

        Future<Integer> fa = pool.submit(deviceA);
        Future<Integer> fb = pool.submit(deviceB);

        // 둘 다 출발선에 설 때까지 기다렸다가 한 번에 놓아준다.
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("두 요청이 출발선에 서지 못했다");
        }
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        statuses.add(fa.get(10, TimeUnit.SECONDS));
        statuses.add(fb.get(10, TimeUnit.SECONDS));
        return statuses;
    }

    private Callable<Integer> task(CountDownLatch ready, CountDownLatch start,
                                   int slotNo, String jsonBody) {
        return () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(putSlot(slotNo, jsonBody))
                    .andReturn().getResponse().getStatus();
        };
    }

    // ── helper ──────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder putSlot(int slotNo, String jsonBody) {
        return put("/playthroughs/{pid}/saves/{slotNo}", playthroughId, slotNo)
                .header("Authorization", bearer)     // M6: 토큰 필수
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(long baseRevision, String episodeId, int playSeconds, String deviceKey) {
        return """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"%s",
                 "snapshot":%s,"playSeconds":%d,"deviceKey":"%s","baseRevision":%d}
                """.formatted(episodeId, SNAPSHOT, playSeconds, deviceKey, baseRevision);
    }

    private long revisionOf(int slotNo) {
        return jdbc.sql("SELECT revision FROM save_slots WHERE playthrough_id = :pid AND slot_no = :slotNo")
                .param("pid", playthroughId)
                .param("slotNo", slotNo)
                .query(Long.class).single();
    }
}
