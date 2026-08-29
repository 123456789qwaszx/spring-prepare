package com.sparta.springprepare.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

 /**
  * POST /users
  *     입력: username, password
  *     성공: 201 Created
  *     결과: id, username
  *     Location: /users/{id}
  *
  * GET /users/{id}
  *     입력: path variable id
  *     성공: 200 OK
  *     실패: 404 Not Found
  *
  * - /users HTTP endpoint를 담당하는 Controller
  */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 201 Created + Location 헤더.
     * "만들어졌다"와 "어디서 다시 읽을 수 있다"를 한 응답에 담는 REST 관례.
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@RequestBody UserCreateRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity
                .created(URI.create("/users/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id) {
        return userService.get(id);
    }
}