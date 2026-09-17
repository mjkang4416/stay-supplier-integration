---
name: query-mapping
description: |
  로컬 MySQL 의 매핑 테이블(hotel_mapping, room_type_mapping)을 조회해 크론잡 결과와 내부 식별자 안정성을 확인해요.
  Triggers: "매핑 테이블 보여줘", "DB 에 뭐 들어갔어", "식별자 확인", "hotel_mapping 조회", "비활성 숙소 확인"
  Do NOT use for: Redis 요금·재고 캐시 조회(query-cache), 매핑 갱신 실행(run-local 3번), 스키마 변경
argument-hint: "hotel | room | inactive (생략하면 셋 다)"
allowed-tools: Bash(docker compose exec*)
---

# 매핑 테이블 조회

## 전제
- MySQL 이 Docker 컨테이너 `stay-supplier-mysql` 에서 떠 있고(`docker compose ps`), 매핑 갱신 잡을 한 번 이상 돌렸어요.
- 계정은 로컬 개발용 `stay`/`stay`, DB 는 `stay_supplier`.

## 실행 순서
1. 숙소 매핑 (공급사 코드 ↔ 내부 식별자)
   ```bash
   docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
     "SELECT id, supplier, supplier_hotel_code, hotel_name, active, updated_at FROM hotel_mapping ORDER BY id;"
   ```
2. 객실 타입 매핑 (숙소별)
   ```bash
   docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
     "SELECT id, hotel_id, supplier_room_type_code, room_type_name, max_occupancy, active FROM room_type_mapping ORDER BY hotel_id, id;"
   ```
3. 비활성(공급사 목록에서 사라진) 행만
   ```bash
   docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
     "SELECT id, supplier, supplier_hotel_code, updated_at FROM hotel_mapping WHERE active = FALSE;"
   ```

## 확인 포인트
- 같은 공급사 코드는 크론잡을 다시 돌려도 `id` 가 바뀌지 않아야 해요. 바뀌면 upsert 규칙이 깨진 것이에요.
- 사라진 숙소는 삭제되지 않고 `active = FALSE` 로 남아요. 다시 나타나면 같은 `id` 로 `active = TRUE` 가 돼요.
- `max_occupancy` 가 NULL 이면 공급사가 값을 주지 않은 것이에요(미상).
- Mock 예제 데이터라면 A-10023(1), A-10044(2), B77120(3)과 객실 타입 세 개가 있어야 해요.

## 주의사항
- DB 초기화가 필요하면 `docker compose down -v` 뒤 `run-local` 스킬 3번(매핑 갱신 잡)을 다시 돌려요. 초기화하면 `id` 가 다시 배정돼요.
- 매핑 규칙의 근거는 `docs/stay-model.md` 3장.
