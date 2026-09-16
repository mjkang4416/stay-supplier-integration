---
name: query-mapping
description: 로컬 MySQL의 매핑 테이블(hotel_mapping, room_type_mapping)을 조회해 크론잡 결과와 식별자 안정성을 확인한다
---

# 매핑 테이블 조회

MySQL은 Docker 컨테이너(`stay-supplier-mysql`)에서 돈다. 계정은 로컬 개발용 `stay`/`stay`.

```bash
# 숙소 매핑 (공급사 코드 ↔ 내부 식별자)
docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
  "SELECT id, supplier, supplier_hotel_code, hotel_name, active, updated_at FROM hotel_mapping ORDER BY id;"

# 객실 타입 매핑 (숙소별. max_occupancy 가 NULL 이면 공급사가 값을 주지 않은 것)
docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
  "SELECT id, hotel_id, supplier_room_type_code, room_type_name, max_occupancy, active FROM room_type_mapping ORDER BY hotel_id, id;"

# 비활성(공급사 목록에서 사라진) 행만
docker compose exec -T mysql mysql -ustay -pstay stay_supplier -e \
  "SELECT id, supplier, supplier_hotel_code, updated_at FROM hotel_mapping WHERE active = FALSE;"
```

읽는 법
- 같은 공급사 코드는 크론잡을 다시 돌려도 `id`가 바뀌지 않아야 한다. 바뀌면 upsert 규칙이 깨진 것이다.
- 사라진 숙소는 삭제되지 않고 `active = FALSE`로 남는다. 다시 나타나면 같은 `id`로 `active = TRUE`가 된다.
- 예제 데이터라면 A-10023(1), A-10044(2), B77120(3)과 객실 타입 세 개가 있어야 한다.

DB 초기화가 필요하면 `docker compose down -v` 뒤 `run-local` 스킬의 3번(매핑 갱신 잡)을 다시 돌린다.
