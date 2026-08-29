package com.sparta.springprepare.save;

import java.time.LocalDateTime;

/**
 * PUT 응답. upsert 직후 DB 에서 다시 읽은 값.
 *
 * revision 이 클라에게 나가는 이유는 M4 때문이다. 클라는 이 값을 보관했다가 다음 업로드에
 * baseRevision 으로 되돌려 보내고, 서버는 그것으로 충돌을 감지한다. 지금은 그냥 늘어나는 숫자지만
 * M4 에서 낙관적 동시성의 키가 된다.
 *
 * 레포지토리가 이 타입을 그대로 돌려준다 — DB 행의 일부와 응답의 모양이 같아서 나누지 않았다.
 * M4 에서 replayed 가 붙으면 그때 갈라진다.
 */
public record SaveUploadResponse(Long revision, LocalDateTime updatedAt) {
}