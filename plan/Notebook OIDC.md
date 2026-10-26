# Plan: Notebook OIDC — chuyển sang Authorization Code flow phía server

## Hiện trạng (đã xác minh 2026-10-26)
- `GET /api/v1/auth/config` trả `{"mode":"oidc","issuer":"https://10.29.19.56:32004/realms/lakehouse","clientId":"kyuubi-notebook","redirectPath":"/auth/callback","scopes":[...]}` → backend đã nhận đủ cấu hình.
- Frontend (`frontend/public/app/api.js`) dùng **oidc-client-ts chạy trong trình duyệt** (Authorization Code + PKCE, public client):
  - `initOidcUserManager()` tạo `UserManager({authority: issuer, ...})`; khi 401 từ `/api/v1/me` thì `signinRedirect()`.
  - `signinRedirect()` phải `fetch()` `https://10.29.19.56:32004/realms/lakehouse/.well-known/openid-configuration` **từ trình duyệt**. Keycloak dùng cert tự ký (`O=cert-manager, CN=ingress`) → fetch fail (curl không `-k` trả HTTP 000) → ném exception → rơi vào `catch` → `showLoginModal('Không thể kết nối đến máy chủ.')`. **Đây là lý do không redirect.**
  - Bug kèm theo: `sessionStorage.oidc_redirect_attempt = '1'` được set **trước** khi `signinRedirect()` thành công → các lần reload sau trong cùng tab chỉ hiện modal "Phiên đăng nhập OIDC hết hạn", không thử redirect lại.
  - Mọi API call gắn `Authorization: Bearer <access_token>`; backend xác thực JWT (lỗi mẫu: `UNAUTHENTICATED: Missing preferred_username claim in token`). Backend lấy JWKS từ `issuer` (https tự ký) → JVM sẽ dính `PKIX path building failed` (đã gặp với Kyuubi, xem memory keycloak-lakehouse-oidc).
- Kết luận: flow phía trình duyệt không dùng được với Keycloak cert tự ký. Chart đã khai báo sẵn các endpoint **in-cluster http** (`tokenEndpoint`, `jwksUrl` → `http://keycloak-service.keycloak-prod.svc.cluster.local:8080/...`) và `redirectUri: /api/v1/auth/callback`, `ui.clientSecret` — tức là values đã mô tả flow phía server, app chưa implement.

## Mục tiêu
Backend thực hiện toàn bộ OIDC (redirect → callback → đổi code lấy token qua URL in-cluster → tạo session cookie `NB_SESSION` giống mode ldap). Trình duyệt chỉ điều hướng tới trang đăng nhập Keycloak, không `fetch()` sang Keycloak. Giữ nguyên mode `ldap` / `no-oidc` bằng `if/else` theo `notebook.auth.mode`.

## Cấu hình backend đọc (đã có trong chart `notebook-helm/experiment`, ConfigMap `application.yaml`)
```yaml
notebook:
  auth:
    mode: oidc            # oidc | ldap | no-oidc
    oidc:
      jwt:
        issuer: https://10.29.19.56:32004/realms/lakehouse      # so với claim iss
        audience: [kyuubi]                                      # aud phải chứa 1 trong các giá trị (hoặc clientId)
        jwksUrl: http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse/protocol/openid-connect/certs
        usernameClaim: preferred_username
        allowedAlgorithms: [RS256]
      ui:
        clientId: kyuubi-notebook
        scope: "openid profile email"
        # clientSecret: KHÔNG có trong ConfigMap — đến từ env NOTEBOOK_AUTH_OIDC_UI_CLIENTSECRET
        #   (Secret notebook-secrets / key oidc-client-secret; Spring relaxed binding → notebook.auth.oidc.ui.client-secret)
      authorizationEndpoint: https://10.29.19.56:32004/realms/lakehouse/protocol/openid-connect/auth   # trình duyệt đi
      endSessionEndpoint:    https://10.29.19.56:32004/realms/lakehouse/protocol/openid-connect/logout # trình duyệt đi
      tokenEndpoint: http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse/protocol/openid-connect/token  # backend gọi
      redirectUri: http://10.29.19.56:32380/api/v1/auth/callback
      sessionTimeout: PT4H
      serviceAccount:
        enabled: false
        clientId: notebook-api
        # clientSecret từ env NOTEBOOK_AUTH_OIDC_SERVICEACCOUNT_CLIENTSECRET
      # 4 key cũ (issuer, clientId, redirectPath, scopes) hiện vẫn render để app 0.0.15 chạy — xoá khỏi values.yaml sau khi backend đọc key mới.