package com.sparta.springprepare.save;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.ConflictException;
import com.sparta.springprepare.common.ForbiddenException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.content.ChapterContentRepository;
import com.sparta.springprepare.content.ChapterEpisode;
import com.sparta.springprepare.content.ChapterEpisodeRepository;
import com.sparta.springprepare.playthrough.Playthrough;
import com.sparta.springprepare.playthrough.PlaythroughRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 세이브 업로드·복구 (PLAN M2 + M3 + M4).
 *
 * 서버는 스냅샷을 **열지 않는다**. nodeName 이든 스탯이든 어떤 키도 읽지 않고 통째로 보관했다가 통째로 돌려준다.
 * 이 서비스가 검사하는 것은 스냅샷 바깥의 것들뿐이다 — 슬롯 번호 범위, 회차 존재, 콘텐츠 버전 존재,
 * 동봉된 선택·이벤트가 그 콘텐츠 버전에 실재하는 에피소드를 가리키는가(M3),
 * 그리고 **클라가 알던 상태가 아직 유효한가**(M4).
 *
 * <p>M4 에서 이 클래스의 성격이 한 번 바뀐다. M2·M3 의 쓰기는 <b>항상 성공했다</b> —
 * 검증만 통과하면 `ON DUPLICATE KEY UPDATE` 가 무조건 반영했다. M4 부터는 "성공했지만 반영하지 않음"
 * (재전송)과 "실패했고 그 이유가 남의 쓰기"(충돌)라는 두 갈래가 생긴다. 상태 코드가 셋에서 다섯으로 늘어난 것이 아니라,
 * <b>같은 요청에 서로 다른 답이 있을 수 있다는 것을 서버가 인정한 것</b>이다.
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
     *
     * <h3>다섯 갈래 (M4)</h3>
     * <pre>
     *   신규      슬롯이 없다                          → INSERT, revision 1
     *   재전송    revision == base+1 이고 seq 가 전부 있다 → 200 replayed, **아무것도 쓰지 않는다**
     *   정상      조건부 UPDATE 가 1행                  → 200
     *   충돌      조건부 UPDATE 가 0행                  → 409 + 현재 서버 상태
     *   force     이력은 새 것만, 스냅샷은 덮어쓴다      → 200
     * </pre>
     *
     * <h3>순서가 곧 정확성이다</h3>
     * <pre>
     *   1. 형식 검증   (슬롯 범위, 필수값, baseRevision)  → 400
     *   2. 존재 검증   (회차, 콘텐츠 버전, 에피소드)       → 404 / 400
     *   3. 현재 슬롯 조회
     *   4. 재전송 판정  ← **쓰기보다 먼저.** 뒤에 두면 replayed 인데 revision 이 올라간다
     *   5. 조건부 UPDATE (0행이면 409)
     *   6. 이력 배치     ← UPDATE 보다 뒤. 충돌이면 이력이 안 쌓인다
     * </pre>
     *
     * 4·5 가 6 보다 앞인 것이 핵심이다. 같은 트랜잭션이라 6 이 실패하면 5 도 롤백되지만,
     * <b>롤백은 안전망이지 정상 경로가 아니다</b> — M3 에서 세운 원칙 그대로다.
     *
     * @param force 충돌을 확인하고도 내 것으로 덮겠다는 뜻. <b>revision 조건을 건너뛰지는 않는다</b>(D-010) —
     *              클라는 409 로 받은 서버 revision 을 baseRevision 에 넣어 다시 보내야 한다.
     *              그래야 그 사이 끼어든 <i>세 번째</i> 기기까지 걸러진다. force 가 바꾸는 것은 이력뿐이다:
     *              이미 있는 seq·이벤트를 빼고 새 것만 INSERT 한다.
     * @param authUserId 인터셉터가 토큰에서 확인한 사용자 (M6-5). 회차 소유 검증(M6-6)의 기준.
     */
    @Transactional
    public SaveUploadResponse upsert(long playthroughId, int slotNo, boolean force,
                                     long authUserId, SaveUploadRequest request) {
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
        // M2·M3 요청과의 호환을 여기서 끊는다 — 의도한 것이다 (SaveUploadRequest 주석).
        if (request.baseRevision() == null) {
            throw new BadRequestException(
                    "baseRevision 이 없습니다. 직전 응답의 revision 을 그대로 보내십시오 (신규 슬롯은 0).");
        }
        long base = request.baseRevision();

        List<ChoiceUpload> choices = request.choicesOrEmpty();
        List<EventUpload> events = request.eventsOrEmpty();
        validateShape(choices, events);

        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));
        requireOwner(playthrough, authUserId);   // 없는 회차 404 → 남의 회차 403, 이 순서다 (M6-6)

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

        // D-011 파생 (M6-2b): 이미 이 회차에서 난 EventKey 는 **빼고 넣는다**.
        // V4 가 UNIQUE 를 (playthrough_id, event_key) 로 좁혀서, 챕터 버전을 올린 뒤의 재도달이나
        // 다른 슬롯에서의 재도달이 전부 "중복"이 됐다 — 그때마다 요청 전체를 409 로 떨어뜨리는 대신,
        // M4 가 choices 재전송을 replayed 로 흡수했듯 조용히 흡수한다. force 전용이던 이벤트 필터가
        // 모든 경로로 올라온 것이고, acceptedEvents 가 보낸 수보다 작은 것이 이제 정상이다.
        List<EventUpload> newEvents = withoutExistingEvents(playthroughId, events, eventKeyByEpisode);

        String snapshotJson = objectMapper.writeValueAsString(request.snapshot());
        int playSeconds = request.playSeconds() == null ? 0 : request.playSeconds();

        Optional<SaveSlotState> found = saveSlotRepository.findState(playthroughId, slotNo);

        // ── 신규 ────────────────────────────────────────────────────
        if (found.isEmpty()) {
            if (base != 0) {
                throw new BadRequestException(
                        "슬롯이 없는데 baseRevision 이 " + base + " 입니다. 신규 슬롯은 0 이어야 합니다.");
            }
            Long deviceId = resolveDevice(playthrough, request);
            saveSlotRepository.insert(playthroughId, slotNo, chapterContentId,
                    request.currentEpisodeId(), snapshotJson, playSeconds, deviceId);
            SaveSlotState created = reloadState(playthroughId, slotNo);
            // 신규 슬롯이어도 이벤트는 걸러진 것(newEvents)을 넣는다 — 이벤트는 슬롯이 아니라
            // **회차**에 속하므로, 다른 슬롯이 이미 기록한 EventKey 와 겹칠 수 있다.
            writeHistory(created.id(), playthroughId, chapterContentId, choices, newEvents, eventKeyByEpisode);
            return SaveUploadResponse.applied(created, choices.size(), newEvents.size());
        }

        SaveSlotState current = found.get();

        // ── 재전송 ──────────────────────────────────────────────────
        // 쓰기보다 먼저다. 뒤에 두면 "이미 했다"고 답하면서 revision 은 올라간다.
        if (!force && isReplay(current, base, choices)) {
            return SaveUploadResponse.replayed(current);
        }

        // ── 정상 / 충돌 ─────────────────────────────────────────────
        Long deviceId = resolveDevice(playthrough, request);
        int affected = saveSlotRepository.updateIfRevision(playthroughId, slotNo, base, chapterContentId,
                request.currentEpisodeId(), snapshotJson, playSeconds, deviceId);
        if (affected == 0) {
            // 지금 서버가 어떤 상태인지를 함께 준다 — 클라가 다시 GET 하는 왕복을 아끼고,
            // 그 사이 또 바뀔 여지도 없앤다. 이 필드들이 M8 충돌 UI 가 보여줄 것이다.
            throw new ConflictException(
                    "다른 기기가 먼저 저장했습니다. baseRevision=" + base,
                    saveSlotRepository.findSummary(playthroughId, slotNo).orElse(null));
        }

        SaveSlotState updated = reloadState(playthroughId, slotNo);

        // ── force: 선택도 새 것만 ────────────────────────────────────
        // 이벤트는 이미 위(newEvents)에서 걸렀다 — M6 부터 이벤트 흡수는 force 전용이 아니다 (M6-2b).
        // 선택은 다르다: (save_slot_id, seq) 재전송은 정상 경로에서 replayed 판정이 흡수하므로,
        // 판정을 건너뛰는 force 에서만 직접 걸러 낸다.
        List<ChoiceUpload> newChoices = choices;
        if (force) {
            Set<Integer> takenSeqs = choiceHistoryRepository.existingSeqs(updated.id(), seqsOf(choices));
            newChoices = choices.stream().filter(c -> !takenSeqs.contains(c.seq())).toList();
        }

        writeHistory(updated.id(), playthroughId, chapterContentId, newChoices, newEvents, eventKeyByEpisode);
        return SaveUploadResponse.applied(updated, newChoices.size(), newEvents.size());
    }

    /**
     * "이미 있으면 빼고 넣기" (M6-2b). 두 겹으로 거른다:
     * <ol>
     *   <li>DB — 이 회차에 이미 기록된 EventKey (existingEventKeys 한 번의 IN 조회, N+1 없음).</li>
     *   <li>요청 안 — 같은 요청에 같은 EventKey 가 두 번 오는 경우(개편 전후의 두 에피소드가
     *       같은 키를 가리키는 식). 첫 번째만 남긴다 — 안 거르면 배치 INSERT 자신이 UNIQUE 에 걸린다.</li>
     * </ol>
     * choices 의 요청 내 중복(seq)이 400 인 것과 결이 다른 이유: 같은 seq 두 번은 클라의 **모순**이지만,
     * 같은 EventKey 두 번은 새 UNIQUE 정의(회차당 1회)가 만든 **정상 데이터의 모양**이다. 모순은 알리고,
     * 정의의 결과는 흡수한다.
     */
    private List<EventUpload> withoutExistingEvents(long playthroughId,
                                                    List<EventUpload> events,
                                                    Map<String, String> eventKeyByEpisode) {
        if (events.isEmpty()) {
            return events;
        }
        Set<String> taken = eventLogRepository.existingEventKeys(
                playthroughId, new LinkedHashSet<>(eventKeyByEpisode.values()));
        Set<String> seenInRequest = new LinkedHashSet<>();
        List<EventUpload> fresh = new ArrayList<>();
        for (EventUpload event : events) {
            String eventKey = eventKeyByEpisode.get(event.episodeId());
            if (taken.contains(eventKey) || !seenInRequest.add(eventKey)) {
                continue;
            }
            fresh.add(event);
        }
        return fresh;
    }

    /**
     * "내가 방금 보낸 그 요청인가" (D-010).
     *
     * 두 조건이 **모두** 맞아야 한다:
     * <ol>
     *   <li>{@code 서버 revision == base + 1} — 정확히 한 번 적용된 상태다. 두 번 올랐으면 누가 더 썼다는 뜻.</li>
     *   <li>동봉된 seq 가 <b>전부</b> 이미 있다 — 하나라도 새 것이면 재전송이 아니라 새 요청이다.</li>
     * </ol>
     *
     * <p><b>choices 가 비면 판정하지 않는다.</b> 그러면 아래의 조건부 UPDATE 로 넘어가고,
     * base 가 낡았으므로 자연히 409 가 난다. PLAN 원문은 여기서 200 을 주라고 했지만 D-010 이 뒤집었다 —
     * 200 을 주면 <b>충돌을 재전송으로 오인</b>해, 다른 기기가 덮었는데도 클라는 "저장됐다"고 믿는다.
     * 세이브만 올리는 요청(choices 없음)은 드문 예외가 아니라 정상 경로라 그 오인이 자주 일어난다.
     *
     * <p>구현에 별도 분기가 없다는 점을 눈여겨볼 만하다. "판정 불가"를 특별 취급하지 않아도 답이 맞는다.
     */
    private boolean isReplay(SaveSlotState current, long base, List<ChoiceUpload> choices) {
        if (choices.isEmpty()) {
            return false;
        }
        if (current.revision() != base + 1) {
            return false;
        }
        List<Integer> seqs = seqsOf(choices);
        return choiceHistoryRepository.existingSeqs(current.id(), seqs).size() == seqs.size();
    }

    private void writeHistory(long saveSlotId, long playthroughId, long chapterContentId,
                              List<ChoiceUpload> choices, List<EventUpload> events,
                              Map<String, String> eventKeyByEpisode) {
        choiceHistoryRepository.insertAll(saveSlotId, chapterContentId, choices);
        eventLogRepository.insertAll(playthroughId, chapterContentId, events, eventKeyByEpisode);
    }

    /** deviceKey 는 선택이다. 없으면 device_id 가 NULL 로 남는다 — 그 슬롯도 목록에서 사라지면 안 된다. */
    private Long resolveDevice(Playthrough playthrough, SaveUploadRequest request) {
        if (request.deviceKey() == null || request.deviceKey().isBlank()) {
            return null;
        }
        return deviceRepository.upsert(playthrough.userId(), request.deviceKey());
    }

    /** revision·updated_at 은 DB 가 만든 값이다. 앱이 세면 두 요청이 겹칠 때 같은 값을 두 번 발급한다. */
    private SaveSlotState reloadState(long playthroughId, int slotNo) {
        return saveSlotRepository.findState(playthroughId, slotNo)
                .orElseThrow(() -> new IllegalStateException("방금 쓴 슬롯을 찾지 못했다"));
    }

    private static List<Integer> seqsOf(List<ChoiceUpload> choices) {
        return choices.stream().map(ChoiceUpload::seq).toList();
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
    public List<SaveSlotSummary> list(long playthroughId, long authUserId) {
        requireOwned(playthroughId, authUserId);
        return saveSlotRepository.findSummaries(playthroughId);
    }

    @Transactional(readOnly = true)
    public SaveSlotDetail get(long playthroughId, int slotNo, long authUserId) {
        requireOwned(playthroughId, authUserId);
        return saveSlotRepository.findDetail(playthroughId, slotNo)
                .orElseThrow(() -> new NotFoundException(
                        "슬롯이 없습니다: 회차 " + playthroughId + " 슬롯 " + slotNo));
    }

    /** 회차가 없다(404) / 남의 것이다(403) / 슬롯이 비어 있다(빈 배열·404) — 전부 다른 사실이다 (M6-6). */
    private void requireOwned(long playthroughId, long authUserId) {
        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));
        requireOwner(playthrough, authUserId);
    }

    /** 세이브는 회차를 통해 소유된다 — 슬롯 자체에는 user_id 가 없고, 그래서 검증 기준도 회차다. */
    private static void requireOwner(Playthrough playthrough, long authUserId) {
        if (!playthrough.userId().equals(authUserId)) {
            throw new ForbiddenException("다른 사용자의 회차입니다: id=" + playthrough.id());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 가 없거나 비어 있습니다.");
        }
    }
}
