# stay-supplier-integration

## 문제 요약
<!-- 본인 말로 3~5줄: 어떤 문제를 풀고, 무엇을 만들었는지 -->

## 빌드·실행

### 요구 사항
- JDK 21 (`mise install`로 설치할 수 있다. 버전은 `mise.toml`에 고정)
- Docker (로컬 MySQL 8.4 실행)

### 실행
```bash
docker compose up -d # MySQL 실행
./gradlew build      # 컴파일 + 테스트
./gradlew bootRun    # 애플리케이션 실행 (포트 8080)
```
- Mock Supplier 실행: <!-- Mock 구성 확정 후 기입 -->
- API 문서(Swagger UI): http://localhost:8080/swagger-ui.html

### 로컬 MySQL

| 항목 | 값 |
|---|---|
| 버전 | MySQL 8.4 (Docker, `compose.yaml`) |
| 설정 파일 | `docker/mysql/conf.d/my.cnf` |
| 포트 | 3306 (충돌 시 `MYSQL_PORT`로 변경, 애플리케이션은 `DB_PORT`로 맞춤) |
| DB / 계정 | `stay_supplier` / `stay`·`stay` (로컬 개발용) |
| 문자셋 | `utf8mb4` / `utf8mb4_unicode_ci` |
| 타임존 | UTC |
| 데이터 | `mysql-data` 볼륨에 유지, 초기화는 `docker compose down -v` |

### 동작 확인
<!-- 검색 API 호출 예시, Mock 장애 모드 전환 후 부분 실패 확인 방법 -->

## 구현 범위

| 항목 | 상태 | 문서 |
|---|---|---|
| 표준 숙박 상품 모델·매핑 저장 | 진행 전 | [stay-model.md](docs/stay-model.md) |
| Supplier 연동 어댑터 | 진행 전 | [supplier-integration.md](docs/supplier-integration.md) |
| 통합 검색 API | 진행 전 | [stay-search-api.md](docs/stay-search-api.md) |
| 연동 견고성 (타임아웃·부분 실패·실패 판정 통일) | 진행 전 | [supplier-integration.md](docs/supplier-integration.md) |
| Mock Supplier | 진행 전 | [mock-supplier.md](docs/mock-supplier.md) |
<!-- 선택 구현은 진행한 항목만 추가: 상태는 구현 / 설계만 / 미구현 -->

## 설계 의사결정 요약

| 결정 | 선택 | 근거 | 자세히 |
|---|---|---|---|
| 내부 표준으로 삼은 정보와 버린 정보 | | | [stay-model.md](docs/stay-model.md) |
| 요금 필드 구성 | | | [stay-model.md](docs/stay-model.md) |
| 매핑 생성 시점 | | | [stay-model.md](docs/stay-model.md) |
| 연박 예약 가능 객실 수 판정 | | | [stay-search-api.md](docs/stay-search-api.md) |
| 예약 불가 상품 노출 방식 | | | [stay-search-api.md](docs/stay-search-api.md) |
| 부분 실패 표현 방식 | | | [stay-search-api.md](docs/stay-search-api.md) |
| 대량 숙소 조회 처리 | | | [stay-search-api.md](docs/stay-search-api.md) |
| 타임아웃 값 | | | [supplier-integration.md](docs/supplier-integration.md) |
| 신규 Supplier 추가 시 수정 범위 | | | [supplier-integration.md](docs/supplier-integration.md) |
| Spring MVC + WebClient vs WebFlux | | | [architecture.md](docs/architecture.md) |

## 문서
- [docs/architecture.md](docs/architecture.md): 전체 구성과 기술 선택
- [docs/stay-model.md](docs/stay-model.md): 표준 모델과 매핑
- [docs/supplier-integration.md](docs/supplier-integration.md): 공급사 연동과 견고성
- [docs/stay-search-api.md](docs/stay-search-api.md): 통합 검색 API
- [docs/mock-supplier.md](docs/mock-supplier.md): Mock Supplier
- [JOURNAL.md](JOURNAL.md): 진행 기록과 AI 활용 기록
