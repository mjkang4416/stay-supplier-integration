---
name: test-search-api
description: 통합 검색 API를 정상·부분 실패·타임아웃·전부 실패·매진 포함 순서로 curl 로 확인한다
---

# 검색 API 확인

전제: `run-local` 스킬로 MySQL·Mock·애플리케이션이 떠 있고 매핑이 들어가 있다. 포트를 바꿨으면 아래 8080을 맞춘다.

캐시 창은 오늘부터 30일이라 그 안의 날짜를 쓴다(아래 2026-09-20~23 은 예시). Mock 은 어떤 날짜든 예제 패턴으로 응답한다.

```bash
Q='http://localhost:8080/api/v1/stays/search?checkIn=2026-09-20&checkOut=2026-09-23&adults=2&children=0'

# 1. 캐시 적중: "fresh": false, 수 ms. Riverside A(429,000)·Riverside B(452,000, 조식 포함). Namsan 은 하루 재고 0 이라 제외
curl -s "$Q" | python3 -m json.tool

# 1-1. 캐시 비우기 → 저하 모드: "fresh": true, 공급사 직접 호출, 같은 값
docker compose exec redis redis-cli FLUSHALL
curl -s "$Q" | python3 -m json.tool

# 2. Supplier A 장애 (캐시가 비어 있어야 검색이 직접 호출한다) → 200 + failures: [{ "supplier": "A", "reason": "UNAVAILABLE" }], B 결과만
curl -s -X POST 'http://localhost:9090/control/a/mode?value=error'
curl -s "$Q" | python3 -m json.tool

# 3. Supplier A 무응답 → 약 3초 뒤 200 + failures: [{ "supplier": "A", "reason": "TIMEOUT" }]
curl -s -X POST 'http://localhost:9090/control/a/mode?value=no-response'
curl -s -w '\n%{time_total}s\n' "$Q"

# 4. B 도 장애 → 503 + Retry-After: 5
curl -s -X POST 'http://localhost:9090/control/b/mode?value=error'
curl -s -i "$Q"

# 5. 복구
curl -s -X POST 'http://localhost:9090/control/a/mode?value=normal'
curl -s -X POST 'http://localhost:9090/control/b/mode?value=normal'

# 6. 예약 불가 포함 → Namsan 이 availableRooms 0 으로 나온다
curl -s "$Q&includeSoldOut=true" | python3 -m json.tool

# 7. 잘못된 요청 → 400 INVALID_REQUEST
curl -s 'http://localhost:8080/api/v1/stays/search?checkIn=2026-09-04&checkOut=2026-09-01&adults=2'

# 8. 캐시가 있어도 직접 호출 (예약 직전 재확인)
curl -s "$Q&fresh=true" | python3 -m json.tool
```

캐시가 차 있는 상태에서 A 를 장애로 두고 5분 기다리면 갱신 잡이 상태 키에 실패를 기록해 캐시 응답에도 failures 가 붙는다.

기대값의 근거는 `docs/stay-search-api.md` 1.5 예시와 2장 조회 규칙.
