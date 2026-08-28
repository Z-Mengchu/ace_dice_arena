(function () {
  'use strict';
  var registering = false;
  var form = document.getElementById('login-form');
  var mode = document.getElementById('login-mode');
  var error = document.getElementById('login-error');
  var query = new URLSearchParams(location.search);
  var requestedUsername = query.get('username');
  var requestedNext = query.get('next');

  function setMode(value) {
    registering = value;
    document.getElementById('login-title').textContent = value ? '创建入场账户' : '登录擂台';
    document.getElementById('login-hint').textContent = value ? '普通账户进入队伍大厅，参与本队限时投票、预言和掷骰。' : '系统将按管理员或玩家身份进入对应工作区。';
    document.getElementById('display-name-row').classList.toggle('hidden', !value);
    document.getElementById('department-row').classList.toggle('hidden', !value);
    document.getElementById('login-password').autocomplete = value ? 'new-password' : 'current-password';
    document.getElementById('login-submit').textContent = value ? '创建并入场' : '登录并入场';
    mode.textContent = value ? '已有账户？返回登录' : '第一次来？创建账户';
    error.classList.add('hidden');
  }

  function destination(user) {
    if (user.role === 'ADMIN') return '/admin';
    return requestedNext === '/lobby' ? '/lobby' : '/lobby';
  }

  if (requestedUsername) document.getElementById('login-username').value = requestedUsername;

  mode.onclick = function () { setMode(!registering); };
  fetch('/api/auth/config').then(function (response) { return response.json(); }).then(function (config) {
    if (!config.registrationEnabled) {
      setMode(false);
      mode.classList.add('hidden');
      document.getElementById('login-hint').textContent = '请输入组织用户表中的用户名，普通用户统一登录密码为 123456。';
    }
  }).catch(function () {});
  form.onsubmit = function (event) {
    event.preventDefault();
    var submit = document.getElementById('login-submit');
    var body = {
      username: document.getElementById('login-username').value.trim(),
      password: document.getElementById('login-password').value
    };
    if (registering) {
      body.displayName = document.getElementById('login-display-name').value.trim();
      body.department = document.getElementById('login-department').value.trim();
    }
    submit.disabled = true;
    submit.textContent = registering ? '正在创建…' : '正在排队进入…';
    error.classList.add('hidden');
    function send(attempt) {
      submit.textContent = registering ? '正在创建…' : (attempt ? '登录排队中 · 第 ' + (attempt + 1) + ' 次尝试' : '正在验证…');
      fetch(registering ? '/api/auth/register' : '/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      }).then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (data) {
          if (response.status === 429 && !registering) {
            var delay = (Number(data.retryAfterMs) || 800) + Math.floor(Math.random() * 500);
            error.textContent = '当前入场人数较多，系统正在自动排队，请不要重复点击。';
            error.classList.remove('hidden');
            setTimeout(function () { send(attempt + 1); }, delay);
            return;
          }
          if (!response.ok) throw new Error(data.error || '登录失败，请检查输入');
          location.replace(destination(data));
        });
      }).catch(function (err) {
        error.textContent = err.message;
        error.classList.remove('hidden');
        submit.disabled = false;
        submit.textContent = registering ? '创建并入场' : '登录并入场';
      });
    }
    // 同时扫码或打开页面的客户端先随机错峰，避免第一波请求在同一毫秒到达。
    setTimeout(function () { send(0); }, registering ? 0 : Math.floor(Math.random() * 700));
  };
})();
