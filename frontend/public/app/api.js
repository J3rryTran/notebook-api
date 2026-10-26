// frontend/public/app/api.js
// Quản lý xác thực và các hàm gọi API chuẩn cho Lakehouse Notebook

let authConfig = { mode: 'oidc' };
let oidcRedirecting = false;

function getCookie(name) {
  const match = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[2]) : null;
}

function redirectToOidcLogin() {
  if (oidcRedirecting) return;
  oidcRedirecting = true;
  const loginUrl = authConfig.loginUrl || '/api/v1/auth/oidc/login';
  const back = window.location.pathname + window.location.search + window.location.hash;
  window.location.assign(`${loginUrl}?redirect=${encodeURIComponent(back)}`);
}

async function api(path, opts = {}, retryOn401 = true) {
  const options = { ...opts, credentials: 'same-origin' };
  options.headers = { ...(options.headers || {}) };

  const method = (options.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') {
    const xsrf = getCookie('XSRF-TOKEN');
    if (xsrf) {
      options.headers['X-XSRF-TOKEN'] = xsrf;
    }
  }

  const res = await fetch(path, options);

  if (res.status === 401 && authConfig.mode === 'oidc' && retryOn401) {
    redirectToOidcLogin();
  }

  return res;
}

function showLoginModal(errorText = null) {
  const modal = document.getElementById('loginModal');
  const title = document.getElementById('loginTitle');
  const errorEl = document.getElementById('loginError');

  if (authConfig.mode === 'oidc') {
    title.innerHTML = '<i class="fa-solid fa-lock" style="color: var(--primary);"></i> Đăng nhập Lakehouse (OIDC)';
  } else if (authConfig.mode === 'ldap') {
    title.innerHTML = '<i class="fa-solid fa-lock" style="color: var(--primary);"></i> Đăng nhập LDAP Lakehouse';
  } else {
    title.innerHTML = '<i class="fa-solid fa-lock" style="color: var(--primary);"></i> Đăng nhập Notebook';
  }

  if (errorText) {
    errorEl.innerText = errorText;
    errorEl.style.display = 'block';
  } else {
    errorEl.style.display = 'none';
  }

  if (modal) modal.style.display = 'flex';
}

function hideLoginModal() {
  const modal = document.getElementById('loginModal');
  if (modal) modal.style.display = 'none';
}

async function checkAuthStatus(onAuthenticatedCallback) {
  try {
    const confRes = await api('/api/v1/auth/config', {}, false);
    if (confRes.ok) {
      authConfig = await confRes.json();
    }
  } catch (e) {
    console.warn('Không thể tải /auth/config:', e);
  }

  const params = new URLSearchParams(window.location.search);
  const authError = params.get('authError');
  if (authError) {
    params.delete('authError');
    const qs = params.toString();
    window.history.replaceState({}, document.title, window.location.pathname + (qs ? `?${qs}` : '') + window.location.hash);
  }

  try {
    const meRes = await api('/api/v1/me', {}, false);
    if (meRes.ok) {
      const user = await meRes.json();
      const userName = user.username || user.userId || 'User';
      const userEl = document.getElementById('userGreeting');
      if (userEl) userEl.innerText = `👤 ${userName}`;
      const logoutBtn = document.getElementById('logoutBtn');
      if (logoutBtn) logoutBtn.style.display = 'inline-flex';
      hideLoginModal();

      if (typeof onAuthenticatedCallback === 'function') {
        onAuthenticatedCallback(user);
      }
    } else if (meRes.status === 401) {
      if (authConfig.mode === 'oidc') {
        if (authError) {
          showOidcLoginModal(`Đăng nhập OIDC không thành công (${authError}).`);
        } else {
          redirectToOidcLogin();
        }
      } else {
        showLoginModal();
      }
    }
  } catch (err) {
    console.warn('Lỗi kiểm tra /me:', err);
    showLoginModal('Không thể kết nối đến máy chủ.');
  }
}

async function submitLogin() {
  const username = document.getElementById('usernameInput').value.trim();
  const password = document.getElementById('passwordInput').value;
  const errorEl = document.getElementById('loginError');

  if (!username || !password) {
    errorEl.innerText = 'Vui lòng nhập tên người dùng và mật khẩu.';
    errorEl.style.display = 'block';
    return;
  }

  try {
    const res = await api('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    }, false);

    if (res.ok) {
      hideLoginModal();
      window.location.reload();
    } else {
      errorEl.innerText = 'Đăng nhập thất bại. Vui lòng kiểm tra lại tài khoản và mật khẩu.';
      errorEl.style.display = 'block';
    }
  } catch (e) {
    errorEl.innerText = 'Lỗi kết nối tới máy chủ.';
    errorEl.style.display = 'block';
  }
}

function showOidcLoginModal(errorText) {
  showLoginModal(errorText);
  const form = document.getElementById('loginForm');
  if (form) form.style.display = 'none';
  const retry = document.getElementById('oidcRetryBtn');
  if (retry) retry.style.display = 'inline-flex';
  const pwBtn = document.getElementById('passwordLoginBtn');
  if (pwBtn) pwBtn.style.display = 'none';
}

async function handleLogout() {
  let endSessionUrl = null;
  try {
    const res = await api('/api/v1/auth/logout', { method: 'POST' }, false);
    if (res.ok) {
      const body = await res.json();
      endSessionUrl = body.endSessionUrl || null;
    }
  } catch (e) {
    console.warn('Lỗi đăng xuất:', e);
  }
  if (endSessionUrl) {
    window.location.assign(endSessionUrl);
  } else {
    window.location.reload();
  }
}
