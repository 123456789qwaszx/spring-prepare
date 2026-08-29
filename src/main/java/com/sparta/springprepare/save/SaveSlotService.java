package com.sparta.springprepare.save;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.content.ChapterContentRepository;
import com.sparta.springprepare.playthrough.Playthrough;
import com.sparta.springprepare.playthrough.PlaythroughRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 세이브 업로드/복구.
 * 이 서비스가 검사하는 것은 스냅샷 외적인 것.(슬롯 번호 범위, 회차 존재, 콘텐츠 버전 존재.)
 */
@Service
public class SaveSlotService {

    /**
     * 슬롯 번호의 유효 범위.
     *
     * 이것은 "슬롯 개수 상한"이 아니다. 몇 개까지 쓸지는 클라이언트 정책이고,
     * 서버는 번호가 save_slots.slot_no(TINYINT) 에 담기는지만 보장.
     */
    private static final int SLOT_NO_MIN = 1;
    private static final int SLOT_NO_MAX = 127;

    private final SaveSlotRepository saveSlotRepository;
    private final DeviceRepository deviceRepository;
    private final PlaythroughRepository playthroughRepository;
    private final ChapterContentRepository chapterContentRepository;
    private final ObjectMapper objectMapper;

    public SaveSlotService(SaveSlotRepository saveSlotRepository,
                           DeviceRepository deviceRepository,
                           PlaythroughRepository playthroughRepository,
                           ChapterContentRepository chapterContentRepository,
                           ObjectMapper objectMapper) {
        this.saveSlotRepository = saveSlotRepository;
        this.deviceRepository = deviceRepository;
        this.playthroughRepository = playthroughRepository;
        this.chapterContentRepository = chapterContentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 한 트랜잭션 안에서 최대 두 테이블(devices, save_slots)이 쓰인다.
     * M3 에서 여기에 choice_history 와 event_log 가 더해져 세 테이블이 되고, 그때 이 경계가 진짜 일을 한다.
     *
     * 순서: 검증 → 조회(404 판정) → 기기 upsert → 슬롯 upsert → 상태 재조회.
     * 쓰기 전에 판정을 모두 끝내는 것이 원칙이다. 롤백은 안전망이지 정상 경로가 아니다.
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

        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));

        // 세이브는 chapter_id 가 아니라 **특정 버전**을 가리킨다 (schema.sql 주석).
        // 여기서 먼저 조회하지 않으면 FK 위반이 나고, 그것은 클라에게 "그 콘텐츠 버전이 서버에 없다"를
        // 알려 주지 못한다. 서비스가 404 로 번역한다.
        long chapterContentId = chapterContentRepository
                .findId(request.chapterId(), request.chapterVersion())
                .orElseThrow(() -> new NotFoundException(
                        "콘텐츠 버전이 없습니다: " + request.chapterId() + " v" + request.chapterVersion()));

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

        // revision 은 DB 가 올린 값이다. 앱이 세면 두 요청이 겹칠 때 같은 값을 두 번 발급한다.
        return saveSlotRepository.findState(playthroughId, slotNo)
                .orElseThrow(() -> new IllegalStateException("방금 upsert 한 슬롯을 찾지 못했다"));
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