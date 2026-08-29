package com.sparta.springprepare.save;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /playthroughs/{pid}/events — 이 회차에서 무슨 일이 있었는가 (PLAN M3).
 *
 * SaveSlotController 와 나눈 이유는 경로다. 이벤트는 슬롯이 아니라 **회차**에 속한다 —
 * 슬롯을 지워도 이벤트는 남아야 하고(그래서 event_log 의 FK 도 save_slots 가 아니라 playthroughs 다),
 * 여러 슬롯에서 같은 이벤트가 발생할 수 있다.
 */
@RestController
@RequestMapping("/playthroughs/{playthroughId}/events")
public class EventLogController {

    private final SaveHistoryService service;

    public EventLogController(SaveHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<EventLogItem> list(@PathVariable long playthroughId) {
        return service.listEvents(playthroughId);
    }
}
