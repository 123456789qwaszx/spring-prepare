package com.sparta.springprepare.dto;

import com.sparta.springprepare.entity.Book;
import lombok.Getter;

@Getter
public class BookResponseDto {
    private Long id;
    private String title;
    private String comment;

    public BookResponseDto(Book book) {
        this.id = book.getId();
        this.title = book.getTitle();
        this.comment = book.getComment();
    }
}