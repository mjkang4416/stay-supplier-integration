---
name: mock-fault
description: Mock Supplier 의 공급사별 모드(정상·장애·무응답)를 바꿔 연동 견고성(타임아웃·부분 실패·실패 판정 통일)을 재현한다
---

# Mock 장애 모드

Mock Supplier(9090)는 재고·요금 조회 API에만 모드가 걸린다. 숙소 목록 API는 항상 정상이다.

```bash
# 모드 전환: {a|b} × {normal|error|no-response}
curl -s -X POST 'http://localhost:9090/control/a/mode?value=error'
curl -s -X POST 'http://localhost:9090/control/b/mode?value=no-response'
curl -s -X POST 'http://localhost:9090/control/a/mode?value=normal'
```

| 모드 | Supplier A 가 주는 것 | Supplier B 가 주는 것 | 우리 판정 |
|---|---|---|---|
| `normal` | 200 + 예제 데이터 | 200 + `resultCode: "0000"` | 성공 |
| `error` | 503 + `{ "error": "SERVICE_UNAVAILABLE" }` | 200 + `resultCode: "E503"` (HTTP 는 200!) | 둘 다 `UNAVAILABLE` |
| `no-response` | 10분 동안 응답 없음 | 같음 | 응답 타임아웃(3초) → `TIMEOUT` |

확인 포인트
- B 는 장애여도 HTTP 200 이므로 본문 `resultCode` 를 읽어야 실패로 잡힌다 (`docs/architecture.md` 4.2).
- `error` 는 검색에서 즉시 1회 재시도 뒤 `failures` 에 들어가고, `no-response` 는 재시도 없이 3초 뒤 `failures` 에 들어간다.
- 크론잡(`sync` 프로필)은 목록 API 를 쓰므로 이 모드의 영향을 받지 않는다. 목록 실패를 보려면 Mock 을 내리고 크론잡을 돌린다 → `CONNECTION`, 종료 코드 1.

모드는 Mock 프로세스 메모리에만 있어 Mock 을 재시작하면 `normal` 로 돌아간다.
