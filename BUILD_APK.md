# APK 만드는 방법 (3가지)

Claude가 작업한 환경에서는 Android SDK 설치와 의존성 다운로드가 차단되어 있어
APK를 직접 뽑아드릴 수 없었습니다. 대신 아래 세 가지 중 편한 걸 고르시면 됩니다.

---

## 방법 1. GitHub에 올리면 자동으로 APK가 나옵니다 (PC 설치 불필요, 추천)

Android Studio를 안 깔아도 되고, 폰만 있어도 됩니다.
이미 `.github/workflows/build-apk.yml` 을 넣어뒀습니다.

1. GitHub에서 새 저장소를 만듭니다 (Private 도 됩니다).
2. 이 폴더의 파일들을 그 저장소에 올립니다.
   - 웹에서 올릴 경우: 저장소 페이지 → **Add file** → **Upload files** → 폴더째 드래그
   - `.github` 폴더가 숨김 처리되어 빠지지 않도록 주의하세요.
3. 저장소의 **Actions** 탭으로 갑니다.
4. 왼쪽에서 **Build APK** 선택 → 오른쪽 **Run workflow** 버튼 클릭.
5. 3~5분 뒤 초록 체크가 뜨면, 그 실행 기록을 눌러 맨 아래
   **Artifacts** 의 `FoldReveal-debug-apk` 를 다운로드합니다.
6. 압축을 풀면 `app-debug.apk` 가 들어있습니다. 폴드8에 옮겨 설치하세요.

> 설치 시 "출처를 알 수 없는 앱" 허용이 필요합니다.
> 설정 → 보안 및 개인 정보 보호 → 기타 보안 설정 → 알 수 없는 앱 설치

---

## 방법 2. Android Studio (PC가 있고, 코드를 만져볼 생각이면 이쪽)

1. [Android Studio](https://developer.android.com/studio) 설치
2. **Open** 으로 이 폴더 선택 → Gradle Sync 자동 진행 (첫 실행은 5~15분)
3. 상단 메뉴 **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
4. 완료 알림의 **locate** 클릭 → `app/build/outputs/apk/debug/app-debug.apk`

폴드8을 USB로 연결하고 `Run ▶` 을 누르면 빌드와 설치가 한 번에 됩니다.
코드를 고치면서 바로바로 확인하려면 이 방법이 제일 편합니다.

---

## 방법 3. 명령줄 (Gradle/SDK가 이미 있는 경우)

```bash
# 프로젝트 폴더에서
gradle assembleDebug

# 결과물
# app/build/outputs/apk/debug/app-debug.apk

# 폰이 연결돼 있다면 바로 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 설치 후 첫 실행

APK를 깔았다고 바로 효과가 나오지는 않습니다. 권한 두 개를 켜야 합니다.

1. 앱 실행 → **"1. 오버레이 권한 요청"** → 설정에서 "다른 앱 위에 표시" 허용
2. 뒤로 돌아와 **"2. 화면 캡처 권한 요청 + 전체 앱 효과 시작"** → 화면 기록 동의
3. 이제 아무 앱이나 켜놓고 기기를 접었다 펼쳐보세요.

효과를 끄고 싶으면 앱의 **"전체 앱 효과 중지"** 를 누르거나,
알림창에서 화면 기록을 중지하면 됩니다.

---

## 참고: 이건 디버그 빌드입니다

- 서명이 디버그 키로 되어 있어 Play 스토어 배포는 불가능합니다. 개인 설치용입니다.
- 릴리스 빌드(`assembleRelease`)를 하려면 별도 서명 키가 필요합니다.
  개인적으로 쓰실 거라면 디버그 APK로 충분합니다.
