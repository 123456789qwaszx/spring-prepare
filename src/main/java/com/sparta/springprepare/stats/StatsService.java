package com.sparta.springprepare.stats;

import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.content.ChapterContentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 집계 (PLAN M5).
 *
 * <p>이 서비스는 계산을 하지 않는다. 세는 것도, 나누는 것도 전부 SQL 이 한다.
 * 여기서 하는 일은 <b>범위를 정하고(어느 챕터의 어느 버전) 없는 것을 404 로 번역하는 것</b>뿐이다.
 * 앱에서 루프를 돌며 세기 시작하면 DB 를 쓰는 의미가 없다 (M5 계획서 §3-2).
 *
 * <p>세 메서드 모두 {@code readOnly = true} 다. 쓰기가 없다는 것을 코드가 말하게 두고,
 * 드라이버·커넥션 풀이 그에 맞게 최적화할 여지도 남긴다.
 */
@Service
public class StatsService {

    private final StatsRepository statsRepository;
    private final ChapterContentRepository chapterContentRepository;

    public StatsService(StatsRepository statsRepository,
                        ChapterContentRepository chapterContentRepository) {
        this.statsRepository = statsRepository;
        this.chapterContentRepository = chapterContentRepository;
    }

    /** 이벤트가 하나도 없으면 빈 배열이다 — "없음" 과 "0개" 를 구분할 자리가 아니다(전체를 묻는 질문이므로). */
    @Transactional(readOnly = true)
    public List<EventReachItem> eventReach() {
        return statsRepository.eventReach();
    }

    /**
     * @param version null 이면 그 챕터의 <b>최신 버전</b>. 버전을 합치지 않는 이유는
     *                버전마다 옵션 구성이 다를 수 있어서다 — 합치면 "3번 옵션" 이 서로 다른 것을 가리킨다.
     *                세이브가 chapter_id 가 아니라 특정 버전을 가리키는 것과 같은 이유다.
     *
     * <p>없는 챕터·버전은 <b>404</b> 다. 빈 배열이 아니다 — M3 에서 세운 구분 그대로,
     * "그런 콘텐츠가 없다" 와 "선택이 0건이다" 는 다른 사실이고 클라의 대응도 다르다.
     */
    @Transactional(readOnly = true)
    public List<ChoiceRatioItem> choiceRatio(String chapterId, Integer version) {
        int resolved = (version != null)
                ? version
                : chapterContentRepository.findLatestVersion(chapterId)
                        .orElseThrow(() -> new NotFoundException("챕터가 없습니다: " + chapterId));

        // 버전을 직접 받은 경우에도 존재를 확인한다. 이걸 건너뛰면 없는 버전이 빈 배열로 보이고,
        // 클라는 "아직 아무도 안 골랐다" 로 읽는다 — 조용히 틀린 답이다.
        chapterContentRepository.findId(chapterId, resolved)
                .orElseThrow(() -> new NotFoundException(
                        "콘텐츠 버전이 없습니다: " + chapterId + " v" + resolved));

        return statsRepository.choiceRatio(chapterId, resolved);
    }

    @Transactional(readOnly = true)
    public UserSummary userSummary(long userId) {
        return statsRepository.userSummary(userId)
                .orElseThrow(() -> new NotFoundException("사용자가 없습니다: id=" + userId));
    }
}
