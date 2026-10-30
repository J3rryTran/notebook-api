# Plan: Triển khai OIDC phía server (BFF) cho notebook — đối chiếu và kiểm chứng

Ngày: 2026-10-30. Dành cho AI agent thực hiện. Liên quan: `plan/Notebook OIDC.md` (phân tích hiện trạng trên app 0.0.15).

## 0. Kết luận sau khi đối chiếu source

Các vấn đề trong `plan/Notebook OIDC.md` đều quan sát trên **image 0.0.15**, build trước commit `1538809 addition oidc`.
Source hiện tại (`main` = `origin/main` = `1538809`) **đã cài đặt đủ** flow phía server:

| Vấn đề trên 0.0.15 | Trạng thái trong source `1538809` | Bằng chứng |
|---|---|---|
| Frontend dùng oidc-client-ts, cần `crypto.subtle` (không có trên origin http) | Đã bỏ thư viện. Trình duyệt chỉ điều hướng `window.location.assign('/api/v1/auth/oidc/login?redirect=...')` | `frontend/public/app/api.js` (`redirectToOidcLogin`); `frontend/public/vendor/oidc-client-ts.min.js` đã xoá |
| Trình duyệt `fetch()` discovery sang Keycloak (cert tự ký) | Không còn `fetch` sang Keycloak; server không dùng discovery, chỉ dùng các endpoint cấu hình | `OidcLoginService.buildAuthorizationRedirect`, `OidcTokenClient` |
| Cờ `sessionStorage.oidc_redirect_attempt` kẹt | Đã xoá cờ; chống lặp bằng biến `oidcRedirecting` trong trang; lỗi callback trả `/?authError=<code>` và hiện modal có nút "Đăng nhập lại" | `api.js` (`checkAuthStatus`, `showOidcLoginModal`) |
| Modal mode oidc hiện ô username/password | Ẩn `#loginForm` và nút đăng nhập mật khẩu ở mode oidc | `index.html` (`#loginForm`, `#oidcRetryBtn`), `api.js` |
| Đọc claim trước khi verify chữ ký (`Missing preferred_username claim`) | `BearerDelegationAuthenticator` đã xoá; `JwtTokenVerifier` verify chữ ký (JWKS), `iss`, `aud`, `exp`, `iat`, thuật toán **trước** khi đọc claim | `nb-common/.../auth/JwtTokenVerifier.java`, test `JwtTokenVerifierTest` |
| Backend lấy JWKS từ issuer https tự ký | Lấy từ `jwt.jwksUrl` (in-cluster http) | `JwtTokenVerifier(AuthProperties.Jwt, ...)` |
| `/api/v1/auth/config` trả `issuer/clientId/redirectPath/scopes` | Trả `{"mode":"oidc","verified":...,"loginUrl":"/api/v1/auth/oidc/login"}` | `AuthConfigController` |

Đã có test: `OidcLoginFlowTest` (PKCE/state/nonce, đổi code, state dùng 1 lần, nonce sai, aud sai, refresh, invalid_grant, logout), `OidcSecurityTest` (redirect, cookie `SameSite=Lax`, Bearer, CSRF), `JwtTokenVerifierTest`, `AuthPropertiesValidatorTest`. `./scripts/build.sh` xanh (114 test).

**Việc còn lại là triển khai đúng image + cấu hình, không phải viết lại code.** Mục 2 liệt kê các chỗ cấu hình đang lệch có thể làm hỏng triển khai.

## 1. Build và triển khai image mới

```bash
git pull
./scripts/build.sh
./scripts/package-image.sh 10.60.129.132:8890/j3rrytran/notebook:<tag-mới>
docker push 10.60.129.132:8890/j3rrytran/notebook:<tag-mới>
```
Đặt `image.tag` = tag mới. Flyway sẽ chạy **V7** (`notebook_oidc_pending_logins`, nới `credential_enc` lên `VARBINARY(32768)`).

## 2. Đối chiếu chart đang deploy (`notebook-helm/experiment`) với chart trong repo (`notebook-helm/`)

Chart `notebook-helm/experiment` **không có trong repo**. Agent phải so từng điểm sau; khuyến nghị deploy bằng chart `notebook-helm/` của repo (đã có template, validate, test cho các khoá dưới).

### 2.1 Khoá ConfigMap `application.yaml` (đã khớp, giữ nguyên)
`notebook.auth.oidc.jwt.{issuer,audience,jwks-url,username-claim,allowed-algorithms}`, `ui.{client-id,scope}`,
`authorization-endpoint`, `token-endpoint`, `end-session-endpoint`, `redirect-uri`, `session-timeout`, `service-client-id`.
4 khoá cũ (`issuer`, `client-id`, `redirect-path`, `scopes` trực tiếp dưới `oidc`) bị bỏ qua — **xoá** khỏi values để tránh nhầm.

### 2.2 Biến môi trường secret — **có điểm lệch**

| Giá trị | Chart repo (`deployment.yaml`) | `experiment` (theo plan cũ) | Kết quả với code hiện tại |
|---|---|---|---|
| UI client secret | `NOTEBOOK_OIDC_UI_CLIENT_SECRET` | `NOTEBOOK_AUTH_OIDC_UI_CLIENTSECRET` | Cả hai đều bind vào `notebook.auth.oidc.ui.client-secret` (relaxed binding). OK |
| Service account secret | `NOTEBOOK_OIDC_CLIENT_SECRET` | `NOTEBOOK_AUTH_OIDC_SERVICEACCOUNT_CLIENTSECRET` | **Không bind**: code đọc `notebook.auth.oidc.service-client-secret`, không có `serviceAccount.clientSecret`. Chỉ ảnh hưởng khi `serviceAccount.enabled=true`. Đổi env sang `NOTEBOOK_OIDC_CLIENT_SECRET` |
| Khoá mã hoá token | `NOTEBOOK_AUTH_ENCRYPTION_KEY` (cho cả `oidc`) | cần kiểm | **Bắt buộc** ở mode oidc. Thiếu → pod không lên (`notebook.encryptionKey must be 32 bytes base64`) |
| DB password | `SPRING_DATASOURCE_PASSWORD` | — | giữ |

### 2.3 Secret `notebook-secrets`
- `auth-encryption-key`: `openssl rand -base64 32`, giữ cố định.
- `oidc-client-secret`: secret của client `kyuubi-notebook` (hoặc đặt `auth.oidc.ui.clientSecret` trong values — chart repo tự tạo Secret `<release>-oidc`).
- `db-password`.

## 3. Keycloak (realm `lakehouse`)

| # | Việc | Kiểm |
|---|---|---|
| K1 | Client `kyuubi-notebook`: **Client authentication = On** (confidential), Standard flow On, PKCE method S256 | Tab Credentials có secret |
| K2 | Valid redirect URIs: `http://10.29.19.56:32380/api/v1/auth/callback` | — |
| K3 | Valid post logout redirect URIs: `http://10.29.19.56:32380/` | — |
| K4 | Audience mapper thêm `kyuubi` vào access token | Decode access token: `aud` chứa `kyuubi` |
| K5 | **`iss` cố định theo URL ngoài.** Notebook đổi code qua `http://keycloak-service...:8080`, nhưng kiểm `iss = https://10.29.19.56:32004/realms/lakehouse`. Keycloak phải đặt hostname cố định (`KC_HOSTNAME`/`hostname` = `https://10.29.19.56:32004`), nếu không `iss` sẽ là URL nội bộ và mọi đăng nhập bị từ chối | Từ một pod trong cluster: `curl -s http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse/.well-known/openid-configuration \| jq -r .issuer` phải ra `https://10.29.19.56:32004/realms/lakehouse` |

K5 cũng là điều kiện để Kyuubi chấp nhận token mà notebook chuyển tiếp (Kyuubi kiểm cùng issuer).

## 4. Kiểm chứng sau deploy

| # | Lệnh / thao tác | Kỳ vọng |
|---|---|---|
| V1 | `curl -s http://10.29.19.56:32380/api/v1/auth/config` | `{"mode":"oidc",...,"loginUrl":"/api/v1/auth/oidc/login"}`; **không** có `issuer`/`redirectPath` (nếu còn → vẫn image cũ) |
| V2 | `curl -si 'http://10.29.19.56:32380/api/v1/auth/oidc/login?redirect=/'` | `302`, `Location: https://10.29.19.56:32004/realms/lakehouse/protocol/openid-connect/auth?response_type=code&client_id=kyuubi-notebook&redirect_uri=http%3A%2F%2F10.29.19.56%3A32380%2Fapi%2Fv1%2Fauth%2Fcallback&...&code_challenge_method=S256` |
| V3 | Mở `http://10.29.19.56:32380/` trên trình duyệt | Tự chuyển sang trang đăng nhập Keycloak (chấp nhận cert tự ký nếu trình duyệt hỏi), đăng nhập xong quay về notebook, góc phải hiện tên người dùng |
| V4 | DevTools → Application → Cookies | Có `NB_SESSION` (HttpOnly, SameSite=Lax) và `XSRF-TOKEN`; **không** có token nào trong sessionStorage/localStorage |
| V5 | Chạy cell `SHOW DATABASES FROM iceberg;` | Có kết quả; log Kyuubi ghi user là người đăng nhập (không phải `kyuubi`) |
| V6 | Bấm đăng xuất | Chuyển tới end-session của Keycloak rồi về `http://10.29.19.56:32380/`; mở lại trang phải đăng nhập lại |
| V7 | `curl -si http://10.29.19.56:32380/api/v1/me -H 'Authorization: Bearer abc.def.ghi'` | `401` với message `Malformed JWT` hoặc `JWT validation failed`, **không** phải `Missing preferred_username claim` |
| V8 | Log pod khi đăng nhập | `Established OIDC session for '<user>'`; không có `OIDC callback failed` |

### Chẩn đoán khi V3 lỗi (URL quay về `/?authError=...`)

| `authError` / log | Nguyên nhân | Sửa |
|---|---|---|
| `invalid_redirect_uri` hiện trên trang Keycloak | K2 thiếu | Thêm redirect URI |
| `oidc_login_failed` + log `Token exchange failed (unauthorized_client/invalid_client)` | K1: client còn public hoặc sai secret | Bật client authentication, cập nhật `oidc-client-secret` |
| `unauthenticated` + log `JWT validation failed: JWT iss claim has value ...` | K5 | Cố định hostname Keycloak |
| `unauthenticated` + log `JWT aud claim has value [account]` | K4 | Thêm audience mapper `kyuubi` |
| `auth_backend_unavailable` | Pod không gọi được token endpoint / JWKS in-cluster | Kiểm DNS/NetworkPolicy tới `keycloak-service.keycloak-prod:8080` |

## 5. Chỉ sửa code nếu kiểm chứng ở mục 4 lộ lỗi
Không có thay đổi code bắt buộc. Nếu phát hiện lỗi mới, agent: viết test tái hiện trước (theo mẫu `OidcLoginFlowTest`/`OidcSecurityTest`), sửa, chạy `./scripts/build.sh`, không thêm comment vào code.

## 6. Đầu vào cần người dùng cung cấp
| # | Đầu vào |
|---|---|
| I1 | Nội dung `notebook-helm/experiment/values.yaml` và `templates/deployment.yaml` đang deploy (để đối chiếu mục 2.2) |
| I2 | Quyền sửa client `kyuubi-notebook` trên Keycloak (mục 3) |
| I3 | Kết quả lệnh K5 |
