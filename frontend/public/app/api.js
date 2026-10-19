// frontend/public/app/api.js
// Quản lý xác thực và các hàm gọi API chuẩn cho Lakehouse Notebook

let authConfig = { mode: 'oidc' };
let userManager = null;
let currentUser = null;

function getCookie(name) {
  const match = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[2]) : null;
}

function oidcRedirectPath() {
  const p = authConfig.redirectPath;
  return typeof p === 'string' && p.startsWith('/') && !p.startsWith('//') ? p : '/auth/callback';
}

function initOidcUserManager() {
  if (!window.oidc || !authConfig || authConfig.mode !== 'oidc') return null;
  if (!userManager && authConfig.issuer && authConfig.clientId) {
    const scopes = Array.isArray(authConfig.scopes) && authConfig.scopes.length > 0
      ? authConfig.scopes.join(' ')
      : 'openid profile';

    userManager = new oidc.UserManager({
      authority: authConfig.issuer,
      client_id: authConfig.clientId,
      redirect_uri: window.location.origin + oidcRedirectPath(),
      post_logout_redirect_uri: window.location.origin + '/',
      response_type: 'code',
      scope: scopes,
      automaticSilentRenew: true,
      userStore: new oidc.WebStorageStateStore({ store: window.sessionStorage })
    });

    userManager.events.addUserLoaded(user => {
      window.currentUser = user;
      currentUser = user;
    });
    userManager.events.addUserUnloaded(() => {
      window.currentUser = null;
      currentUser = null;
    });
  }
  return userManager;
}

async function api(path, opts = {}, retryOn401 = true) {
  const options = { ...opts };
  options.headers = { ...(options.headers || {}) };

  const method = (options.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') {
    const xsrf = getCookie('XSRF-TOKEN');
    if (xsrf) {
      options.headers['X-XSRF-TOKEN'] = xsrf;
    }
  }

  const tokenUser = window.currentUser || currentUser;
  if (tokenUser && tokenUser.access_token) {
    options.headers['Authorization'] = `Bearer ${tokenUser.access_token}`;
  }

  let res = await fetch(path, options);

  if (res.status === 401 && authConfig.mode === 'oidc' && retryOn401 && userManager) {
    try {
      const renewedUser = await userManager.signinSilent();
      if (renewedUser) {
        window.currentUser = renewedUser;
        currentUser = renewedUser;
        options.headers['Authorization'] = `Bearer ${renewedUser.access_token}`;
        return await fetch(path, options);
      }
    } catch (silentErr) {
      console.warn('signinSilent thất bại:', silentErr);
    }

    if (!sessionStorage.getItem('oidc_redirect_attempt')) {
      sessionStorage.setItem('oidc_redirect_attempt', '1');
      await userManager.signinRedirect();
    } else {
      console.error('Xác thực thất bại: Đã thử redirect OIDC.');
      showLoginModal('Phiên đăng nhập OIDC hết hạn hoặc không hợp lệ. Vui lòng tải lại trang.');
    }
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

  if (authConfig.mode === 'oidc') {
    initOidcUserManager();

    if (window.location.pathname === oidcRedirectPath() && userManager) {
      try {
        const user = await userManager.signinRedirectCallback();
        window.currentUser = user;
        currentUser = user;
        sessionStorage.removeItem('oidc_redirect_attempt');
        window.history.replaceState({}, document.title, '/');
      } catch (cbErr) {
        console.error('Lỗi xử lý callback OIDC:', cbErr);
        showLoginModal('Không thể hoàn tất đăng nhập OIDC: ' + (cbErr.message || cbErr));
        return;
      }
    }

    if (userManager && !window.currentUser) {
      try {
        const storedUser = await userManager.getUser();
        if (storedUser && !storedUser.expired) {
          window.currentUser = storedUser;
          currentUser = storedUser;
        }
      } catch (e) {
        console.warn('Không thể đọc user từ session storage:', e);
      }
    }
  }

  try {
    const meRes = await api('/api/v1/me', {}, false);
    if (meRes.ok) {
      sessionStorage.removeItem('oidc_redirect_attempt');
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
      if (authConfig.mode === 'oidc' && userManager) {
        if (!sessionStorage.getItem('oidc_redirect_attempt')) {
          sessionStorage.setItem('oidc_redirect_attempt', '1');
          await userManager.signinRedirect();
        } else {
          showLoginModal('Phiên đăng nhập OIDC hết hạn hoặc không hợp lệ.');
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

async function handleLogout() {
  if (authConfig.mode === 'oidc' && userManager) {
    sessionStorage.removeItem('oidc_redirect_attempt');
    await userManager.signoutRedirect();
  } else {
    await api('/api/v1/auth/logout', { method: 'POST' }, false);
    window.location.reload();
  }
}
