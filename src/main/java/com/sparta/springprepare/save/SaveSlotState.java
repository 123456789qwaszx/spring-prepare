package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * upsert 직후 DB 에서 다시 읽은 슬롯의 상태.
 *
 * id 가 있는 이유: M3 의 choice_history 가 `save_slot_id` 로 이 슬롯을 가리켜야 한다.
 * upsert 는 신규든 갱신이든 생성 키를 주지 않으므로(갱신 경로에는 키가 없다), 재조회로 얻는다.
 *
 * revision·updatedAt 은 **DB 가 만든 값**이다. 앱이 세면 두 요청이 겹칠 때 같은 값을 두 번 발급한다.
 */
public record SaveSlotState(Long id, Long revision, OffsetDateTime updatedAt) {
}
