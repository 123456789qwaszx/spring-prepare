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
        Memo memo = new Memo(requestDto);

        Long maxId = memoList.isEmpty()
                ? 1L
                : Collections.max(memoList.keySet()) + 1;

        memo.setId(maxId);

        memoList.put(memo.getId(), memo);

        return new MemoResponseDto(memo);
    }

    @GetMapping
    public List<MemoResponseDto> getMemos() {
        List<MemoResponseDto> responseList = new ArrayList<>();

        for (Memo memo : memoList.values()) {
            responseList.add(new MemoResponseDto(memo));
        }

        return responseList;
    }

    @PutMapping("/{id}")
    public Long updateMemo(@PathVariable Long id, @RequestBody MemoRequestDto requestDto) {
        // memoList에 해당 id가 있는지 확인, 있는 경우만 값 변경
        if (memoList.containsKey(id)) {
            Memo memo = memoList.get(id);

            memo.setUsername(requestDto.getUsername());
            memo.setContents(requestDto.getContents());

            // id 반환
            return memo.getId();
        } else {
            throw new IllegalArgumentException("선택한 메모는 존재하지 않습니다.");
        }
    }

    @DeleteMapping("/{id}")
    public Long deleteMemo(@PathVariable Long id) {
        // memoList에 해당 id가 있는지 확인, 있는 경우만 memoList.remove(id)
        if (memoList.containsKey(id)) {
            memoList.remove(id);

            // - id 반환
            return id;
        } else {
            throw new IllegalArgumentException("선택한 메모는 존재하지 않습니다.");
        }
    }
}