package com.sparta.springprepare.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}