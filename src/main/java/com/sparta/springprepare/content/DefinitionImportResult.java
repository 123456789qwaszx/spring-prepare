package com.sparta.springprepare.content;

/** 서비스 → 컨트롤러. created 가 상태 코드(201/200)를 정한다. */
public record DefinitionImportResult(int version, boolean created) {
}