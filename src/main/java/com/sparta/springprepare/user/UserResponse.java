package com.sparta.springprepare.user;

/**
 * /users 응답. password는 어떤 경우에도 나가지 않음.
 * 그걸 위해 User(행)와 UserResponse(응답)를 나눠둠.
 */
public record UserResponse(Long id, String username) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.username());
    }
}