---
name: test-search-api
description: 통합 검색 API를 정상·부분 실패·타임아웃·전부 실패·매진 포함 순서로 curl 로 확인한다
---

# 검색 API 확인

전제: `run-local` 스킬로 MySQL·Mock·애플리케이션이 떠 있고 매핑이 들어가 있다. 포트를 바꿨으면 아래 8080을 맞춘다.

```bash
Q='http://localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'

# 1. 정상: Riverside A(429,000)·Riverside B(452,000, 조식 포함) 두 건. Namsan 은 9/2 재고 0 이라 제외
curl -s "$Q" | python3 -m json.tool

# 2. Supplier A 장애 → 200 + failures: [{ "supplier": "A", "reason": "UNAVAILABLE" }], B 결과만
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
```

기대값의 근거는 `docs/stay-search-api.md` 1.5 예시와 2장 조회 규칙.
