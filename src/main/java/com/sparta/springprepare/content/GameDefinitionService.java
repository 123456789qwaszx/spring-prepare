package com.sparta.springprepare.content;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.Checksum;
import com.sparta.springprepare.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * game.definition.json 보관. 챕터 수입과 같은 흐름이되 색인이 없어 테이블 하나만 쓴다.
 *
 * 서버는 이 파일의 내용을 전혀 해석하지 않는다 (PLAN 1.4: 해금 규칙은 클라와 서버가 같은 파일을 읽되
 * 규칙의 주인은 파일이지 서버가 아니다). 그래서 검증은 "JSON 객체인가" 하나뿐이다.
 */
@Service
public class GameDefinitionService {

    private final GameDefinitionRepository repository;
    private final ObjectMapper objectMapper;

    public GameDefinitionService(GameDefinitionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DefinitionImportResult importDefinition(byte[] rawBody) {
        String checksum = Checksum.sha256Hex(rawBody);

        var existing = repository.findVersionByChecksum(checksum);
        if (existing.isPresent()) {
            return new DefinitionImportResult(existing.get(), false);
        }

        // 파싱은 하되 필드는 읽지 않는다 — "유효한 JSON 객체인가"만 본다.
        // MySQL JSON 컬럼도 같은 검증을 하지만, 여기서 먼저 걸러야 400 이 되고 안 그러면 500 계열이 된다.
        JsonNode root = objectMapper.readTree(rawBody);
        if (!root.isObject()) {
            throw new BadRequestException("definition 은 JSON 객체여야 합니다.");
        }

        int version = repository.nextVersion();
        repository.insert(version, new String(rawBody, StandardCharsets.UTF_8), checksum);
        return new DefinitionImportResult(version, true);
    }

    @Transactional(readOnly = true)
    public String getBody(int version) {
        return repository.findBody(version)
                .orElseThrow(() -> new NotFoundException("definition 버전이 없습니다: v" + version));
    }

    @Transactional(readOnly = true)
    public String getLatestBody() {
        return repository.findLatestBody()
                .orElseThrow(() -> new NotFoundException("수입된 definition 이 없습니다."));
    }
}