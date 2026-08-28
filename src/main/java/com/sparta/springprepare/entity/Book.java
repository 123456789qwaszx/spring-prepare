package com.sparta.springprepare.entity;

import com.sparta.springprepare.dto.BookRequestDto;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Book {
    private Long id;
    private String title;
    private String comment;

    public Book(BookRequestDto requestDto) {
        this.title = requestDto.getTitle();
        this.comment = requestDto.getComment();
    }
}