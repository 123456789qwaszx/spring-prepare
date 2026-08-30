package com.sparta.springprepare.save;

import com.sparta.springprepare.common.ForbiddenException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.playthrough.Playthrough;
import com.sparta.springprepare.playthrough.PlaythroughRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이력 **조회** (PLAN M3). 쓰기는 SaveSlotService 가 세이브 업로드와 한 트랜잭션으로 처리한다.
 *
 * 읽기를 따로 둔 이유: 쓰기 경로는 "한 요청에서 세 테이블이 함께" 라는 트랜잭션 이야기이고,
 * 읽기 경로는 JOIN 과 페이징 이야기다. 섞으면 SaveSlotService 가 두 가지 일을 하게 된다.
 */
@Service
public class SaveHistoryService {

    private final ChoiceHistoryRepository choiceHistoryRepository;
    private final EventLogRepository eventLogRepository;
    private final SaveSlotRepository saveSlotRepository;
    private final PlaythroughRepository playthroughRepository;

    public SaveHistoryService(ChoiceHistoryRepository choiceHistoryRepository,
                              EventLogRepository eventLogRepository,
                              SaveSlotRepository saveSlotRepository,
                              PlaythroughRepository playthroughRepository) {
        this.choiceHistoryRepository = choiceHistoryRepository;
        this.eventLogRepository = eventLogRepository;
        this.saveSlotRepository = saveSlotRepository;
        this.playthroughRepository = playthroughRepository;
    }

    /** 회차가 없으면 404, 남의 것이면 403 (M6-6). 이벤트가 하나도 없으면 빈 배열 — "없음"과 "0개"는 다르다. */
    @Transactional(readOnly = true)
    public List<EventLogItem> listEvents(long playthroughId, long authUserId) {
        requireOwned(playthroughId, authUserId);
        return eventLogRepository.findByPlaythrough(playthroughId);
    }

    /**
     * @param afterSeq 이 번호 **다음부터**. 클라가 마지막으로 받은 seq 를 주면 증분만 돌아온다.
     *                 M7 의 SyncQueue 가 "서버가 어디까지 알고 있나" 를 확인할 때 쓴다.
     */
    @Transactional(readOnly = true)
    public List<ChoiceHistoryItem> listChoices(long playthroughId, int slotNo, int afterSeq, long authUserId) {
        requireOwned(playthroughId, authUserId);
        SaveSlotState slot = saveSlotRepository.findState(playthroughId, slotNo)
                .orElseThrow(() -> new NotFoundException(
                        "슬롯이 없습니다: 회차 " + playthroughId + " 슬롯 " + slotNo));
        return choiceHistoryRepository.findAfter(slot.id(), afterSeq);
    }

    /**
     * 존재 확인 + 소유 검증 (M6-6). 순서가 의미다 — 없는 회차는 404, 있는데 남의 것이면 403.
     * 인터셉터가 못 하는 이유: 경로(/playthroughs/{id})에는 소유자가 없어 DB 를 봐야 안다.
     */
    private void requireOwned(long playthroughId, long authUserId) {
        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));
        if (!playthrough.userId().equals(authUserId)) {
            throw new ForbiddenException("다른 사용자의 회차입니다: id=" + playthroughId);
        }
    }
}
