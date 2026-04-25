(() => {
  const Storage = {
    getToken() {
      return sessionStorage.getItem('token') || localStorage.getItem('token');
    },
    setToken(token, remember) {
      sessionStorage.removeItem('token');
      localStorage.removeItem('token');
      if (!token) return;
      if (remember) localStorage.setItem('token', token);
      else sessionStorage.setItem('token', token);
    },
    clearToken() {
      sessionStorage.removeItem('token');
      localStorage.removeItem('token');
    }
  };

  function qs(name) {
    const url = new URL(window.location.href);
    return url.searchParams.get(name);
  }

  function toast(msg) {
    const el = document.getElementById('toast');
    if (!el) return alert(msg);
    el.textContent = msg;
    el.style.display = 'block';
    clearTimeout(el._t);
    el._t = setTimeout(() => (el.style.display = 'none'), 2600);
  }

  function getApiBase() {
    const qp = qs('apiBase');
    if (qp) return String(qp).replace(/\/$/, '');
    if (window.location.protocol === 'file:') return 'http://127.0.0.1:8081';
    if (window.location.port === '8081') return window.location.origin;
    return window.location.origin + '/api';
  }

  const API_BASE = getApiBase();

  async function api(path, { method = 'GET', query, body, auth = true } = {}) {
    const url = new URL(API_BASE + path);
    if (query) Object.entries(query).forEach(([k, v]) => v !== undefined && v !== null && url.searchParams.set(k, v));
    const headers = { 'Content-Type': 'application/json' };
    if (auth) {
      const token = Storage.getToken();
      if (token) headers['authorization'] = token;
    }
    const res = await fetch(url.toString(), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (res.status === 401) {
      Storage.clearToken();
      throw new Error('请先登录');
    }
    const data = await res.json().catch(() => null);
    if (!data) throw new Error('服务端返回异常');
    if (!data.success) throw new Error(data.errorMsg || '请求失败');
    return data.data;
  }

  function requireLogin(nextUrl) {
    const token = Storage.getToken();
    if (!token) {
      const to = encodeURIComponent(nextUrl || window.location.pathname + window.location.search);
      window.location.href = 'login.html?next=' + to;
      return false;
    }
    return true;
  }

  window.RentalApp = { Storage, qs, toast, api, requireLogin };
})();
