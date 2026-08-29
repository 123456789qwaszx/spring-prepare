package com.sparta.springprepare.content;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.Checksum;
import com.sparta.springprepare.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 콘텐츠 수입 - 트랜잭션.
 *
 * 한 요청에서 두 테이블(chapter_contents, chapter_episodes)이 함께 쓰인다.
 * 색인 INSERT 가 실패하면 본문도 남지 않아야 한다 — 그것을 보장하는 것이 아래 @Transactional 하나다.
 *
 * 해석 금지 원칙(PLAN 1.4): 서버는 이 JSON 을 도메인 객체로 역직렬화하지 않음.
 * JsonNode 로 열어 **색인에 쓸 필드만** 읽고, 나머지는 원본 그대로 body 에 넣는다.
 * 조건·스탯 변화·선택지 내용은 읽지도 저장하지도 않는다.
 */
@Service
public class ChapterImportService {

    private final ChapterContentRepository repository;
    private final ObjectMapper objectMapper;   // tools.jackson (Jackson 3). Boot 가 자동 등록한다.

    public ChapterImportService(ChapterContentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /*** @param rawBody 요청 본문의 **원본 바이트**.*/
    @Transactional
    public ChapterImportResult importChapter(byte[] rawBody) {

        // checksum 계산
        String checksum = Checksum.sha256Hex(rawBody);

        // 이미 있으면 기존 버전 반환.
        Optional<ChapterContent> existing = repository.findByChecksum(checksum);

        if (existing.isPresent()) {
            ChapterContent row = existing.get();

            return new ChapterImportResult(
                    row.chapterId(), row.version(), repository.countEpisodes(row.id()), false);
        }

        // Json 파싱(필수 색인 정보 추출)
        JsonNode root = objectMapper.readTree(rawBody);

        String chapterId = requiredText(root, "ChapterId");
        String startEpisodeId = requiredText(root, "StartEpisodeId");

        JsonNode nodes = root.path("Nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new BadRequestException("Nodes 가 없거나 비어 있습니다.");
        }

        String displayName = root.path("DisplayName").asString("");

        // 버전 계산
        int version = repository.nextVersion(chapterId);

        // chapter_contents INSERT
        String body = new String(rawBody, StandardCharsets.UTF_8);
        long contentId = repository.insertContent(chapterId, version, displayName, startEpisodeId, body, checksum);

        // episode 목록 생성
        List<ChapterEpisode> episodes = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            episodes.add(new ChapterEpisode(
                    contentId,
                    node.path("EpisodeId").asString(""),
                    node.path("Title").asString(""),
                    node.path("EventKey").asString(""),
                    node.path("NextOptions").size()));
        }

        // chapter_episodes batch INSERT
        repository.insertEpisodes(episodes);

        return new ChapterImportResult(chapterId, version, episodes.size(), true);
    }

    @Transactional(readOnly = true)
    public List<ChapterSummary> listChapters() {
        return repository.findSummaries();
    }

    @Transactional(readOnly = true)
    public List<ChapterVersionInfo> listVersions(String chapterId) {
        List<ChapterVersionInfo> versions = repository.findVersions(chapterId);
        if (versions.isEmpty()) {
            throw new NotFoundException("챕터가 없습니다: " + chapterId);
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public String getBody(String chapterId, int version) {
        return repository.findBody(chapterId, version)
                .orElseThrow(() -> new NotFoundException(
                        "챕터 버전이 없습니다: " + chapterId + " v" + version));
    }

    @Transactional(readOnly = true)
    public String getLatestBody(String chapterId) {
        return repository.findLatestBody(chapterId)
                .orElseThrow(() -> new NotFoundException("챕터가 없습니다: " + chapterId));
    }

    /** 필수 문자열 필드. 없거나 비면 400 — 구조 검증은 VnTool 과 런타임 로더의 일이고, 여기선 색인에 꼭 필요한 것만 본다. */
    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asString("");
        if (value.isBlank()) {
            throw new BadRequestException(field + " 가 없거나 비어 있습니다.");
        }
        return value;
    }
}