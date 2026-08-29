package com.sparta.springprepare.playthrough;

import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 회차 = [1] 영구 계층의 그릇. 만들고, 목록을 보고, 끝낸다.
 *
 * UserRepository 를 주입받는 이유:
 * - 없는 userId 로 회차를 만들면 DB 는 FK 위반(→400)을 내지만,
 * - 클라에게 맞는 답은 "그 사용자가 없다"(404).
 */
@Service
public class PlaythroughService {

    private final PlaythroughRepository playthroughRepository;
    private final UserRepository userRepository;

    public PlaythroughService(PlaythroughRepository playthroughRepository, UserRepository userRepository) {
        this.playthroughRepository = playthroughRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public PlaythroughCreatedResponse create(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("사용자가 없습니다: id=" + userId);
        }
        return new PlaythroughCreatedResponse(playthroughRepository.insert(userId));
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
    public PlaythroughEndResponse end(long playthroughId) {
        Playthrough playthrough = playthroughRepository.findById(playthroughId)
                .orElseThrow(() -> new NotFoundException("회차가 없습니다: id=" + playthroughId));

        if (playthrough.endedAt() == null) {
            playthroughRepository.end(playthroughId);
            // 갱신된 ended_at 은 DB 시각이므로 다시 읽는다 (앱 시각을 쓰지 않는다는 M0 규칙과 같다).
            playthrough = playthroughRepository.findById(playthroughId).orElseThrow();
        }
        return new PlaythroughEndResponse(playthrough.id(), playthrough.endedAt());
    }
}