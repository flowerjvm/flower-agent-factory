# Flower Agent Factory

이 프로젝트는 **도메인별 전문 생산라인을 갖춘 소프트웨어 Factory를 구축하는 기반**입니다.
주문 접수, 생성·조립, 독립 검사, 정확한 사람 승인, 부품 인증과 완제품 출고를 지속 원장과
생산 증거로 연결합니다. Factory의 책임은 출고·인계까지입니다.

실제 구현한 라인은 `agent-pack`, `reference-assembly`, `incident-application`입니다.
Coding Worker가 유지보수 조사 코어를 생성·수정했고, Factory가 같은 인증 코어를 수정 없이
소비하여 참조 조립물과 BASIC/HISTORY 실행 앱을 생산했습니다. 앱 두 주문은 표준 모듈을
선택하는 결정적 조립이며, 새 Worker가 웹앱 전체를 다시 코딩한 공정은 아닙니다.

## 먼저 읽을 문서

1. [전체 구조](docs/architecture.md): 공통 기반과 전문 라인, Flower와 Action Runtime의 역할
2. [생산물의 종류](docs/product-lines.md): 인증 부품, 참조 조립물, 실행 앱의 차이
3. [생산부터 출고까지](docs/production-process.md): Worker 수정 공정과 완제품 조립·검사·승인
4. [TOS 등으로 확장](docs/extending-product-lines.md): 재사용할 공통 설비와 새로 구현할 도메인 규격
5. [실행 환경 준비](docs/local-setup.md): 기본 검사와 실제 생산 환경의 준비사항
6. [검증 기록](docs/validation.md): 공개 사본 검사와 이전 로컬 생산 검증의 구분

## 소스 검증

Java 21과 Node.js 20 이상을 PATH에 준비합니다. 기본 Java 검사 일부가 fake Node Worker를
실행하므로 Node도 필요합니다.

```powershell
git clone https://github.com/flowerjvm/flower-agent-factory.git
cd flower-agent-factory
.\mvnw.cmd -B -ntp verify
```

macOS/Linux에서는 `./mvnw -B -ntp verify`를 사용합니다. 실제 Worker 생산에는 별도의
Codex 인증·격리 설정이 필요하지만 위 기본 검사는 로그인이나 API 키를 사용하지 않습니다.

공개 저장소에는 소스·검사 코드·모듈 Catalog·도구를 담았습니다. 개인 설정, 생산 DB,
사용자 응답, 원시 실행 증거, 실제 출고 ZIP과 소비자 데이터는 로컬에 보존했습니다.
따라서 클론만으로 `run-demo.ps1`을 실행하거나 과거 출고 앱을 켤 수 있는 구성은 아닙니다.
역사적 소비자 도구는 별도의 출고 snapshot이 필요합니다.

현재 구현은 전문 생산라인의 기반과 한정된 실제 데모를 검증한 단계입니다. 임의의 TOS를
자동 생성하는 범용 제품 생성기, 완성된 Agent Runtime, 배포·상시 운영 플랫폼은 후속 범위입니다.

라이선스는 [Apache-2.0](LICENSE)입니다. 상세 소개는 [영문 README](README.md)를 참고하세요.
