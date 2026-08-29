package com.sparta.springprepare.content;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 콘텐츠 저장소에 파일을 올리고, 버전별로 조회하는 HTTP API
 *
 * 두 가지가 M0 컨트롤러와 다르다.
 *  1. 요청을 byte[] 로 받는다 — checksum 이 바이트의 함수라서. ByteArrayHttpMessageConverter 가
 *     컨버터 목록의 맨 앞이라 어떤 Content-Type 이든 원본 바이트가 그대로 들어온다.
 *  2. 응답으로 원본 JSON 문자열을 그대로 통과시킨다. Jackson 이 다시 감싸면 안 되므로
 *     ResponseEntity<String> + Content-Type: application/json 을 명시한다.
 *     (StringHttpMessageConverter 의 기본 charset 은 ISO-8859-1 이지만 application/json 이면
 *     UTF-8 로 쓰는 예외 규칙이 있다 — 한글 라벨이 깨지지 않는 근거다.)
 */
@RestController
@RequestMapping("/content/chapters")
public class ChapterContentController {

    private final ChapterImportService service;

    public ChapterContentController(ChapterImportService service) {
        this.service = service;
    }

    /** 신규면 201, 있으면 200. 본문 모양은 같고 상태 코드만 구분. */
    @PostMapping
    public ResponseEntity<ChapterImportResponse> importChapter(@RequestBody byte[] rawBody) {
        ChapterImportResult result = service.importChapter(rawBody);

        return ResponseEntity
                .status(result.created()
                        ? HttpStatus.CREATED
                        : HttpStatus.OK)
                .body(ChapterImportResponse.from(result));
    }

    @GetMapping
    public List<ChapterSummary> listChapters() {
        return service.listChapters();
    }

    @GetMapping("/{chapterId}/versions")
    public List<ChapterVersionInfo> listVersions(@PathVariable String chapterId) {
        return service.listVersions(chapterId);
    }

    /**
     * 경로에 정규식 제약(\\d+)을 건 이유: 이것이 없으면 {version} 이 "latest" 와 "versions" 까지 삼켜
     * 아래 두 매핑과 충돌한다. 숫자만 받는다고 못박아 두면 의도가 경로에 드러난다.
     */
    @GetMapping("/{chapterId}/{version:\\d+}")
    public ResponseEntity<String> getBody(@PathVariable String chapterId, @PathVariable int version) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.getBody(chapterId, version));
    }

    @GetMapping("/{chapterId}/latest")
    public ResponseEntity<String> getLatestBody(@PathVariable String chapterId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.getLatestBody(chapterId));
    }
}