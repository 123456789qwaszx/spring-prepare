package com.sparta.springprepare.common;

import java.nio.charset.StandardCharsets;

/**
 * 스냅샷 크기 상한 (M8-A, D-022). 1MB — 클라는 백로그 300줄 상한으로 그 아래(수십 KB)에 머문다.
 *
 * <p>전송 층이 아니라 여기서 재는 이유: Tomcat 은 JSON 본문에 상한을 두는 스위치가 없고,
 * 재야 할 것은 요청 전체가 아니라 <b>스냅샷</b>이다 — choices 배열이 큰 것은 다른 문제다.
 * 서비스가 {@code writeValueAsString} 으로 되돌린 문자열의 UTF-8 바이트가 곧 JSON 컬럼에 들어갈 크기다.
 */
public final class SnapshotLimit {

    public static final int MAX_BYTES = 1_048_576;

    private SnapshotLimit() {
    }

    /** @param what 메시지용 — "snapshot" / "bookmark snapshot". */
    public static void check(String json, String what) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new PayloadTooLargeException(
                    what + " 이(가) 상한을 넘었습니다: " + bytes + " bytes > " + MAX_BYTES);
        }
    }
}
