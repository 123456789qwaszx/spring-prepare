package com.sparta.springprepare.playthrough;

import com.sparta.springprepare.common.AuthInterceptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 회차 API (PLAN M2 + M8-A).
 *
 * <p>생성이 201 과 200 두 답을 갖는다 (D-019): 새로 만들었으면 201 + Location, 같은 클라 id 가 이미 있으면
 * 그것을 200 으로. 본문 모양은 같다. M1 의 콘텐츠 재수입(같은 checksum → 200)과 같은 어법이다.
 */
@RestController
public class PlaythroughController {

    private final PlaythroughService service;

    public PlaythroughController(PlaythroughService service) {
        this.service = service;
    }

    @PostMapping("/users/{userId}/playthroughs")
    public ResponseEntity<PlaythroughCreatedResponse> create(@PathVariable long userId,
                                                             @RequestBody PlaythroughCreateRequest request) {
        PlaythroughService.Created result = service.create(userId, request);
        long id = result.response().playthroughId();

        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity
                .created(URI.create("/playthroughs/" + id + "/saves"))
                .body(result.response());
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
