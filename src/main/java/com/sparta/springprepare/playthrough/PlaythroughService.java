package com.sparta.springprepare.playthrough;

import com.sparta.springprepare.bookmark.BookmarkRepository;
import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.ForbiddenException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 회차 = [1] 영구 계층의 그릇. 만들고, 목록을 보고, 끝낸다.
 *
 * UserRepository 를 주입받는 이유:
 * - 없는 userId 로 회차를 만들면 DB 는 FK 위반(→400)을 내지만,
 * - 클라에게 맞는 답은 "그 사용자가 없다"(404).
 *
 * <h3>M8-A 에서 바뀐 것 — 생성이 멱등이 됐다 (D-019·D-020)</h3>
 * 클라가 회차의 신원(로컬 guid)을 보내고, 서버는 "있으면 그것, 없으면 만든다". 갈래는 부모를 클라 id 로
 * 적고, 서버 id 는 찾히면 채우고 아니면 부모가 오는 순간 되채운다. 도착 순서를 가정하지 않는다.
 */
@Service
public class PlaythroughService {

    /** client_id VARCHAR(32). Guid "N" 형식이 정확히 32 hex 다. */
    private static final int CLIENT_ID_MAX = 32;

    private final PlaythroughRepository playthroughRepository;
    private final UserRepository userRepository;
    private final BookmarkRepository bookmarkRepository;

    public PlaythroughService(PlaythroughRepository playthroughRepository, UserRepository userRepository,
                              BookmarkRepository bookmarkRepository) {
        this.playthroughRepository = playthroughRepository;
        this.userRepository = userRepository;
        this.bookmarkRepository = bookmarkRepository;
    }

    /** 생성 결과 + "새로 만들었는가". 컨트롤러가 201/200 을 가르는 데 쓴다. */
    public record Created(PlaythroughCreatedResponse response, boolean created) {
    }

    /**
     * 있으면 그것(200), 없으면 만든다(201).
     *
     * <p>순서:
     * <ol>
     *   <li>형식 검증 — clientPlaythroughId 필수, 갈래면 부모 클라 id 필수 → 400</li>
     *   <li>사용자 존재 → 404 (인터셉터가 본인임은 보장했지만 서비스 방어로 남긴다)</li>
     *   <li>클라 id 조회 → 있으면 그대로 반환. <b>아무것도 쓰지 않는다.</b></li>
     *   <li>부모(갈래면) 를 클라 id 로 찾는다 — 없어도 된다(D-b)</li>
     *   <li>INSERT. 같은 순간 같은 id 로 두 요청이 오면 한쪽이 UNIQUE 에 걸린다 — 그때는 다시 조회해
     *       그것을 200 으로 돌려준다. 멱등의 마지막 조각은 "경쟁에서 진 쪽도 같은 답을 받는다"다.</li>
     *   <li>되채우기 — 이 id 를 부모로 적고 먼저 와 있던 갈래들, 출처로 적고 먼저 와 있던 즐겨찾기들의
     *       서버 링크를 닫는다.</li>
     * </ol>
     */
    @Transactional
    public Created create(long userId, PlaythroughCreateRequest request) {
        String clientId = requireClientId(request.clientPlaythroughId(), "clientPlaythroughId");

        ForkOrigin fork = request.forkedFrom();
        String parentClientId = null;
        if (fork != null) {
            parentClientId = requireClientId(fork.clientPlaythroughId(), "forkedFrom.clientPlaythroughId");
            if (fork.sceneIndex() == null || fork.sceneIndex() < 0) {
                throw new BadRequestException("forkedFrom.sceneIndex 는 0 이상이어야 합니다.");
            }
        }

        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("사용자가 없습니다: id=" + userId);
        }

        Optional<Long> existing = playthroughRepository.findIdByClientId(userId, clientId);
        if (existing.isPresent()) {
            return new Created(new PlaythroughCreatedResponse(existing.get(), clientId), false);
        }

        // 부모의 서버 id 는 있으면 지금, 없으면 나중에(되채우기). 요청의 forkedFrom.playthroughId 는 쓰지 않는다
        // (ForkOrigin 주석) — 소유 검증 없는 서버 id 를 믿을 이유가 없고, 클라 id 로 같은 사용자 안에서 찾으면 된다.
        Long parentId = parentClientId == null
                ? null
                : playthroughRepository.findIdByClientId(userId, parentClientId).orElse(null);

        long id;
        try {
            id = playthroughRepository.insert(userId, clientId, parentId, parentClientId,
                    fork == null ? null : fork.sceneIndex());
        } catch (DuplicateKeyException race) {
            // 조회와 INSERT 사이에 같은 id 가 먼저 들어갔다 — 그 회차가 정답이다.
            // (M4 의 동시성 테스트가 잡은 종류의 틈이다. UNIQUE 가 막고, 앱은 막힌 쪽을 같은 답으로 접는다.)
            long won = playthroughRepository.findIdByClientId(userId, clientId)
                    .orElseThrow(() -> race);
            return new Created(new PlaythroughCreatedResponse(won, clientId), false);
        }

        // 되채우기 둘 — 이 클라 id 를 부모로 적고 먼저 와 있던 갈래, 출처로 적고 먼저 와 있던 즐겨찾기.
        playthroughRepository.backfillChildren(userId, clientId, id);
        bookmarkRepository.backfillPlaythrough(userId, clientId, id);

        return new Created(new PlaythroughCreatedResponse(id, clientId), true);
    }

    private static String requireClientId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 이(가) 없습니다.");
        }
        if (value.length() > CLIENT_ID_MAX) {
            throw new BadRequestException(field + " 은(는) " + CLIENT_ID_MAX + "자 이하여야 합니다.");
        }
        return value;
    }

    /** 사용자가 없으면 404. 회차가 하나도 없으면 빈 배열 — "없음"과 "0개"는 다르다. */
    @Transactional(readOnly = true)
    public List<PlaythroughSummary> listByUser(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("사용자가 없습니다: id=" + userId);
        }
        return playthroughRepository.findSummariesByUser(userId);
    }

    /**
     * 이미 끝난 회차를 다시 끝내도 200 이고 endedAt 은 처음 값 그대로.
     * 409 로 할 수도 있지만, 클라가 네트워크 재시도를 하면 "끝내기"는 여러 번 도착할 수 있다.
     * 같은 요청이 여러 번 와도 결과가 같은 편이 클라를 단순하게 만듬.
     */
    @Transactional
    public PlaythroughEndResponse end(long playthroughId, long authUserId) {
        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));

        // 소유 검증 (M6-6). 존재 확인 뒤에 온다 — 없는 회차는 404, 남의 회차는 403 으로 갈라진다.
        // (create·listByUser 는 경로가 /users/{id}/… 라 인터셉터가 이미 본인임을 보장했다 — 여기만 서비스 몫이다.)
        if (!playthrough.userId().equals(authUserId)) {
            throw new ForbiddenException("다른 사용자의 회차입니다: id=" + playthroughId);
        }

        if (playthrough.endedAt() == null) {
            playthroughRepository.end(playthroughId);
            // 갱신된 ended_at 은 DB 시각이므로 다시 읽는다 (앱 시각을 쓰지 않는다는 M0 규칙과 같다).
            playthrough = playthroughRepository.findById(playthroughId).orElseThrow();
        }
        return new PlaythroughEndResponse(playthrough.id(), playthrough.endedAt());
    }
}
