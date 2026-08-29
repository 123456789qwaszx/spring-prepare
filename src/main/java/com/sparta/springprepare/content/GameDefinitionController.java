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

/** PLAN M1 의 definition API. 챕터 컨트롤러와 같은 규칙(byte[] 입력, 원본 JSON 출력). */
@RestController
@RequestMapping("/content/definition")
public class GameDefinitionController {

    private final GameDefinitionService service;

    public GameDefinitionController(GameDefinitionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DefinitionImportResponse> importDefinition(@RequestBody byte[] rawBody) {
        DefinitionImportResult result = service.importDefinition(rawBody);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new DefinitionImportResponse(result.version()));
    }

    @GetMapping("/latest")
    public ResponseEntity<String> getLatest() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.getLatestBody());
    }

    // {version} 이 "latest" 를 삼키지 않도록 숫자로 제한한다 (챕터 컨트롤러와 같은 이유).
    @GetMapping("/{version:\\d+}")
    public ResponseEntity<String> get(@PathVariable int version) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.getBody(version));
    }
}