package com.sparta.springprepare.save;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.content.ChapterContentRepository;
import com.sparta.springprepare.content.ChapterEpisode;
import com.sparta.springprepare.content.ChapterEpisodeRepository;
import com.sparta.springprepare.playthrough.Playthrough;
import com.sparta.springprepare.playthrough.PlaythroughRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 세이브 업로드·복구 (PLAN M2 + M3).
 *
 * 서버는 스냅샷을 **열지 않는다**. nodeName 이든 스탯이든 어떤 키도 읽지 않고 통째로 보관했다가 통째로 돌려준다.
 * 이 서비스가 검사하는 것은 스냅샷 바깥의 것들뿐이다 — 슬롯 번호 범위, 회차 존재, 콘텐츠 버전 존재,
 * 그리고 M3 에서 더해진 것: 동봉된 선택·이벤트가 **그 콘텐츠 버전에 실재하는 에피소드**를 가리키는가.
 */
@Service
public class SaveSlotService {

    /**
     * 슬롯 번호의 유효 범위 (D-008).
     *
     * 이것은 "슬롯 개수 상한"이 아니다. 몇 개까지 쓸지는 클라이언트 정책이고, 서버는 번호가
     * save_slots.slot_no(TINYINT) 에 담기는지만 보장한다. VN 은 수동 세이브를 많이 만들 수 있으므로
     * 3 같은 작은 고정 상한을 서버가 정하지 않는다.
     *
     * 127 이 언젠가 부족해지면 slot_no 를 SMALLINT 로 넓히는 한 줄 마이그레이션이면 된다 (기존 데이터에 무해).
     * 앱에서 미리 막는 이유는 범위를 넘겼을 때 DB 가 내는 것이 "Out of range" 오류(→ 500 계열)이고,
     * 클라에게 맞는 답은 "번호가 잘못됐다"(400)이기 때문이다.
     */
    private static final int SLOT_NO_MIN = 1;
    private static final int SLOT_NO_MAX = 127;

    private final SaveSlotRepository saveSlotRepository;
    private final DeviceRepository deviceRepository;
    private final PlaythroughRepository playthroughRepository;
    private final ChapterContentRepository chapterContentRepository;
    private final ChapterEpisodeRepository chapterEpisodeRepository;
    private final ChoiceHistoryRepository choiceHistoryRepository;
    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    public SaveSlotService(SaveSlotRepository saveSlotRepository,
                           DeviceRepository deviceRepository,
                           PlaythroughRepository playthroughRepository,
                           ChapterContentRepository chapterContentRepository,
                           ChapterEpisodeRepository chapterEpisodeRepository,
                           ChoiceHistoryRepository choiceHistoryRepository,
                           EventLogRepository eventLogRepository,
                           ObjectMapper objectMapper) {
        this.saveSlotRepository = saveSlotRepository;
        this.deviceRepository = deviceRepository;
        this.playthroughRepository = playthroughRepository;
        this.chapterContentRepository = chapterContentRepository;
        this.chapterEpisodeRepository = chapterEpisodeRepository;
        this.choiceHistoryRepository = choiceHistoryRepository;
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 한 트랜잭션 안에서 최대 네 테이블(devices, save_slots, choice_history, event_log)이 쓰인다.
     * M2 에서는 경계가 있어도 하는 일이 적었지만, M3 에서 이 경계가 진짜 일을 한다 —
     * 선택 세 건 중 하나가 틀리면 슬롯의 revision 조차 오르지 않는다.
     *
     * <h3>순서</h3>
     * <pre>
     *   1. 형식 검증        (슬롯 범위, 필수값)          → 400
     *   2. 존재 검증        (회차, 콘텐츠 버전, 에피소드) → 404 / 400
     *   3. 쓰기             (기기 → 슬롯 → 선택 → 이벤트)
     *   4. 상태 재조회      (revision·updated_at 은 DB 의 사실)
     * </pre>
     *
     * <b>쓰기를 시작하기 전에 판정을 모두 끝낸다</b>는 것이 이 순서의 전부다 (M3 계획서 §3-2).
     * 없는 episodeId 를 그냥 INSERT 해도 복합 FK 가 막아 주고 롤백되므로 결과는 같아 보이지만,
     * 그때 클라가 받는 것은 "Cannot add or update a child row..." 라는 드라이버 메시지다.
     * 롤백은 안전망이고 사전 검증은 정상 경로다 — 안전망이 작동한 것을 정상이라 부르지 않는다.
     */
    @Transactional
    public SaveUploadResponse upsert(long playthroughId, int slotNo, SaveUploadRequest request) {
        if (slotNo < SLOT_NO_MIN || slotNo > SLOT_NO_MAX) {
            throw new BadRequestException(
                    "슬롯 번호는 " + SLOT_NO_MIN + "~" + SLOT_NO_MAX + " 범위여야 합니다: " + slotNo);
        }
        requireText(request.chapterId(), "chapterId");
        requireText(request.currentEpisodeId(), "currentEpisodeId");
        if (request.chapterVersion() == null) {
            throw new BadRequestException("chapterVersion 이 없습니다.");
        }
        // 스냅샷은 열지 않지만 "있는가"는 본다. null 이면 JSON 컬럼이 NOT NULL 위반을 낸다.
        if (request.snapshot() == null || request.snapshot().isMissingNode()) {
            throw new BadRequestException("snapshot 이 없습니다.");
        }

        List<ChoiceUpload> choices = request.choicesOrEmpty();
        List<EventUpload> events = request.eventsOrEmpty();
        validateShape(choices, events);

        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));

        // 세이브는 chapter_id 가 아니라 **특정 버전**을 가리킨다 (schema.sql 주석).
        // 여기서 먼저 조회하지 않으면 FK 위반이 나고, 그것은 클라에게 "그 콘텐츠 버전이 서버에 없다"를
        // 알려 주지 못한다. 서비스가 404 로 번역한다.
        long chapterContentId = chapterContentRepository
                .findId(request.chapterId(), request.chapterVersion())
                .orElseThrow(() -> new NotFoundException(
                        "콘텐츠 버전이 없습니다: " + request.chapterId() + " v" + request.chapterVersion()));

        // 선택과 이벤트가 가리키는 에피소드를 **한 번에** 조회한다.
        // 건별로 조회하면 선택 20건에 쿼리 20번이다 (N+1). IN (:ids) 한 번이면 된다.
        Map<String, String> eventKeyByEpisode =
                resolveEpisodes(chapterContentId, choices, events);

        // deviceKey 는 선택이다. 없으면 device_id 가 NULL 로 남는다 (M2 까지는 허용).
        // M4 의 충돌 요약이 "어느 기기가 덮었는가"를 보여주려면 필요해진다.
        Long deviceId = null;
        if (request.deviceKey() != null && !request.deviceKey().isBlank()) {
            deviceId = deviceRepository.upsert(playthrough.userId(), request.deviceKey());
        }

        // 스냅샷을 문자열로 되돌린다. 내용을 읽는 것이 아니라 직렬화만 한다.
        String snapshotJson = objectMapper.writeValueAsString(request.snapshot());
        int playSeconds = request.playSeconds() == null ? 0 : request.playSeconds();

        saveSlotRepository.upsert(playthroughId, slotNo, chapterContentId,
                request.currentEpisodeId(), snapshotJson, playSeconds, deviceId);

        // revision·updated_at 은 DB 가 만든 값이다. 앱이 세면 두 요청이 겹칠 때 같은 값을 두 번 발급한다.
        // id 도 여기서 얻는다 — choice_history 가 save_slot_id 로 이 슬롯을 가리켜야 하는데,
        // upsert 는 갱신 경로에서 생성 키를 주지 않는다.
        SaveSlotState state = saveSlotRepository.findState(playthroughId, slotNo)
                .orElseThrow(() -> new IllegalStateException("방금 upsert 한 슬롯을 찾지 못했다"));

        choiceHistoryRepository.insertAll(state.id(), chapterContentId, choices);
        eventLogRepository.insertAll(playthroughId, chapterContentId, events, eventKeyByEpisode);

        return SaveUploadResponse.of(state, choices.size(), events.size());
    }

    /**
     * DB 를 보기 전에 끝낼 수 있는 검사. 여기서 걸리는 것은 전부 400 이다.
     *
     * record 의 필드를 Integer 로 받은 이유가 여기 있다 — int 로 받으면 클라가 seq 를 빼먹었을 때
     * Jackson 이 0 을 채워 넣고, 서버는 "0번 선택"이라는 없는 사실을 저장한다.
     * null 로 받아야 "안 보냈다"를 알 수 있다.
     */
    private static void validateShape(List<ChoiceUpload> choices, List<EventUpload> events) {
        Set<Integer> seenSeq = new LinkedHashSet<>();
        for (ChoiceUpload c : choices) {
            requireText(c.episodeId(), "choices[].episodeId");
            if (c.seq() == null) {
                throw new BadRequestException("choices[].seq 가 없습니다: episodeId=" + c.episodeId());
            }
            if (c.optionIndex() == null) {
                throw new BadRequestException("choices[].optionIndex 가 없습니다: seq=" + c.seq());
            }
            if (c.chosenAt() == null) {
                throw new BadRequestException("choices[].chosenAt 이 없습니다: seq=" + c.seq());
            }
            // 한 요청 안에서 seq 가 겹치는 것은 클라의 실수다. DB 까지 보내면 UNIQUE 가 409 를 내지만,
            // 그건 "이미 서버에 있다"는 뜻의 코드다. 요청 자체가 모순인 것은 400 이 맞다.
            if (!seenSeq.add(c.seq())) {
                throw new BadRequestException("한 요청에 같은 seq 가 두 번 들어 있습니다: " + c.seq());
            }
        }
        for (EventUpload e : events) {
            requireText(e.episodeId(), "events[].episodeId");
            if (e.occurredAt() == null) {
                throw new BadRequestException("events[].occurredAt 이 없습니다: episodeId=" + e.episodeId());
            }
        }
    }

    /**
     * 선택·이벤트가 가리키는 에피소드가 **그 콘텐츠 버전에** 실재하는지 확인하고,
     * 이벤트에 쓸 EventKey 를 함께 얻는다. 한 번의 조회로 두 가지를 답한다.
     *
     * @return 이벤트 대상 에피소드 → EventKey. 이벤트가 없으면 빈 맵.
     */
    private Map<String, String> resolveEpisodes(long chapterContentId,
                                                List<ChoiceUpload> choices,
                                                List<EventUpload> events) {
        // LinkedHashSet: 중복을 없애면서(같은 에피소드에서 선택이 여러 번 나올 수 있다) 순서를 지킨다.
        // 순서를 지키는 이유는 오류 메시지가 요청 순서대로 나오는 편이 읽기 쉽기 때문이다.
        Set<String> wanted = new LinkedHashSet<>();
        choices.forEach(c -> wanted.add(c.episodeId()));
        events.forEach(e -> wanted.add(e.episodeId()));
        if (wanted.isEmpty()) {
            return Map.of();     // 조회를 아예 하지 않는다 — IN () 는 SQL 문법 오류다 (M3 §6 C3)
        }

        Map<String, ChapterEpisode> found = new LinkedHashMap<>();
        for (ChapterEpisode ep : chapterEpisodeRepository.findByIds(chapterContentId, wanted)) {
            found.put(ep.episodeId(), ep);
        }
        // 요청한 것 중 안 돌아온 것이 "없는 에피소드"다. 크기 비교만으로는 어느 것인지 알려 줄 수 없다.
        for (String episodeId : wanted) {
            if (!found.containsKey(episodeId)) {
                throw new BadRequestException(
                        "이 콘텐츠 버전에 없는 에피소드입니다: " + episodeId);
            }
        }

        Map<String, String> eventKeyByEpisode = new LinkedHashMap<>();
        for (EventUpload e : events) {
            String eventKey = found.get(e.episodeId()).eventKey();
            // event_log.event_key 는 NOT NULL 이지만, 빈 문자열은 NOT NULL 을 통과한다.
            // 통과시키면 event_key='' 인 행이 쌓이고 M5 의 통계가 그것을 세게 된다.
            // 콘텐츠에 EventKey 가 없다는 것은 "이 에피소드는 기록 대상이 아니다"라는 뜻이다.
            if (eventKey == null || eventKey.isBlank()) {
                throw new BadRequestException(
                        "EventKey 가 없는 에피소드에는 이벤트를 기록할 수 없습니다: " + e.episodeId());
            }
            eventKeyByEpisode.put(e.episodeId(), eventKey);
        }
        return eventKeyByEpisode;
    }

    @Transactional(readOnly = true)
    public List<SaveSlotSummary> list(long playthroughId) {
        requirePlaythrough(playthroughId);
        return saveSlotRepository.findSummaries(playthroughId);
    }

    @Transactional(readOnly = true)
    public SaveSlotDetail get(long playthroughId, int slotNo) {
        requirePlaythrough(playthroughId);
        return saveSlotRepository.findDetail(playthroughId, slotNo)
                .orElseThrow(() -> new NotFoundException(
                        "슬롯이 없습니다: 회차 " + playthroughId + " 슬롯 " + slotNo));
    }

    /** 회차가 없는 것과 슬롯이 비어 있는 것은 다르다 — 전자는 404, 후자는 빈 배열이다. */
    private void requirePlaythrough(long playthroughId) {
        if (playthroughRepository.findById(playthroughId).isEmpty()) {
            throw new NotFoundException("회차가 없습니다: id=" + playthroughId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 가 없거나 비어 있습니다.");
        }
    }
}
