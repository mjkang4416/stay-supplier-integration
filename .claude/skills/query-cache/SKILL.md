---
name: query-cache
description: |
  로컬 Redis 의 요금·재고 캐시(stay:v1:{hotelId} Hash)와 공급사별 갱신 상태 키를 조회해 갱신 잡이 채운 값, TTL, 실패 기록을 확인해요.
  Triggers: "캐시 확인", "Redis 에 뭐 있어", "캐시 값 보여줘", "TTL 확인", "갱신 잡 돌았어?", "왜 fresh=true 야"
  Do NOT use for: MySQL 매핑 조회(query-mapping), 검색 응답 확인(test-search-api), 캐시 채우기(갱신 잡이 5분마다 자동으로 한다)
argument-hint: "hotelId (생략하면 키 목록과 상태 키)"
allowed-tools: Bash(docker compose exec*)
---

# 요금·재고 캐시 조회

## 전제
- Redis 가 Docker 컨테이너 `stay-supplier-redis` 에서 떠 있고, 애플리케이션이 기동해 갱신 잡 첫 갱신(약 10초)가 끝났어요.
- 키·값 형식은 `docs/architecture.md` 4.8. 숙소당 Hash 하나: 키 `stay:v1:{hotelId}`, 필드 `{roomTypeId}:{yyyyMMdd}`, 값 `재고|세금 포함 1박|통화|조식(0/1)|최대 인원`. `hotelId`·`roomTypeId` 는 `query-mapping` 의 `id` 다.

## 실행 순서
1. 키 목록 (숙소별 Hash + 공급사별 상태 키)
   ```bash
   docker compose exec redis redis-cli KEYS 'stay:v1:*'
   ```
2. 숙소 하나의 값
   ```bash
   docker compose exec redis redis-cli HGETALL stay:v1:1
   docker compose exec redis redis-cli HLEN stay:v1:1        # 필드 수 = 객실 타입 수 × 30일 + 1(_refreshedAt)
   docker compose exec redis redis-cli TTL stay:v1:1         # 남은 초
   ```
3. 공급사별 마지막 갱신 결과
   ```bash
   docker compose exec redis redis-cli HGETALL stay:v1:status:A
   docker compose exec redis redis-cli HGETALL stay:v1:status:B
   ```
4. 캐시 비우기 (TTL 만료·Redis 초기화와 같은 상태를 만들어 저하 모드를 볼 때)
   ```bash
   docker compose exec redis redis-cli FLUSHALL
   ```

## 확인 포인트
- 값 예: `3|132000|KRW|0|2` 는 재고 3, 세금 포함 1박 132,000원, KRW, 조식 없음, 최대 2인이에요. 마지막 칸이 비어 있으면 최대 인원 미상이에요. 재고 0 도 저장돼요(연박 판정에 필요).
- `_refreshedAt` 은 그 숙소를 마지막으로 채운 시각(UTC)이에요. TTL 은 갱신 주기 × 3 = 900초에서 시작해 줄어들다가 다음 갱신에서 다시 900 이 돼요. 0 에 가까워지면 갱신 잡이 멈춘 것이에요.
- 상태 키는 `lastSuccessAt`, 또는 `lastFailureAt` + `lastFailureReason`(`FailureReason` 이름)을 가져요. 실패가 기록되면 검색 응답의 `failures` 에도 그 공급사가 붙어요.
- 키가 없으면 검색은 공급사를 직접 부르고 `"fresh": true` 로 응답해요(저하 모드). 첫 갱신 전, 범위(오늘\~+30일) 밖 날짜, FLUSHALL 직후가 여기에 해당해요.

## 주의사항
- `KEYS` 는 로컬 확인용이에요. 운영 Redis 에서는 `SCAN` 을 써요.
- B 숙소는 날짜마다 1박 호출로 채우므로(기간 총액만 주는 공급사) 첫 갱신가 A 보다 오래 걸려요.
- 값을 직접 고쳐 넣지 않아요. 캐시를 쓰는 곳은 갱신 잡뿐이고 검색은 읽기만 해요(CLAUDE.md 코드 규칙).
