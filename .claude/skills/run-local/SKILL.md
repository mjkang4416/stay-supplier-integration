---
name: run-local
description: |
  로컬에서 MySQL·Redis(Docker), Mock Supplier, 애플리케이션을 순서대로 띄운다. 처음이거나 DB 를 초기화했으면 매핑 갱신 잡을 먼저 한 번 돌린다.
  Triggers: "로컬 띄워줘", "앱 실행해", "서버 올려", "처음 실행", "run local", "bootRun"
  Do NOT use for: 검색 응답 확인(test-search-api), 장애 재현(mock-fault), 자동화 테스트 실행(`./gradlew test` 는 CLAUDE.md 명령)
allowed-tools: Bash(docker compose*), Bash(./gradlew*), Bash(curl*)
---

# 로컬 실행

## 전제
- JDK 21 과 Docker 가 있다. 명령은 모두 저장소 루트에서 실행한다.
- 터미널 세 개를 쓴다. 1·3번은 한 터미널에서 순서대로, 2번과 4번은 각각 별도 터미널이다.

## 실행 순서
1. MySQL + Redis
   ```bash
   docker compose up -d
   docker compose ps    # stay-supplier-mysql, stay-supplier-redis 가 healthy 인지 확인
   ```
2. Mock Supplier (별도 터미널, 포트 9090)
   ```bash
   ./gradlew :mock-supplier:bootRun
   curl -s http://localhost:9090/a/v1/hotels | head -c 200
   ```
3. 매핑 갱신 잡 1회 (처음 띄울 때, 또는 DB 를 초기화했을 때)
   ```bash
   ./gradlew :bootRun --args='--spring.profiles.active=sync'
   ```
   웹 서버 없이 뜨고 공급사 숙소 목록을 DB 에 넣은 뒤 종료한다.
4. 애플리케이션 (별도 터미널, 포트 8080)
   ```bash
   ./gradlew :bootRun
   ```

## 확인 포인트
- 3번 종료 코드 0 이면 전부 성공, 1 이면 공급사 하나 이상 실패다. 들어간 매핑은 `query-mapping` 스킬로 본다.
- 4번 기동 직후 로그 `cache refresh supplier=A hotels=2 …` 가 찍히고 약 10초 뒤 오늘~+30일 요금·재고가 Redis 에 찬다. `query-cache` 스킬로 본다.
- Swagger UI: http://localhost:8080/swagger-ui.html. 검색 확인은 `test-search-api` 스킬.

## 주의사항
- `bootRun` 앞의 `:` 를 빼면 Mock 모듈의 bootRun 까지 같이 실행된다.
- 포트 충돌: MySQL 은 `MYSQL_PORT=3307 docker compose up -d` 와 앱 `DB_PORT=3307`, Redis 는 `REDIS_PORT=6380`(앱도 같은 변수를 읽음), 앱은 `./gradlew :bootRun --args='--server.port=8081'`.
- Redis 가 없어도 앱은 뜨고 검색은 공급사를 직접 부르는 저하 모드로 동작한다.
