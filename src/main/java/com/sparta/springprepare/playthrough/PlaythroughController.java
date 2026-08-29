package com.sparta.springprepare.playthrough;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * userId 가 경로에 그대로 드러나 있고 지금은 누구나 남의 회차를 만들 수 있다. M6 인증까지 의도된 상태.
 * "회차를 만드는 것"은 사용자에 속한 일이고, "회차를 끝내는 것"은 회차 자신의 일
 */
@RestController
public class PlaythroughController {

    private final PlaythroughService service;

    public PlaythroughController(PlaythroughService service) {
        this.service = service;
    }

    @PostMapping("/users/{userId}/playthroughs")
    public ResponseEntity<PlaythroughCreatedResponse> create(@PathVariable long userId) {
        PlaythroughCreatedResponse created = service.create(userId);
        return ResponseEntity
                .created(URI.create("/playthroughs/" + created.playthroughId() + "/saves"))
                .body(created);
    }

    @GetMapping("/users/{userId}/playthroughs")
    public List<PlaythroughSummary> list(@PathVariable long userId) {
        return service.listByUser(userId);
    }

    @PostMapping("/playthroughs/{playthroughId}/end")
    public PlaythroughEndResponse end(@PathVariable long playthroughId) {
        return service.end(playthroughId);
    }
}