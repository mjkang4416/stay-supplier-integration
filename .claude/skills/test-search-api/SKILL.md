---
name: test-search-api
description: |
  통합 검색 API 를 캐시 적중 → 저하 모드 → 부분 실패 → 타임아웃 → 전부 실패 → 매진 포함 → 잘못된 요청 → 실시간 재확인 순서로 curl 로 확인해요.
  Triggers: "검색 확인해줘", "검색 API 테스트", "부분 실패 확인", "503 확인", "fresh=true 확인", "curl 로 검색"
  Do NOT use for: 서버 기동(run-local), Mock 모드만 바꾸기(mock-fault), 자동화 테스트 실행(`./gradlew test`)
argument-hint: "시나리오 번호 (1\~8, 생략하면 전부)"
allowed-tools: Bash(curl*), Bash(docker compose*), Bash(python3 -m json.tool*)
---

# 검색 API 확인

## 전제
- `run-local` 스킬로 MySQL·Redis·Mock·애플리케이션이 떠 있고 매핑이 들어가 있어요. 포트를 바꿨으면 아래 8080 을 맞춰요.
- 캐시 창은 오늘부터 30일이라 그 안의 날짜를 써요(아래 2026-09-20\~23 은 예시). Mock 은 어떤 날짜든 예제 패턴으로 응답해요.

## 실행 순서
```bash
Q='http://localhost:8080/api/v1/stays/search?checkIn=2026-09-20&checkOut=2026-09-23&adults=2&children=0'

# 1. 캐시 적중: "fresh": false, 수 ms. Riverside A(429,000)·Riverside B(452,000, 조식 포함). Namsan 은 하루 재고 0 이라 제외
curl -s "$Q" | python3 -m json.tool

# 2. 캐시 비우기 → 저하 모드: "fresh": true, 공급사 직접 호출, 같은 값
docker compose exec redis redis-cli FLUSHALL
curl -s "$Q" | python3 -m json.tool

# 3. Supplier A 장애 (캐시가 비어 있어야 검색이 직접 호출한다) → 200 + failures: [{ "supplier": "A", "reason": "UNAVAILABLE" }], B 결과만
curl -s -X POST 'http://localhost:9090/control/a/mode?value=error'
curl -s "$Q" | python3 -m json.tool

# 4. Supplier A 무응답 → 약 3초 뒤 200 + failures: [{ "supplier": "A", "reason": "TIMEOUT" }]
curl -s -X POST 'http://localhost:9090/control/a/mode?value=no-response'
curl -s -w '\n%{time_total}s\n' "$Q"

# 5. B 도 장애 → 503 + Retry-After: 5
curl -s -X POST 'http://localhost:9090/control/b/mode?value=error'
curl -s -i "$Q"

# 6. 복구 → 다음 갱신 바퀴(최대 5분)부터 다시 캐시 적중
curl -s -X POST 'http://localhost:9090/control/a/mode?value=normal'
curl -s -X POST 'http://localhost:9090/control/b/mode?value=normal'

# 7. 예약 불가 포함 → Namsan 이 availableRooms 0 으로 나온다
curl -s "$Q&includeSoldOut=true" | python3 -m json.tool

# 8. 잘못된 요청 → 400 INVALID_REQUEST
curl -s 'http://localhost:8080/api/v1/stays/search?checkIn=2026-09-04&checkOut=2026-09-01&adults=2'

# 9. 캐시가 있어도 직접 호출 (예약 직전 재확인)
curl -s "$Q&fresh=true" | python3 -m json.tool
```

## 확인 포인트
- 기대값의 근거는 `docs/stay-search-api.md` 1.5 예시와 2장 조회 규칙. 요금은 세금 포함 총액, 예약 가능 객실 수는 기간 내 날짜별 재고의 최솟값이에요.
- `failures` 는 200 응답 안에 실패한 공급사와 사유만 담아요. 전부 실패해야 503 이에요.
- 4번은 `time_total` 이 3초 부근이어야 해요. 훨씬 짧으면 캐시로 응답한 것이니 2번을 다시 해요.
- 캐시가 차 있는 상태에서 A 를 장애로 두고 5분 기다리면 갱신 잡이 상태 키에 실패를 기록해 캐시 응답에도 `failures` 가 붙어요(`query-cache` 스킬 3번).

## 주의사항
- 3\~5번은 캐시가 비어 있어야 검색이 공급사를 직접 불러요. 순서를 지키거나 2번을 먼저 해요.
- 끝나면 6번으로 복구해요. 모드는 Mock 메모리에만 있어 Mock 재시작으로도 돌아와요.
