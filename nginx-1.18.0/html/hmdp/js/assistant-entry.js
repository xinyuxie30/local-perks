/**
 * 智能助手悬浮入口：任何业务页面右下角注入「🤖 智能助手」按钮。
 * - 不注入 chat.html 自身，避免遮挡输入区
 * - 页面上已有 chat.html 入口时不重复注入
 * - 商铺详情页自动带上 shopId，点进去助手直接介绍该店
 */
(function () {
  if (/\/chat\.html$/i.test(location.pathname)) { return; }
  if (document.getElementById('csEntryBtn')) { return; }
  if (document.querySelector('a[href^="/chat.html"]')) { return; }

  function entryUrl() {
    var m = location.pathname.match(/shop-detail\.html$/i);
    if (m) {
      var id = new URLSearchParams(location.search).get('id');
      if (id) { return '/chat.html?shopId=' + encodeURIComponent(id); }
    }
    return '/chat.html';
  }

  function mount() {
    var a = document.createElement('a');
    a.id = 'csEntryBtn';
    a.href = entryUrl();
    a.setAttribute('title', '打开智能助手：找店、挑券、下单、售后');
    a.setAttribute('aria-label', '智能助手');
    a.innerHTML = '<span style="font-size:22px;line-height:1">🤖</span>';
    // 圆形 FAB：不遮挡列表文字，窄屏也放得下
    a.style.cssText = [
      'position:fixed', 'right:14px', 'bottom:88px', 'z-index:999',
      'width:52px', 'height:52px', 'border-radius:50%',
      'display:flex', 'align-items:center', 'justify-content:center',
      'background:linear-gradient(135deg,#ff8a4c,#ff5a1f)', 'color:#fff',
      'text-decoration:none', 'box-shadow:0 6px 16px rgba(255,90,31,.38)',
      'transition:transform .18s ease,box-shadow .18s ease',
      '-webkit-tap-highlight-color:transparent'
    ].join(';');
    a.addEventListener('mouseenter', function () {
      a.style.transform = 'translateY(-2px) scale(1.04)';
      a.style.boxShadow = '0 9px 20px rgba(255,90,31,.48)';
    });
    a.addEventListener('mouseleave', function () {
      a.style.transform = '';
      a.style.boxShadow = '0 6px 16px rgba(255,90,31,.38)';
    });
    document.body.appendChild(a);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();
