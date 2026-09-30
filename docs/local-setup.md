# 로컬 개발과 실제 생산 환경 준비

처음에는 공개 소스의 기본 build와 자동 검사를 실행한다. 실제 Worker 생산·DB·인증·출고 환경은 별도 설정이 필요하다. 공개 checkout에는 과거 생산 DB, credential, 사용자 설정과 실행 제품 snapshot을 넣지 않는다.

## 기본 소스 검증

필수 도구는 Java 21, Node.js 20 이상과 npm이다. Java와 Node 실행 파일을 PATH에서 찾을 수 있어야 한다. Maven unit의 `CodexCodingWorkerContractTest`도 합성 Worker를 실제 Node 프로세스로 실행하므로 Node는 별도 runner 테스트뿐 아니라 기본 Maven 검사에도 필요하다. Maven은 저장소 wrapper가 선언한 버전을 사용한다. 최초 wrapper와 의존성 다운로드에는 네트워크가 필요하다.

```powershell
.\mvnw.cmd -B -ntp clean verify
Push-Location codex-worker-runner
npm ci --ignore-scripts
npm test
Pop-Location
```

소스 build와 일반 자동 검사는 실제 ChatGPT 로그인이나 API key 입력을 요구하는 생산 주문이 아니다. Node runner는 lockfile의 Codex SDK exact 버전을 사용한다. 현재 공개 snapshot의 새 실행 결과는 [검증 상태](validation.md)에 기록한다.

## Factory Host의 별도 준비

Factory Host는 Spring Boot 실행 host다. 빈 checkout에서 실행 파일만 시작하면 과거 생산 주문·부품·출고 제품이 복구되는 형태가 아니다. 실제 생산에는 다음을 준비한다.

| 준비 대상 | 역할 |
| --- | --- |
| 전용 PostgreSQL | domain 원장, ActionRun, durable intent와 migration |
| Factory artifact 저장소 | 정확한 입력·후보·검사·인증·출고 bytes 보관 |
| 전용 Worker state/workspace | 작업별 source와 transport 상태 격리 |
| 등록된 Worker/equipment binding | 사용할 실행 설비·정책·권한 선택 |
| 고정 검증 도구·Docker 환경 | Factory 소유의 독립 기술·업무·전체 제품 검사 |
| trusted 접수·Decision 진입부 | 요청자·tenant·정확한 주문·승인 권한 검증 |

DB 연결은 `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` 설정을 사용한다. 실제 값과 비밀은 저장소 밖의 접근이 제한된 설정으로 공급한다. 공개 예제 값이나 과거 문서의 상태를 유효한 인증·생산 권한으로 사용하지 않는다.

현재 Worker 설정에는 `factory.worker.codex.enabled`, `binding-id`, `node-executable`, `runner-entrypoint`, `state-root`, `workspace-base`, `workspace-ref`, `workspace-path`, `codex-home` 등의 항목이 있다. 실제 전용 경로와 identity는 사용자의 환경에 맞춰 준비한다. 세부 유효성 검증은 host의 `FactoryWorkerTransportConfiguration`과 관련 binding 계약이 소유한다.

여기에는 실제 작업을 수행하는 주문과 승인 예시를 내장하지 않는다. 필요한 입력·기한·budget·권한을 명시해 등록된 접수 Action을 사용하는 것이 생산 시작점이다. 사람 승인은 실제 검사 대상이 정해진 뒤 그 대상에 결박해 기록한다.

## 실제 Codex Coding Worker

이 프로젝트의 검증된 생산 경로는 Codex SDK와 전용 ChatGPT 인증을 사용한다. 공개 소스에 OpenAI API key를 추가하는 것을 기본 준비 과정으로 삼지 않는다.

실제 Worker를 실행하려면 사용자가 별도 전용 Codex profile의 로그인과 해당 OS의 샌드박스 초기 설정을 준비해야 한다. 인증 상태, control 읽기, 차단해야 할 credential/workspace 경로와 permission-profile 증거를 확인한 뒤 binding을 활성화한다. 기본 개인 profile을 복사해 인증을 대신하거나 실제 secret을 합성 검사 입력에 넣지 않는다.

기존 내부 실행에서 확인한 `ISOLATION_PROVEN` 상태는 사용자의 새 머신에 자동 적용되지 않는다. 해당 환경에서 실제 smoke를 수행해야 한다. 일부 setup은 OS 수준 권한 설정과 사용자 로그인이 필요하며 소스 build만으로 완료되지 않는다.

현재 실제 Worker 경로에는 Windows 전용 준비와 검증이 포함되어 있다. Java 소스의 다른 플랫폼 build 가능성을 실제 Worker 격리의 모든 OS 지원 주장으로 확대하지 않는다.

## 독립 검증 설비와 native 검사

추가 PostgreSQL 검사는 Docker/Testcontainers 환경을 필요로 한다. sandbox 검사는 Docker Linux engine, 고정 이미지와 exact toolchain/dependency closure를 준비해야 한다. 자동 pull이나 host 실행 fallback을 허용하지 않는 경로는 필요한 로컬 입력이 없으면 중단한다.

profile 명령과 역사적 입력 의존 범위는 [검증 문서](validation.md)를 따른다. 일반 build 성공과 native profile 성공을 구분해서 기록한다.

## 과거 데모를 바로 실행할 수 있는가

이 공개 checkout에는 과거 실제 출고 handoff와 BASIC/HISTORY 실행 사본을 포함하지 않는다. 과거 `run-demo.ps1`이나 historical snapshot을 읽는 standalone 도구의 실행 안내를 공개 소스의 즉시 실행 quick start로 해석하지 않는다.

`tools/incident-application-runtime`의 모듈 source는 생산라인의 catalog 입력이다. 완성 앱은 현재 유효한 인증 코어와 설정을 포함해 조립·검사·승인·출고한 별도 bundle이다. 표준 모듈 source만으로 과거 출고 제품의 인증 권한과 provenance를 재생성할 수 없다.

새 실제 데모가 필요하면 전용 환경에서 새 부품·주문을 정식 생산하고, 독립 검사와 정확한 승인 후 새 인계물을 만든다. Factory 책임은 이 인계까지이며 제품 배포·상시 운영은 별도의 조직 책임이다.
