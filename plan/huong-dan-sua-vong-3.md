# Hướng dẫn sửa vòng 3 `notebook-api` (dành cho AI agent)

Trạng thái: chưa thực hiện. Ngày viết: 2026-10-14.
Đầu vào: biên bản `plan/nghiem-thu-2026-10-14.md` và `plan/nghiem-thu-2026-10-14-2.md`, đối chiếu với
code tại commit `7dae13f`. **Code không đổi kể từ `bf8cec5`**: hai commit sau chỉ thêm file trong `plan/`.
Plan gốc vẫn là `plan/mariadb-auth-plan.md`. Khi nghiệm thu lại, dùng `plan/huong-dan-nghiem-thu.md`.

Mỗi việc ghi: **Ở đâu**, **Làm gì**, **Xong khi** (điều kiện kiểm được) và **Mã kiểm** (mã trong
hướng dẫn nghiệm thu). Không làm việc ngoài danh sách. Thấy thiếu thì ghi vào `plan/round3-notes.md`.

---

## 0. Đính chính biên bản `nghiem-thu-2026-10-14-2.md`

Biên bản này liệt kê đúng 7 lỗi chặn B1–B7. Nhưng nó chấm **ĐẠT sai** cho các mục dưới đây (kiểm lại
trên code `7dae13f`). Những mục này phải sửa trong vòng này, đánh mã `C*` ở mục 3.

| Mã | Biên bản ghi | Thực tế | Bằng chứng |
|---|---|---|---|
| D1 | ĐẠT: "entrypoint dừng khi thiếu biến bắt buộc" | **Sai.** Thiếu `NOTEBOOK_INTERNAL_TOKEN` thì script **tự sinh token ngẫu nhiên** và chạy tiếp. | `docker/entrypoint.sh:7-10` |
| K10 | ĐẠT: "script chạy được" | **Sai.** `build-all-in-one.sh`, `build-dist.sh`, `BUILD.md`, `DOCKER.md` đều gọi `apache-maven-3.9.11/bin/mvn`. Trong git, `lib/` của thư mục này chỉ có file license (jar bị `.gitignore` chặn), nên máy clone mới **không build được**. Máy của người nghiệm thu chạy được chỉ vì có jar cục bộ không nằm trong git. | `scripts/build-all-in-one.sh:14`, `scripts/build-dist.sh:15` |
| K5 | MỘT PHẦN, chỉ nêu DOCKER.md | `BUILD.md:30` cũng có `file:///mnt/d/...`. | `BUILD.md:30` |
| K9, D4 | ĐẠT | **Sai.** Khối `oidc:` trong `values.yaml` (issuer, clientId, scopes, usernameClaim) **không được template dùng**. Không có cách cấu hình `auth.ldap.*`. Secret `oidc-client-secret` khai báo nhưng không mount. Biên bản không chạy `helm template`. | `notebook-helm/templates/deployment.yaml` |
| D2 | ĐẠT | Probe chỉ đi qua nginx tới actuator của notebook-service (`:8085`). Execution-service chết thì pod vẫn Ready. | `docker/nginx.conf` (`/actuator/`) |
| D3 | ĐẠT | Không đặt `X-Forwarded-Proto`. Spring không biết request là HTTPS, nên sửa B2 (`Secure`) sẽ không đúng sau proxy. | `docker/nginx.conf` |
| E12 | ĐẠT | Có hàm lọc, nhưng `openSession(authHeader, null)` **luôn truyền `null`**: cấu hình Spark của người dùng không bao giờ tới Kyuubi. | `ExecutionService.java:61, 324` |
| E9 | ĐẠT | Tăng `executionCountSeq` không khóa, nên submit đồng thời bị trùng số. Hủy không khóa, nên có thể ghi đè `SUCCEEDED` thành `CANCELED`. | `ExecutionService.java:97-100, 306` |
| E10 | ĐẠT | Session `LOST` vẫn được dùng lại (chỉ loại `CLOSED`). | `ExecutionService.java:~50` |
| E5 | ĐẠT | Submit Kyuubi lỗi thì bản ghi cell-run chưa được tạo (tạo sau submit), không còn dấu `FAILED`. | `ExecutionService.java:102` |
| L12 | ĐẠT | Import không đọc `metadata.language`. Magic lạ rơi về `PYTHON` thay vì `defaultLanguage`. `RAW` sai language. Bỏ `execution_count`, `outputs`, `kernelspec`, `id` và metadata cell. Export bỏ `includeOutputs`, ghi `kernelspec` cứng. Lỗi 409/413 bị đổi thành 400. | `IpynbConversionService.java` |
| L14 | ĐẠT | `updateNotebook` nuốt lỗi parse JSON **nhưng vẫn tăng revision**. | `NotebookService.java:~180-190` |
| A14 | ĐẠT | `KyuubiRestClient.resolveAuth` rơi về `KyuubiCredentialHolder` khi request không có header. | `KyuubiRestClient.java:58-63` |
| A15 | ĐẠT | `ex.getMessage()` và body lỗi Kyuubi được trả nguyên cho client. | `LdapAuthenticator.java:96`, `KyuubiRestClient.java:106,140`, `SessionCookieFilter`, `BearerDelegationAuthenticator` |
| E6 | MỘT PHẦN | `:interrupt` truyền id runtime (session) vào hàm hủy cell-run, nên **luôn 404**. `DELETE` session không đóng session Kyuubi. | `NotebookExecutionController.java:156-162, 186` |
| E7 | CHƯA KIỂM | Đã kiểm: `/events` chạy trên `ForkJoinPool.commonPool()`, mất security context. | `NotebookExecutionController.java:79` |

Bài học cho lần nghiệm thu sau (bổ sung vào cách làm, không sửa file hướng dẫn nghiệm thu):
- Chạy `helm template` thật khi chấm K9.
- Đọc `entrypoint.sh` khi chấm D1.
- Chấm K10 bằng `git ls-files apache-maven-3.9.11/lib`, không chạy thử trên máy có sẵn jar.

---

## 1. Quy tắc làm việc

| # | Quy tắc |
|---|---|
| W1 | Làm theo **thứ tự ở mục 4**. Mỗi bước xong phải `mvn -B verify` xanh rồi mới sang bước sau. |
| W2 | Mỗi lỗi chặn (B*) phải có **ít nhất một test** chứng minh đã sửa: test đỏ trước khi sửa, xanh sau khi sửa. |
| W3 | Dữ liệu giả trong test phải đúng **định dạng thật** (JSON Kyuubi ở mục 2.5, DDL ở plan §1.1). |
| W4 | Không đổi API công khai, schema DB, tên cấu hình ngoài những gì ghi ở đây. Đổi schema thì thêm `V4__*.sql`, **không sửa** V1–V3. |
| W5 | Commit theo từng bước, message dạng `fix(B1): …`, `test(T1): …`. Không push khi chưa được chủ dự án cho phép. |
| W6 | Build bằng Docker vì máy không có Maven và chỉ có Java 8 (lệnh ở `huong-dan-nghiem-thu.md` §1.2). |

---

## 2. Lỗi chặn

### B1. Validator và DatasourceGuard không được nạp  (K2, K3, A1)

**Nguyên nhân.** Spring Boot 3.3 chỉ nạp `EnvironmentPostProcessor` từ `META-INF/spring.factories`.
File `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` hiện có **không
được đọc**. `AuthPropertiesValidator` còn không có mặt ở đâu cả.

**Làm gì**

1. Tạo `backend/nb-common/src/main/resources/META-INF/spring.factories`:
   ```properties
   org.springframework.boot.env.EnvironmentPostProcessor=\
     com.vtnexus.notebook.common.datasource.DatasourceGuard,\
     com.vtnexus.notebook.common.auth.AuthPropertiesValidator
   ```
2. Xóa file `.imports` nói trên để khỏi gây hiểu nhầm.
3. `DatasourceGuard`: URL **rỗng hoặc null** cũng phải ném lỗi `spring.datasource.url is required`
   (hiện đang bỏ qua).
4. `AuthPropertiesValidator` chạy ở pha `EnvironmentPostProcessor`, lúc profile đã có. Đọc profile dev
   bằng `environment.acceptsProfiles(Profiles.of("dev"))`, không đọc chuỗi `spring.profiles.active`.
5. Bỏ nhánh `else` mặc định OIDC trong **cả hai** `SecurityConfig`
   (`nb-notebook-service/.../SecurityConfig.java:40-45`, `nb-execution-service/.../SecurityConfig.java`).
   Dùng `switch` trên `AuthMode`. `AuthMode.fromString` trả `null` thì ném `IllegalStateException`
   (lớp phòng thủ thứ hai nếu validator bị tắt).
6. `@Value` của `internalToken` ở mọi nơi bỏ giá trị mặc định rỗng (`${notebook.internalToken:}`
   → `${notebook.internalToken}`).

**Xong khi**
- Test mới `nb-notebook-service/src/test/.../StartupFailureTest.java` dùng `SpringApplicationBuilder`
  (không cần DB, dùng `spring.main.web-application-type=none`, chỉ cần tới pha environment) kiểm
  4 trường hợp, mỗi trường hợp **ném lỗi có thông báo đúng như bảng §2.5 plan**:
  `notebook.auth.mode=abc`, thiếu `notebook.internalToken`, URL `jdbc:postgresql://x/y`,
  `mode=ldap` mà không có `url`.
- `grep -rn "Mặc định OIDC" backend` rỗng.

### B2. CSRF tắt, cookie thiếu `Secure`/`SameSite`  (A5, A6, D3)

**Làm gì**

1. `SecurityConfig` của **cả hai** service, theo mode:
   - `OIDC`: giữ `csrf.disable()` (không dùng cookie).
   - `NO_OIDC`, `LDAP`:
     ```java
     .csrf(c -> c
         .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
         .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())   // SPA gửi header thô, không XOR
         .ignoringRequestMatchers("/internal/**"))
     ```
     Thêm một filter sau `CsrfFilter` gọi `csrfToken.getToken()` để cookie `XSRF-TOKEN` luôn được ghi
     (Spring Security 6 nạp token lười). `/api/v1/auth/login` **không** được bỏ qua CSRF: SPA gọi
     `GET /api/v1/auth/config` trước để nhận cookie.
2. Cookie phiên: thay `new Cookie(...)` ở `AuthController.java:106-110` và chỗ xóa cookie ở `:130-134`
   bằng `ResponseCookie`:
   ```java
   ResponseCookie.from(COOKIE_NAME, id).httpOnly(true).secure(!isDev).sameSite("Strict")
       .path("/").maxAge(sessionTtl).build();
   response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
   ```
   `isDev` = profile `dev` đang bật. Xóa cookie dùng cùng thuộc tính với `maxAge(0)`.
3. Đặt `AuthProperties.sessionTtl`, `recheck`, `cacheTtl` thành kiểu `java.time.Duration`. Spring tự
   chuyển `8h`, `10m`, `60s`. Bỏ `Duration.parse("PT" + …)`.
4. Sau proxy: `docker/nginx.conf` thêm `proxy_set_header X-Forwarded-Proto $scheme;` ở mọi `location`
   proxy. Cả hai `application.yml` thêm `server.forward-headers-strategy: native`. Khi có TLS ở
   Ingress, header phải đi tiếp; ghi chú vào README.
5. `frontend/public/index.html`: mọi request không phải `GET` gửi `X-XSRF-TOKEN` (hiện chỉ có ở login).
   Gom vào một hàm `api(path, opts)` dùng chung.

**Xong khi**
- Test MockMvc mode `no-oidc`: `POST /api/v1/notebooks` có cookie phiên nhưng **không** có
  `X-XSRF-TOKEN` → `403`. Có header đúng → không phải `403`.
- Test login: header `Set-Cookie` chứa `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`.
- Test mode `oidc`: `POST` có Bearer, không có CSRF → không bị `403` vì CSRF.

### B3. LDAP injection, sai tên chuẩn, `adminGroup` cứng  (A10, A11)

Viết lại `LdapAuthenticator.authenticate` theo các bước sau:

1. **Escape**:
   - Ghép DN: `LdapEncoder.nameEncode(username)`.
   - Ghép filter: **không** dùng `replace`. Dùng
     `LdapQueryBuilder.query().base(userSearchBase).filter(userSearchFilter, username)`. Spring tự
     escape `{0}`.
   - Từ chối ngay username rỗng, dài hơn 255 ký tự, hoặc có ký tự điều khiển.
2. **Tìm DN thật**:
   - Nhánh `userSearch`: dùng `ContextMapper` lấy `DirContextAdapter.getNameInNamespace()` (DN đầy đủ)
     **và** thuộc tính `usernameAttribute`.
   - Không tìm thấy, hoặc tìm thấy nhiều hơn 1 kết quả → `401`.
   - **Không tự ghép** DN như dòng 69 hiện tại.
3. **Bind** bằng DN tìm được (hoặc DN từ `userDnPattern`) và mật khẩu người dùng. Mật khẩu rỗng →
   `401` ngay, vì nhiều LDAP coi bind mật khẩu rỗng là bind ẩn danh và trả thành công.
4. **Tên chuẩn**: lấy giá trị `usernameAttribute` của entry (nhánh `userDnPattern` cũng phải đọc lại
   entry sau bind) và trả về giá trị đó, **không** trả chuỗi người dùng gõ.
5. **Admin**: `adminGroup` có giá trị thì tìm trong `groupSearchBase` với filter
   `(&(objectClass=*)(<groupMemberAttribute>={0}))`. Tham số là **DN** nếu attribute là `member` hoặc
   `uniqueMember`, là **tên chuẩn** nếu là `memberUid`. Rồi kiểm DN group có bằng `adminGroup` không
   (so không phân biệt hoa thường qua `LdapName`).
6. **TLS**:
   - `ldaps://`: dựng `SSLSocketFactory` từ `trustStore` nếu có cấu hình.
   - `ldap://` + `startTls=true`: dùng `DefaultTlsDirContextAuthenticationStrategy` trên
     `LdapContextSource`.
   - `trustAll`: chỉ khi profile dev.
7. **Pool và timeout**:
   - Tạo `LdapContextSource` **một lần** (bean), không tạo mới mỗi lần login.
   - Đặt `com.sun.jndi.ldap.connect.timeout` và `com.sun.jndi.ldap.read.timeout` theo cấu hình.
8. **Lỗi**:
   - `AuthenticationException` → `401 INVALID_CREDENTIALS`.
   - `CommunicationException` hoặc timeout → `503 AUTH_BACKEND_UNAVAILABLE`, thông báo cố định
     "LDAP unavailable".
   - **Không** nối `ex.getMessage()` vào thông báo trả về; ghi log `WARN` phía server.

**Xong khi** có test `LdapAuthenticatorTest` với UnboundID in-memory (`com.unboundid:unboundid-ldapsdk`,
scope test). Dữ liệu: `uid=alice` thuộc `cn=notebook-admins`, `uid=bob` không thuộc.
- Gõ `ALICE` + đúng mật khẩu → trả `alice`, `admin=true`.
- `bob` → `admin=false`.
- Sai mật khẩu → 401. Mật khẩu rỗng → 401.
- Username `*)(uid=*` → 401, không ném lỗi khác.
- Username `alice,ou=x` → 401.
- Tắt server → 503.
- Chạy cả hai cấu hình `userDnPattern` và `userSearch`.

### B4. Proxy user theo `ranger_username`  (A13)

Nhánh này phụ thuộc kết quả P5.7 trên cluster. Viết code và test bằng WireMock ngay; chỉ bật mặc định
khi P5.7 đạt.

1. **notebook-service** thêm endpoint nội bộ `GET /internal/users/{username}/ranger-username` (kèm
   `X-Internal-Token`), đọc `app_users`. Không có dòng → trả `username`. Execution-service gọi endpoint
   này, cache Caffeine 5 phút. Execution-service **không** được đọc bảng `app_users` trực tiếp, vì bảng
   thuộc notebook-service.
2. **execution-service** thêm `ServiceAccountTokenProvider`:
   - Gọi `POST <issuer>/protocol/openid-connect/token` với `grant_type=client_credentials`,
     `client_id=notebook-api`, `client_secret` lấy từ `NOTEBOOK_OIDC_CLIENT_SECRET`.
   - Cache token tới `exp − 30s`.
   - Thiếu secret → `503 PROXY_UNAVAILABLE`.
3. `ExecutionService.getOrCreateSession` và `restartSession`, chỉ ở mode `oidc`:
   ```text
   ranger = rangerClient.lookup(user)
   if ranger == user  → openSession(userBearer, configs)
   else               → openSession(serviceBearer, configs + {"hive.server2.proxy.user": ranger})
   ```
   Lưu cờ `proxied BOOLEAN` và `effective_user VARCHAR(255)` vào `notebook_kyuubi_sessions` (thêm cột
   bằng `V4__proxy_session.sql`). Mọi lời gọi Kyuubi sau đó của session `proxied=true` dùng
   `serviceBearer`, **không** dùng token người dùng.
4. Ở mode `no-oidc`/`ldap`: nếu `ranger ≠ user` thì log `WARN` và chạy bằng user đăng nhập. Proxy qua
   `Basic` cần credential dịch vụ, nằm ngoài phạm vi; ghi vào `round3-notes.md`.
5. Kyuubi trả lỗi chứa `impersonate` → `403 PROXY_NOT_ALLOWED`, thông báo cố định.

**Xong khi** có test WireMock:
- `alice` có `ranger=alice` → `POST /sessions` mang token của alice, không có `hive.server2.proxy.user`.
- `bob` có `ranger=svc_bob` → mang token dịch vụ và `configs.hive.server2.proxy.user=svc_bob`.
- Kyuubi trả lỗi impersonate → 403.
- Thiếu secret → 503.

### B5. Đọc kết quả Kyuubi sai định dạng  (E1, E2)

**Định dạng thật** của Kyuubi REST 1.9/1.10:

```json
GET /api/v1/operations/{op}/resultsetmetadata
{"columns":[{"columnName":"id","dataType":"INT_TYPE","precision":0,"scale":0,"comment":""}]}

GET /api/v1/operations/{op}/rowset?maxrows=N&fetchorientation=FETCH_NEXT
{"rows":[{"fields":[{"dataType":"INT_TYPE","value":1},{"dataType":"STRING_TYPE","value":"a"}]}],"rowCount":1}

GET /api/v1/operations/{op}/log?maxrows=N&fetchorientation=FETCH_NEXT
{"logRowSet":["line1","line2"],"rowCount":2}
```

`value` có thể là `null`, số, chuỗi hoặc boolean. Các kiểu `DECIMAL`, `TIMESTAMP`, `BINARY` thường đến
dưới dạng chuỗi.

**Làm gì**

1. Tách parser ra class mới `execution/service/KyuubiResultParser.java` (thuần, không Spring), gồm:
   - `List<Column> columns(JsonNode metadata)`
   - `List<List<JsonNode>> rows(JsonNode rowset)`: đọc `rows[].fields[].value`
   - `List<String> logLines(JsonNode log)`
   - `ErrorInfo error(String message)`: tách `ename`/`evalue`/`traceback`
2. **SQL**: gọi `getResultSetMetadata` trước, rồi `getOperationRowset` lặp `FETCH_NEXT` cho tới khi
   `rowCount == 0` hoặc đủ `results.max-rows`.
   - `truncated = true` khi còn dữ liệu mà đã đủ giới hạn.
   - `row_count` = số dòng đã lấy.
   - Output là `execute_result` với
     `data["application/vnd.notebook.table+json"] = {columns:[{name,type}], rows:[[...]], truncated}`
     **và** thêm `data["text/plain"]` dạng bảng chữ (tối đa 50 dòng) để export `.ipynb` mở được trên
     Jupyter.
3. **Python** (output `AUTO`): rowset thường có 1 cột chuỗi, mỗi dòng là một phần stdout. Nối các
   `value` bằng `\n`. Rỗng thì **không** tạo output `stream` (`output_type = NONE`).
4. **Lỗi**: `event.exception` có thể là chuỗi hoặc object, lấy `message`.
   - Tìm traceback Python theo mẫu dòng `Traceback (most recent call last):`.
   - `ename`/`evalue` lấy từ dòng cuối dạng `Name: value`.
   - Lỗi SQL: `ename` = lớp exception đầu tiên dạng `org.apache…Exception`, nếu không có thì
     `"SQLException"`.
   - `traceback` = các dòng của message (tách `\n`), giới hạn 200 dòng.
5. **Log**: lấy log **mỗi lần sync** khi còn `RUNNING`, không chỉ lúc kết thúc.
   `sequence` tiếp nối `max(sequence)` hiện có của cell-run (hiện luôn bắt đầu từ 0, nên trùng
   `UNIQUE (cell_run_id, sequence)` ở lần sync thứ hai).
6. Ghi `execution_time_ms = finishedAt − startedAt`.

**Xong khi**
- `KyuubiResultParserTest` dùng **đúng JSON ở trên**. Có ca `value: null`, ca 0 dòng, ca truncated,
  ca traceback Python thật (tối thiểu `NameError`), và ca lỗi SQL `AnalysisException`.
- `ExecutionServiceAutoOutputTest` thay toàn bộ mock mảng trần bằng JSON thật. Test cũ dùng mảng trần
  phải **đỏ** với code mới (chứng minh test cũ đã sai).

### B6. Trạng thái `CLOSED`/`UNKNOWN` và vòng đời run  (E3, E4, E5)

Viết lại `syncCellRun` thành ánh xạ đầy đủ:

| Kyuubi `state` | cell-run / run |
|---|---|
| `INITIALIZED`, `PENDING`, `RUNNING` | `RUNNING`, cập nhật `heartbeat_at` |
| `FINISHED` | `SUCCEEDED` (B5) |
| `ERROR`, `TIMEOUT` | `FAILED` (`TIMEOUT` → `error_code = "TIMEOUT"`) |
| `CANCELED` | `CANCELED` |
| `CLOSED`, `UNKNOWN`, trạng thái lạ | `LOST`, `error_code = "KYUUBI_" + state` |
| HTTP 404 khi hỏi operation | `LOST` |
| Kyuubi không trả lời | giữ `RUNNING`, **không** ném lỗi ra API `GET`; trả DTO kèm `stale=true` |

Vòng đời submit (sửa luôn E5):
1. Transaction 1: tạo `notebook_runs` (`PENDING`) và `notebook_cell_runs` (`PENDING`, `source`,
   `execution_order`), tăng `execution_count` **có khóa** (`findByIdForUpdate` trên session). Commit.
2. Ngoài transaction: `submitStatement`.
3. Transaction 2: thành công thì `RUNNING`, ghi `execution_id`, `started_at`. Lỗi thì `FAILED`,
   `error_code`, `error_message` (thông báo đã làm sạch).

**Xong khi** có test cho từng dòng của bảng trạng thái, và test submit lỗi để lại một cell-run `FAILED`.

### B7. Frontend OIDC: PKCE và callback  (D5)

Frontend hiện là một file `frontend/public/index.html`, không có bước build. Không dựng Vue/Vite trong
vòng này (đó là P1-7 vòng 2). Làm tối thiểu cho đăng nhập chạy được:

1. Thêm `oidc-client-ts` **tự host**: tải bản `oidc-client-ts@3.1.0` dạng browser build vào
   `frontend/public/vendor/oidc-client-ts.min.js`. Plan cấm CDN. Ghi checksum SHA-256 vào
   `frontend/public/vendor/README.md`.
2. Tạo `UserManager` từ `/api/v1/auth/config`:
   - `authority = issuer`, `client_id = clientId`, `redirect_uri = origin + '/auth/callback'`,
     `response_type = 'code'` (PKCE mặc định).
   - `scope = scopes.join(' ')`, `automaticSilentRenew = true`.
   - `userStore = new WebStorageStateStore({ store: window.sessionStorage })`.
3. Đường dẫn `/auth/callback`: nginx đã `try_files … /index.html`. Trong `index.html`, khi
   `location.pathname === '/auth/callback'` thì gọi `signinRedirectCallback()`, rồi
   `history.replaceState` về trang trước, rồi tải app.
4. Hàm `api()` (B2 bước 5): mode `oidc` gắn `Authorization: Bearer ${user.access_token}`. Gặp `401`
   thì thử `signinSilent()` một lần, vẫn lỗi mới `signinRedirect()`.
5. **Chống lặp vô hạn**: đặt cờ `sessionStorage['oidc_redirect_attempt']`. Đã chuyển hướng 1 lần mà
   vẫn 401 thì hiện lỗi, không chuyển hướng tiếp.
6. Logout `oidc`: `signoutRedirect()`.
7. Keycloak: client `kyuubi-notebook` phải là **public**, *Valid redirect URIs* gồm
   `http://10.29.19.56:32380/auth/callback`, *Web origins* gồm origin đó. Ghi vào README phần vận hành.

**Xong khi** chạy thủ công trên cluster: đăng nhập được, F5 vẫn còn phiên, token gần hết hạn được tự
làm mới, `/me` trả user. Ghi kết quả vào biên bản nghiệm thu mục 6. Tại chỗ: mở `index.html` với
`/api/v1/auth/config` giả lập (WireMock hoặc nginx tĩnh) và kiểm request tới Keycloak có
`code_challenge` và `code_challenge_method=S256`.

---

## 3. Lỗi cần sửa (sau khi xong B1–B7)

| Mã | Ở đâu | Làm gì | Xong khi |
|---|---|---|---|
| C1 (D1) | `docker/entrypoint.sh:7-10` | Thiếu `NOTEBOOK_INTERNAL_TOKEN` thì `echo` lỗi và `exit 1`. Bỏ phần tự sinh token. | Chạy container không có biến → thoát mã 1 |
| C2 (K10) | `scripts/*.sh`, `BUILD.md`, `DOCKER.md` | Thêm Maven Wrapper vào `backend/` (`mvnw`, `.mvn/wrapper/maven-wrapper.properties` trỏ 3.9.9). Script gọi `backend/mvnw`. Xóa thư mục `apache-maven-3.9.11/` khỏi git (`git rm -r --cached`) và thêm vào `.gitignore`. | `git clone` mới rồi `./scripts/build-all-in-one.sh` chạy được khi có JDK 17 |
| C3 (K5, D7) | `DOCKER.md`, `BUILD.md` | Xóa mục ArgoCD (dòng 1, 3, 39–46), thay mọi `file:///mnt/d/DOC/VT/notebook-api/` bằng đường dẫn tương đối. Tiêu đề DOCKER.md: "Build Docker image". Việc deploy là của hệ thống khác, chỉ ghi "image được hệ thống deploy của nền tảng kéo về". | `grep -rniE "argocd|file:///mnt" .` (trừ `plan/`) rỗng |
| C4 (K9, D4) | `notebook-helm/templates/deployment.yaml`, `values.yaml` | Truyền `NOTEBOOK_AUTH_OIDC_ISSUER`, `…_CLIENT_ID`, `…_SCOPES`, `NOTEBOOK_AUTH_USERNAME_CLAIM`. Thêm khối `auth.ldap.*` (mọi key plan §2.5 trừ password) và render env khi `mode=ldap`. Mount `oidc-client-secret` thành `NOTEBOOK_OIDC_CLIENT_SECRET` (`optional: true`) khi `mode=oidc`. Gộp khối `oidc:` cũ vào `auth.oidc`. | `helm template` với 3 mode, mỗi mode đủ env, không thừa secret của mode khác |
| C5 (D2) | `nginx.conf`, `deployment.yaml` | Thêm `location = /healthz` trả 200 chỉ khi **cả hai** actuator UP: dùng script nhỏ trong container cho `exec` probe `curl -fs :8085/actuator/health/readiness && curl -fs :8083/actuator/health/readiness`. Liveness dùng tương tự cho `liveness`. | Kill execution-service trong pod → pod NotReady trong 15 s |
| C6 | `Dockerfile` | Tạo user `notebook` (uid 10001), `USER 10001`. nginx listen 8080 (không cần root), `pid /tmp/nginx.pid`, các đường dẫn temp sang `/tmp`. | `docker run … id -u` = 10001, cả 3 tiến trình chạy |
| C7 (E12) | `ExecutionService.java:61, 324`, controller | `POST /notebooks/{id}/sessions` nhận body `{ "configs": {...} }`, truyền xuống `openSession`, lọc theo `allowedSessionConfigKeys`. Key bị loại → trả trong `warnings`. | Test WireMock: key cho phép tới Kyuubi, key lạ không tới |
| C8 (E9) | `ExecutionService` | `cancelExecution` dùng `findByIdForUpdate`, chỉ đổi khi trạng thái còn `PENDING`/`RUNNING`. Đặt `cancel_requested=1` trước khi gọi Kyuubi. | Test hủy sau khi đã `SUCCEEDED` → giữ `SUCCEEDED` |
| C9 (E10) | `getOrCreateSession` | Chỉ dùng lại session `READY`/`BUSY`. `LOST`/`CLOSED`/`FAILED` → mở mới. | Test |
| C10 (E8) | `ExecutionService` | Bỏ `@Transactional` ở cấp class. Đặt transaction ngắn quanh từng lần đọc/ghi DB (`TransactionTemplate`). Không gọi HTTP Kyuubi khi đang giữ `FOR UPDATE`: gọi Kyuubi trước, rồi khóa và ghi, ghi chỉ khi trạng thái chưa kết thúc. | Đọc code: không có `restTemplate` trong vùng khóa |
| C11 (E6) | `NotebookExecutionController` | `/results?offset&limit` cắt trên `output_json` đã lưu. `/logs?afterSequence` query `sequence > ?`. `DELETE /notebook-sessions/{sid}` gọi `closeSession` Kyuubi rồi `CLOSED`. `:interrupt` tìm cell-run `RUNNING` của session đó rồi hủy. | Test từng endpoint |
| C12 (E7) | `NotebookExecutionController:79` | `ThreadPoolTaskExecutor` riêng (`notebook.events.poolSize`, mặc định 16), bọc bằng `DelegatingSecurityContextAsyncTaskExecutor`. Vòng chờ: sync → có thay đổi thì trả, không thì ngủ 500 ms, dừng khi quá `waitMillis` hoặc `DeferredResult` timeout. | Test: không gọi `ForkJoinPool`; timeout trả `204`/rỗng |
| C13 (E13) | `/notebook-status` | `kyuubi` = kết quả `/ping` thật (cache 10 s), `persistence` = `SELECT 1`. Đếm `active`/`queued` theo user hiện tại và trạng thái `RUNNING`/`PENDING`. | Test |
| C14 (A14) | `KyuubiRestClient.java:58-63` | Bỏ fallback sang `KyuubiCredentialHolder`. Header null → `IllegalStateException` (lỗi lập trình). Session proxied (B4) truyền token dịch vụ **tường minh**. | Test: gọi không header → ném lỗi |
| C15 (A15) | các file ở mục 0 | Thông báo trả client là chuỗi cố định theo mã lỗi. Chi tiết ghi log `WARN` kèm `requestId`. Body lỗi Kyuubi chỉ ghi log, không trả. | `grep -rn "getMessage()" backend/*/src/main` chỉ còn trong lệnh log |
| C16 (A4) | `nb-common` | Bean `KyuubiAuthProbe` (`ApplicationRunner`): gọi `/ping` với `Basic` ngẫu nhiên. 200 mà `allowUnverified=false` → thoát lỗi. 200 mà `true` → `WARN` mỗi 5 phút và `/auth/config.verified=false`. Mode `ldap`: 401 kèm `WWW-Authenticate: Bearer` → thoát lỗi. Kyuubi không trả lời → `WARN`, không chặn khởi động. | Test WireMock 4 ca |
| C17 (A8) | `AuthSessionService`, `SessionCookieFilter` | Trong `resolveSession`: `now − last_recheck_at > recheck` thì no-oidc gọi `/ping` bằng credential đã lưu, ldap bind lại. Thất bại xác thực → xóa phiên, 401. Backend không trả lời → giữ phiên, thử lại lần sau. | Test |
| C18 (A12) | `InternalAuthController:33`, `InternalAccessController`, các filter | So token bằng `MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))`. Gom thành một `InternalTokenFilter` cho `/internal/**`. Đổi `startsWith("/internal")` thành `startsWith("/internal/")`. | Test token sai → 401; `/internalx` không được miễn xác thực |
| C19 (A3) | `BearerDelegationAuthenticator:210` | Token không có `exp` thì cache tối đa `cacheTtl`, ghi `DEBUG`, không giả `exp`. | Test |
| C20 (L9) | `NotebookService`, `NotebookCellService` | Kiểm `schemaVersion == 1` khi đọc và ghi (khác → 409 `UNSUPPORTED_FORMAT`). Kiểm tổng `document_json` ≤ 16 MB trước khi lưu (413). Mọi phép đo kích thước dùng `getBytes(StandardCharsets.UTF_8)`. | Test |
| C21 (L11) | `NotebookService.deleteNotebook` | Giữ xóa mềm (để khôi phục). Thêm `DELETE /notebooks/{id}?purge=true` (chỉ OWNER/admin) xóa thật trong một transaction: logs → cell-runs → runs → kyuubi-sessions → revisions → permissions → notebook. Xóa mềm thì thu hồi quyền chia sẻ (xóa permission) để notebook biến mất khỏi danh sách người khác. Ghi quyết định vào plan §1.2. | Test T3 cả hai nhánh |
| C22 (L12) | `IpynbConversionService` | Làm đúng từng dòng bảng ánh xạ plan §1.3. Import lỗi 409/413 giữ nguyên mã. | Test round-trip `.ipynb` thật có markdown, raw, code SQL/Python, `execution_count`, `outputs`, `kernelspec`, `id` cell |
| C23 (L14) | `NotebookService.updateNotebook` | Lỗi parse/serialize → `400 INVALID_DOCUMENT`, **không** tăng revision, không lưu. | Test |
| C24 (L13) | dọn revision | Đếm và xóa **chỉ** `revision_type='AUTO'`, giữ `MANUAL`/`IMPORT`. | Test 105 AUTO + 3 MANUAL → còn 100 AUTO + 3 MANUAL |

---

## 4. Thứ tự thực hiện

| Bước | Việc | Lý do thứ tự |
|---|---|---|
| 1 | **B1** + `StartupFailureTest` | Các bước sau cần validator chạy thật |
| 2 | **T1**: Testcontainers MariaDB, test `@SpringBootTest` khởi động notebook-service và execution-service với Flyway + `ddl-auto: validate` trên (a) DB trống, (b) DB có sẵn 5 bảng `utf8mb3` theo DDL §1.1 | Chặn rủi ro `CHAR(36)`/`DATETIME(6)` trước khi viết thêm code DB |
| 3 | **B5**, **B6**, C10 | Làm cùng nhau vì cùng sửa `syncCellRun` và transaction |
| 4 | **B2**, C18, C15 | Bảo mật web chung |
| 5 | **B3**, C17 | LDAP |
| 6 | **B7** | Cần B2 (hàm `api()`) |
| 7 | C1–C6 | Đóng gói và chart |
| 8 | C7–C9, C11–C14, C16, C19 | Execution và auth còn lại |
| 9 | C20–C24 | Lưu trữ còn lại |
| 10 | **B4** | Viết code + test WireMock. Bật thật khi P5.7 đạt |
| 11 | README: mục Đăng nhập (3 mode), Kyuubi (REST, `AUTO`, giới hạn không có hình), Ranger (`ranger_username`, proxy), Keycloak client | |

---

## 5. Test bắt buộc thêm trong vòng này

| Mã plan | Test | Bước |
|---|---|---|
| K3 | `StartupFailureTest` | 1 |
| T1 | `MariaDbMigrationIT` (cả hai service), Testcontainers `mariadb:10.11` | 2 |
| T2 | `document_json` 5 MB, emoji (sau V3), xung đột `lock_version` → 409 | 2 |
| T3 | Xóa mềm và `purge` | 9 |
| T7 | Bearer: WireMock `/ping` 200/401/timeout, cache theo `exp` | 4 |
| T8 | Bearer A mở session, Bearer B (cùng user) poll → WireMock nhận B | 3 |
| T9 | no-oidc: login, cookie, CSRF, logout, recheck | 4, 5 |
| T10, T11 | LDAP UnboundID (B3); Kyuubi OIDC + mode ldap → khởi động lỗi (C16) | 5, 8 |
| T12 | Execution-service: cookie → `/internal/auth/session` → Basic tới Kyuubi, cache 60 s | 4 |
| T13 | `KyuubiResultParserTest` + `ExecutionServiceAutoOutputTest` với JSON thật | 3 |
| T14 | Round-trip SQLLAB và `.ipynb` (C22) | 9 |
| B4 | `ProxyUserTest` WireMock | 10 |

Mục tiêu: sau vòng này `mvn verify` có **≥ 80 test**, trong đó có test tích hợp (`*IT`) chạy
Testcontainers.

---

## 6. Điều kiện hoàn thành vòng 3

- B1–B7 có test chứng minh, nghiệm thu lại theo `huong-dan-nghiem-thu.md` không còn lỗi chặn (B4 được
  phép `CHƯA KIỂM` trên cluster nếu đã có test WireMock).
- C1–C24 đạt hoặc có lý do ghi trong `plan/round3-notes.md`.
- `helm template` 3 mode render đúng. Build từ `git clone` sạch chạy được.
- Có biên bản nghiệm thu mới `plan/nghiem-thu-<ngày>.md` ghi kết luận `ĐẠT` hoặc `ĐẠT CÓ ĐIỀU KIỆN`.

Việc **vẫn chờ chủ dự án**, không chặn vòng 3:
- Kết quả P5.1–P5.7 trên cluster (curl Kyuubi với Bearer, session user, Ranger, proxy).
- Client Keycloak `notebook-api` (confidential, service account) cho B4.
- Danh sách `hadoop.proxyuser.*` hiện có trên Kyuubi.
