package com.sparta.springprepare.controller;

import com.sparta.springprepare.dto.BookRequestDto;
import com.sparta.springprepare.dto.BookResponseDto;
import com.sparta.springprepare.entity.Book;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final Map<Long, Book> bookList = new HashMap<>();

    @PostMapping
    public BookResponseDto createBook(@RequestBody BookRequestDto requestDto) {
        Book book = new Book(requestDto);

        // TODO 1: id 정하기
        //   if(bookList >=0) 1, else(키 중 최댓값 + 1)

        // TODO 2: book에 id 기입, bookList에 저장

        return new BookResponseDto(book);
    }

    @GetMapping
    public List<BookResponseDto> getBooks() {
        // TODO 3: bookList의 Book들을 BookResponseDto 리스트로 바꿔서 반환
        return null;
    }
}