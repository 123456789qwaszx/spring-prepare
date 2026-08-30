package com.sparta.springprepare.stats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 집계 API (PLAN M5).
 *
 * <p>클래스 레벨 {@code @RequestMapping} 이 없다. 경로가 두 갈래이기 때문이다 —
 * {@code /stats/…} 는 "전체를 가로질러 본 통계" 이고, {@code /users/{id}/summary} 는
 * <b>그 사용자에게 속한 자원</b>이다. 후자를 {@code /stats/users/{id}} 로 옮기면
 * 경로가 소유 관계를 잃는다. {@code PlaythroughController} 가 같은 이유로 같은 모양이다.
 */
@RestController
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    /** 전체 회차를 가로질러 EventKey 별 도달률. 챕터로 나누지 않는다 — 나눌 이유가 생기면 M6 에서. */
    @GetMapping("/stats/events")
    public List<EventReachItem> events() {
        return service.eventReach();
    }

    /**
     * @param version 생략하면 최신 버전. {@code Integer} 라서 "안 보냈다" 와 "0 을 보냈다" 가 구분된다 —
     *                {@code int} + {@code defaultValue} 로 두면 없는 버전 0 을 조회하게 된다.
     *                {@code required = false} 는 M4 의 {@code baseRevision} 을 필수로 둔 것과 반대 판단인데,
     *                <b>읽기라서</b> 그렇다. 잘못 읽으면 틀린 숫자를 보지만, 잘못 쓰면 남의 데이터가 사라진다.
     */
    @GetMapping("/stats/chapters/{chapterId}/choices")
    public List<ChoiceRatioItem> choices(@PathVariable String chapterId,
                                         @RequestParam(required = false) Integer version) {
        return service.choiceRatio(chapterId, version);
    }

    @GetMapping("/users/{userId}/summary")
    public UserSummary userSummary(@PathVariable long userId) {
        return service.userSummary(userId);
    }
}
