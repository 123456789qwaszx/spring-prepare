package com.sparta.springprepare.user;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 유스케이스.
 * - 사용자 생성 및 조회.
 *
 * M0 에서는 얇지만, 트랜잭션 경계를 Service 에 둘 예정.
 * M6 에서 이 클래스에 비밀번호 해시가 들어온다. Controller 나 Repository 가 아니라 여기인 이유:
 * "어떤 값을 저장할지"는 유스케이스의 결정이고, Repository 는 받은 값을 넣기만 한다.
 */
@Service
public class UserService {

    /**
     * Service가 필요한 데이터를 Repository에게 요청.
     * "어떤 값을 저장할지"는 유스케이스의 결정이고, Repository는 받은 값을 넣기만 함.
     * */
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * '사용자 생성' useCase를 트랙잭션 단위로 실행. 입력 DTO 받아서 출력 DTO 반환.
     * 트랜잭션 경계.
     */
    @Transactional
    public UserResponse create(UserCreateRequest request) {

        if (request.username() == null || request.username().isBlank()) {
            throw new BadRequestException("username 은 비어 있을 수 없습니다.");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new BadRequestException("password 는 비어 있을 수 없습니다.");
        }

        // M6-3: 평문이 아니라 해시를 저장한다. 해시는 단방향이라 유출돼도 원문을 복원할 수 없고,
        // 검증은 "복호화해서 비교"가 아니라 "다시 해싱해서 비교"(matches)다 — 해시 vs 암호화의 차이.
        // 여기(유스케이스)서 encode 하는 이유: "어떤 값을 저장할지"는 서비스의 결정이다 (클래스 주석).
        long id = userRepository.insert(request.username(), passwordEncoder.encode(request.password()));
        return new UserResponse(id, request.username());
    }

    /** readOnly = true: 이 useCase에서는 DB데이터를 변경하지 않는다는 의도 명시 */
    @Transactional(readOnly = true)
    public UserResponse get(long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("사용자가 없습니다: id=" + id));
        return UserResponse.from(user);
    }
}