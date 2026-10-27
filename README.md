# Yomitori

기기 내 OCR과 한국어 식자를 지원하는 Android 만화 리더입니다. [Yakuyomi](https://github.com/joyeli/Yakuyomi)와 [Mihon](https://github.com/mihonapp/mihon)을 기반으로 합니다.

[APK 다운로드](https://github.com/hvboq/Yomitori/releases) · [빌드 안내](docs/BUILDING.md) · [번역 엔진](https://github.com/hvboq/yomitori-engine)

## 주요 기능

- 내장 Hitomi 탐색·검색·이미지 로딩과 전체 태그 자동완성. `artist`, `group`, `series`, `character`, `tag`, `female`, `male`, `language`, `type` 분류를 지원합니다.
- 리더에서 선택한 페이지의 자동·수동 번역. 온라인 페이지 한 장을 처리할 때 전체 챕터를 다운로드하지 않습니다. 전체 챕터 다운로드·번역은 별도 동작입니다.
- 기기 내 텍스트 검출·OCR·원문 제거·식자. 기본 언어는 일본어 → 한국어이며 번역 언어를 설정할 수 있습니다.
- 사용자 지정 OpenAI 호환 번역 서버. 페이지 처리 시도마다 번역할 OCR 텍스트와 영역 ID를 한 논리 요청으로 보내며, 실제 페이지 이미지는 번역 서버에 보내지 않습니다. 네트워크 재시도가 발생할 수 있습니다.
- 원문 비교, 처리 기록, 중지·재시도와 부분 재개. 호환되는 저장 기록에서 재개할 때 성공한 영역은 다시 번역하지 않습니다.
- 로컬 이미지 폴더·압축 파일 읽기, 라이브러리, 다운로드·번역 대기열과 야간 읽기.

## 시작하기

1. [Releases](https://github.com/hvboq/Yomitori/releases)에서 ARM64 APK를 설치합니다. Android 8.0(API 26) 이상이 필요하며, 기기 내 추론은 ARM64를 대상으로 합니다.
2. 번역 설정에서 필요한 OCR·이미지 처리 모델을 다운로드하거나 준비한 모델 폴더를 선택합니다. 모델 가중치는 APK에 포함되지 않습니다.
3. 사용할 번역 서버의 API base URL, 모델, 필요한 경우 API 키를 입력합니다. 개인 서버 주소·키·모델은 기본값으로 제공하지 않습니다.
4. 소스를 탐색하거나 로컬 작품을 열고, 리더의 **이 페이지 번역**을 사용합니다. 자동 번역은 번역 설정에서 켤 수 있습니다.

Hitomi가 소스 목록에 없다면 소스 언어와 성인 소스 표시 설정을 확인하세요. 자동완성·이미지 제공 여부는 원격 소스의 상태에 영향을 받습니다.

공개 빌드는 Bangumi·Hikka·Kitsu·Shikimori의 앱 자격증명을 포함하지 않아 해당 트래커가 비활성화됩니다. 일반 읽기·기기 내 OCR·사용자가 설정한 LLM 서버 번역은 사용할 수 있습니다. 자체 빌드의 선택형 트래커 설정은 [빌드 안내](docs/BUILDING.md#optional-tracker-credentials)를 참고하세요.

## 데이터와 처리 범위

검출·OCR·원문 제거·식자는 기기에서 실행합니다. 번역 서버에는 인식된 원문과 번역 지시가 전송되므로, 사용할 서버의 데이터 처리 방침을 확인하세요. 소스 탐색·이미지 수신과 모델 다운로드에도 네트워크를 사용합니다.

번역 또는 재식자 전에 원본을 보존하며, 실패·취소·미해결 영역이 있는 결과를 완전한 성공으로 기록하지 않습니다. 온라인 페이지 캐시는 영구 다운로드가 아니며, 캐시 정책에 따라 원문과 결과가 함께 회수될 수 있습니다. 압축 내부 페이지는 개별 파일 URI가 없어 폴더와 같은 단일 페이지 동작을 모두 지원하지는 않습니다.

OCR은 일부 글자를 놓치거나 잘못 읽을 수 있고, 번역 품질과 지연 시간은 입력·기기·모델·서버에 따라 달라집니다. 모든 작품·저장소·장시간 사용 조합이 검증된 것은 아닙니다.

## 개발과 출처

현재 소스 버전은 `0.23.0-yomitori.51`(`versionCode 117`)입니다. 하위 모듈을 포함해 복제해야 합니다.

```sh
git clone --recurse-submodules https://github.com/hvboq/Yomitori.git
cd Yomitori
```

[빌드 안내](docs/BUILDING.md)에 환경 준비, 검사, 자체 서명 APK 생성 방법이 있습니다. 엔진은 `yakuyomi-engine/` 경로의 [별도 저장소](https://github.com/hvboq/yomitori-engine)이며, 내부의 [yakuyomi-nightread](https://github.com/joyeli/yakuyomi-nightread)도 하위 모듈로 유지합니다.

이 프로젝트는 Mihon의 리더, Yakuyomi의 통합 기능과 엔진, [manga-image-translator](https://github.com/zyddnys/manga-image-translator)의 번역·모델 기반 작업, [NCNN](https://github.com/Tencent/ncnn)을 바탕으로 합니다. 내장 Hitomi 구현에는 Aidoku 커뮤니티 소스에서 유래한 코드가 포함되어 있습니다.

기존 라이선스와 저작권 고지를 유지합니다. Mihon 기반 코드의 [Apache-2.0 라이선스](LICENSE), 결합 앱의 [GPL-3.0 관련 고지](LICENSE-YAKUYOMI.md), [엔진 라이선스](yakuyomi-engine/LICENSE), [야간 읽기 라이선스](yakuyomi-engine/yakuyomi-nightread/LICENSE), [Hitomi MIT 고지](app/src/main/java/mihon/source/hitomi/LICENSE)를 확인하세요. 모델 가중치는 소스 코드와 별도의 조건이 적용될 수 있으며, 출처와 조건은 [모델 안내](yakuyomi-engine/docs/MODELS.md)에 있습니다.

## English

Yomitori is an Android manga reader based on Yakuyomi and Mihon, with on-device OCR, text removal and typesetting. It defaults to Japanese-to-Korean translation and includes a built-in Hitomi source with autocomplete across all nine supported tag namespaces.

Use **Translate this page** in the reader, or enable translation while reading. Processing a selected online page does not download the whole chapter; chapter download and translation remain separate actions. Configure your own OpenAI-compatible endpoint, model and optional API key in translation settings. Each page attempt sends the OCR text and region IDs as one logical translation request; page images are not sent to the translation server. Network retries are separate, and compatible partial resumes do not request already successful regions again.

Originals are preserved before replacement, with original comparison, processing records, cancellation and retry support. Online cached results are subject to cache eviction. OCR and translation quality vary, and processing speed depends on the device, models and server.

Download an ARM64 APK from [Releases](https://github.com/hvboq/Yomitori/releases). Android 8.0+ is required. Models are downloaded separately. See [BUILDING](docs/BUILDING.md) to build from source; clone recursively to include both engine and night-reading submodules. Upstream licenses and attribution remain in the files linked above.

Public builds leave application credentials for Bangumi, Hikka, Kitsu and Shikimori blank, so those optional trackers are disabled. Reading, on-device OCR and translation through your own configured LLM endpoint remain supported. The build guide explains how to configure your own tracker application credentials.
