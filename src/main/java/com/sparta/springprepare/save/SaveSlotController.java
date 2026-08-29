package com.sparta.springprepare.save;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 세이브 슬롯 API.
 *
 * POST 가 아니라 PUT 인 이유: "슬롯 3 을 이 내용으로 만든다" 가 아니라 "슬롯 3 은 이 내용이다" 이기 때문이다.
 * 같은 요청을 두 번 보내면 결과가 같아야 하는 자리이고, 그래서 상태 코드도 201 이 아니라 200 이다.
 * (revision 은 올라가지만 그것은 "몇 번째 업로드인가"의 기록이지 새 자원이 생긴 것은 아니다.)
 */
@RestController
@RequestMapping("/playthroughs/{playthroughId}/saves")
public class SaveSlotController {

    private final SaveSlotService service;

    public SaveSlotController(SaveSlotService service) {
        this.service = service;
    }

    @PutMapping("/{slotNo}")
    public SaveUploadResponse upsert(@PathVariable long playthroughId,
                                     @PathVariable int slotNo,
                                     @RequestBody SaveUploadRequest request) {
        return service.upsert(playthroughId, slotNo, request);
    }

    @GetMapping
    public List<SaveSlotSummary> list(@PathVariable long playthroughId) {
        return service.list(playthroughId);
    }

    @GetMapping("/{slotNo}")
    public SaveSlotDetail get(@PathVariable long playthroughId, @PathVariable int slotNo) {
        return service.get(playthroughId, slotNo);
    }
}