---
name: mock-fault
description: |
  Mock Supplier 의 공급사별 모드(정상·장애·무응답)를 바꿔 타임아웃·부분 실패·실패 판정 통일을 재현한다.
  Triggers: "A 장애로 바꿔", "무응답 만들어", "Mock 모드 바꿔", "장애 재현", "부분 실패 보여줘", "복구해"
  Do NOT use for: 숙소 목록 API 장애(모드가 안 걸리므로 Mock 을 내려야 함), 검색 응답 확인(test-search-api 소관)
argument-hint: "{a|b} {normal|error|no-response}"
allowed-tools: Bash(curl*)
---

# Mock 장애 모드

## 전제
- Mock Supplier 가 9090 에 떠 있다(`run-local` 2번).
- 모드는 재고·요금 조회 API 에만 걸린다. 숙소 목록 API 는 항상 정상이다.

## 실행 순서
1. 모드 전환: `{a|b}` × `{normal|error|no-response}`
   ```bash
   curl -s -X POST 'http://localhost:9090/control/a/mode?value=error'
   curl -s -X POST 'http://localhost:9090/control/b/mode?value=no-response'
   ```
2. 검색을 날려 결과를 본다(`test-search-api` 스킬). 캐시가 차 있으면 검색이 공급사를 부르지 않으므로 먼저 `docker compose exec redis redis-cli FLUSHALL` 로 비운다.
3. 복구
   ```bash
   curl -s -X POST 'http://localhost:9090/control/a/mode?value=normal'
   curl -s -X POST 'http://localhost:9090/control/b/mode?value=normal'
   ```

## 확인 포인트

| 모드 | Supplier A 가 주는 것 | Supplier B 가 주는 것 | 우리 판정 |
|---|---|---|---|
| `normal` | 200 + 예제 데이터 | 200 + `resultCode: "0000"` | 성공 |
| `error` | 503 + `{ "error": "SERVICE_UNAVAILABLE" }` | 200 + `resultCode: "E503"` (HTTP 는 200!) | 둘 다 `UNAVAILABLE` |
| `no-response` | 10분 동안 응답 없음 | 같음 | 응답 타임아웃(3초) → `TIMEOUT` |

- B 는 장애여도 HTTP 200 이므로 본문 `resultCode` 를 읽어야 실패로 잡힌다(`docs/architecture.md` 4.2).
- `error` 는 검색에서 즉시 1회 재시도 뒤 `failures` 에 들어가고, `no-response` 는 재시도 없이 3초 뒤 `failures` 에 들어간다.
- 캐시가 차 있는 상태에서 장애를 걸면 검색은 그대로 캐시로 응답하고, 다음 갱신 바퀴(최대 5분)에서 상태 키에 실패가 기록돼 `failures` 가 붙는다(`query-cache` 3번).

## 주의사항
- 크론잡(`sync` 프로필)은 목록 API 를 쓰므로 이 모드의 영향을 받지 않는다. 목록 실패를 보려면 Mock 을 내리고 크론잡을 돌린다 → `CONNECTION`, 종료 코드 1.
- 모드는 Mock 프로세스 메모리에만 있어 Mock 을 재시작하면 `normal` 로 돌아온다.
