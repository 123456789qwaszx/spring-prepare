package com.sparta.springprepare.save;

import com.sparta.springprepare.common.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 세이브 슬롯 API (PLAN M2 + M3).
 *
 * POST 가 아니라 PUT 인 이유: "슬롯 3 을 이 내용으로 만든다" 가 아니라 "슬롯 3 은 이 내용이다" 이기 때문이다.
 * 같은 요청을 두 번 보내면 결과가 같아야 하는 자리(멱등)이고, 그래서 상태 코드도 201 이 아니라 200 이다.
 * (revision 은 올라가지만 그것은 "몇 번째 업로드인가"의 기록이지 새 자원이 생긴 것은 아니다.
 *  진짜 멱등 — 같은 요청이면 revision 도 안 오르는 것 — 은 M4 에서 baseRevision 으로 만든다.)
 *
 * M3 에서 이 PUT 의 성격이 한 번 바뀐다. 스냅샷만 실을 때는 덮어쓰기였지만, choices 를 함께 실으면
 * 그 부분은 **누적**이다 — 같은 요청을 두 번 보내면 두 번째는 409 가 난다. 멱등이 깨진 것처럼 보이지만
 * 깨진 게 아니라, 클라가 seq 를 다시 매기지 않는 한 같은 요청이 아니게 된 것이다.
 * M4 에서 이 409 를 "이미 받았다"(200 replayed)로 바꾸면 멱등이 회복된다.
 */
@RestController
@RequestMapping("/playthroughs/{playthroughId}/saves")
public class SaveSlotController {

    private final SaveSlotService service;
    private final SaveHistoryService historyService;

    public SaveSlotController(SaveSlotService service, SaveHistoryService historyService) {
        this.service = service;
        this.historyService = historyService;
    }

    /**
     * @param force 충돌을 확인하고도 내 것으로 덮겠다는 뜻 (M4). 쿼리 파라미터인 이유는 자원을 식별하는 값이
     *              아니라 **처리 방식**을 고르는 값이기 때문이다. 본문에 넣으면 세이브 데이터의 일부처럼 보인다.
     *
     *              <p>force 여도 {@code baseRevision} 은 여전히 맞아야 한다 (D-010). 409 로 받은 서버
     *              revision 을 넣어 다시 보내는 것이 정상 흐름이고, 그래야 그 사이 끼어든 세 번째 기기도 걸린다.
     *              "무조건 덮어쓰기"가 아니라 **"내가 본 그 상태 위에 덮어쓰기"** 다.
     */
    @PutMapping("/{slotNo}")
    public SaveUploadResponse upsert(@PathVariable long playthroughId,
                                     @PathVariable int slotNo,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) long authUserId,
                                     @RequestBody SaveUploadRequest request) {
        return service.upsert(playthroughId, slotNo, force, authUserId, request);
    }

    @GetMapping
    public List<SaveSlotSummary> list(@PathVariable long playthroughId,
                                      @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) long authUserId) {
        return service.list(playthroughId, authUserId);
    }

    @GetMapping("/{slotNo}")
    public SaveSlotDetail get(@PathVariable long playthroughId, @PathVariable int slotNo,
                              @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) long authUserId) {
        return service.get(playthroughId, slotNo, authUserId);
    }

    /**
     * GET …/saves/{slotNo}/choices?afterSeq=N — 이 슬롯의 선택 이력 (PLAN M3).
     *
     * 경로가 슬롯 아래인 이유는 choice_history 의 FK 가 save_slots 를 가리키기 때문이다.
     * 이벤트는 회차에 속하고(EventLogController) 선택은 슬롯에 속한다 — 경로가 소유 관계를 그대로 보인다.
     *
     * afterSeq 를 경로가 아니라 쿼리 파라미터로 둔 이유: 자원을 식별하는 값이 아니라 **거르는** 값이다.
     * `defaultValue = "0"` 이라 안 주면 전부 나온다.
     */
    @GetMapping("/{slotNo}/choices")
    public List<ChoiceHistoryItem> choices(@PathVariable long playthroughId,
                                           @PathVariable int slotNo,
                                           @RequestParam(defaultValue = "0") int afterSeq,
                                           @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) long authUserId) {
        return historyService.listChoices(playthroughId, slotNo, afterSeq, authUserId);
    }
}
