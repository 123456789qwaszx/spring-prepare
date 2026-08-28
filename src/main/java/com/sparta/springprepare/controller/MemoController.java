package com.sparta.springprepare.controller;

import com.sparta.springprepare.dto.MemoRequestDto;
import com.sparta.springprepare.dto.MemoResponseDto;
import com.sparta.springprepare.entity.Memo;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/memos")
public class MemoController {
    private final Map<Long, Memo> memoList = new HashMap<>();

    @PostMapping
    public MemoResponseDto createMemo(@RequestBody MemoRequestDto requestDto) {
        return null;
    }

    @GetMapping
    public List<MemoResponseDto> getMemos() {
        return null;
    }

    @PutMapping("/{id}")
    public Long updateMemo(@PathVariable Long id, @RequestBody MemoRequestDto requestDto) {

        // TODO:
        // - memoList에 해당 id가 있는지 확인
        // - 있으면 값 변경
        // - id 반환

        return null;
    }

    @DeleteMapping("/{id}")
    public Long deleteMemo(@PathVariable Long id) {

        // TODO:
        // - memoList에 해당 id가 있는지 확인
        // - memoList.remove(id)
        // - id 반환

        return null;
    }
}