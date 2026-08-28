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

        // id 정하기
        Long maxId = bookList.isEmpty()
                ? 1L
                : Collections.max(bookList.keySet()) + 1;

        // book에 id 기입
        book.setId(maxId);

        // bookList에 저장
        bookList.put(book.getId(), book);

        return new BookResponseDto(book);
    }

    @GetMapping
    public List<BookResponseDto> getBooks() {
        List<BookResponseDto> responseList = new ArrayList<>();

        // bookList의 Book들을 BookResponseDto 리스트로 반환
        for (Book book : bookList.values()) {
            responseList.add(new BookResponseDto(book));
        }

        return responseList;
    }
}