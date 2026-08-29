package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/** GET /playthroughs/{pid}/saves/{slotNo}/choices 의 한 줄. */
public record ChoiceHistoryItem(
        Integer seq,
        String episodeId,
        Integer optionIndex,
        OffsetDateTime chosenAt) {
}
