package com.sparta.springprepare.controller;

import org.springframework.web.bind.annotation.*;

@RestController
public class TestController {
    @GetMapping("/hello/{name}/{age}")
    public String helloName(@PathVariable String name, @PathVariable int age) {
        return "Hello " + name + " " + age;
    }

    @GetMapping("/hello/age")
    public String helloAge(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "0") int age
    ) {
        return name + "은 " + age + "살입니다.";
    }

    @PostMapping("/hello")
    public String helloPost() {
        return "POST 요청입니다.";
    }
}