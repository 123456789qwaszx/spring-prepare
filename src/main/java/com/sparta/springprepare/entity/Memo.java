package com.sparta.springprepare.entity;

import com.sparta.springprepare.dto.MemoRequestDto;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Memo {
    private Long id;
    private String username;
    private String contents;

    public Memo(MemoRequestDto requestDto){
        this.username = requestDto.getUsername();
        this.contents = requestDto.getContents();
    }
}
