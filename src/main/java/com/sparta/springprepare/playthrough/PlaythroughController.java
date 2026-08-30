package com.sparta.springprepare.playthrough;

import com.sparta.springprepare.common.AuthInterceptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * "회차를 만드는 것"은 사용자에 속한 일이고, "회차를 끝내는 것"은 회차 자신의 일.
 *
 * M6 부터 두 경로의 보호 방식이 다르다:
 * - /users/{userId}/playthroughs — 인터셉터가 경로의 userId == 토큰 userId 를 이미 보장한다.
 * - /playthroughs/{id}/end — 경로에 소유자가 없으므로 서비스가 회차를 읽어 검증한다 (M6-6).
 *   그래서 end 만 @RequestAttribute 로 인증된 userId 를 넘긴다.
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
    public PlaythroughEndResponse end(@PathVariable long playthroughId,
                                      @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) long authUserId) {
        return service.end(playthroughId, authUserId);
    }
}