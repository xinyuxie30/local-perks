let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
// 2s 对弱网和秒杀轮询来说太紧，统一放宽到 8s，个别接口可单独覆盖
axios.defaults.timeout = 8000;

// 登录态 token：每次请求现取，避免页面打开后重新登录仍带旧 token
var token = sessionStorage.getItem("token");

// request 拦截器，将用户 token 放入头中
axios.interceptors.request.use(
  config => {
    token = sessionStorage.getItem("token");
    if (token) {
      config.headers['authorization'] = token
    }
    return config
  },
  error => Promise.reject(error)
)

/**
 * 把 HTTP 错误翻译成用户能看懂的一句话。
 * 后端 WebExceptionAdvice 已对 400/403/405/413/429 返回结构化的 errorMsg，优先透传。
 */
function describeError(error) {
  if (!error || !error.response) {
    if (error && error.code === 'ECONNABORTED') {
      return "请求超时，请检查网络后重试";
    }
    return "网络异常，请稍后重试";
  }
  const res = error.response;
  const body = res.data || {};
  const message = body.errorMsg || body.message;
  switch (res.status) {
    case 400:
      return message || "请求参数不合法";
    case 401:
      return "请先登录";
    case 403:
      return message || "没有操作权限";
    case 404:
      return "接口不存在";
    case 405:
      return "请求方式不支持";
    case 413:
      return "文件太大，请压缩后重试";
    case 429: {
      const retry = parseInt(res.headers['retry-after'] || "1", 10);
      return `操作太频繁，请 ${retry} 秒后重试`;
    }
    default:
      return message || (res.status >= 500 ? "服务器开小差了，请稍后重试" : "请求失败");
  }
}

// response 拦截器：业务失败统一 reject(errorMsg 字符串)，方便页面直接 $message.error
axios.interceptors.response.use(function (response) {
  if (!response.data || response.data.success !== true) {
    return Promise.reject((response.data && response.data.errorMsg) || "请求失败");
  }
  return response.data;
}, function (error) {
  const status = error.response && error.response.status;
  if (status === 401) {
    // 记录来源页，登录后可以跳回来
    try {
      const here = location.pathname + location.search;
      if (!/login\.html/.test(here)) {
        sessionStorage.setItem("redirectAfterLogin", here);
      }
    } catch (e) {
      // 隐私模式下 sessionStorage 可能不可用，忽略
    }
    setTimeout(() => {
      location.href = "/login.html";
    }, 200);
    return Promise.reject("请先登录");
  }
  return Promise.reject(describeError(error));
});

// 只拼接有值的参数，且做 URL 编码（中文关键字必须编码，否则部分浏览器会截断）
axios.defaults.paramsSerializer = function (params) {
  const parts = [];
  Object.keys(params || {}).forEach(k => {
    const v = params[k];
    if (v === null || v === undefined || v === "") {
      return;
    }
    parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
  });
  return parts.join("&");
}

const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURIComponent(r[2]);
    }
    return "";
  },
  getToken() {
    return sessionStorage.getItem("token");
  },
  isLogin() {
    return !!sessionStorage.getItem("token");
  },
  /**
   * 分 -> 元。后端金额统一是「分」，前端展示统一两位小数。
   * 兼容数字与字符串（字符串允许 "80" / "80.5" / "80.50"）。
   */
  formatPrice(val) {
    if (val === null || val === undefined || val === "") {
      return "0.00";
    }
    let cents = typeof val === "number" ? val : parseInt(String(val).replace(/[^\d.-]/g, ""), 10);
    if (isNaN(cents)) {
      return "0.00";
    }
    const negative = cents < 0;
    cents = Math.abs(cents);
    const yuan = (cents / 100).toFixed(2);
    return negative ? "-" + yuan : yuan;
  },
  /** 十进制度数：37 -> 3.7 */
  formatScore(val) {
    const n = Number(val);
    return isNaN(n) ? 0 : Math.round(n / 10 * 10) / 10;
  },
  /** 折扣：8000/10000 -> 8.0 折，保留一位小数 */
  formatDiscount(payValue, actualValue) {
    const pay = Number(payValue);
    const actual = Number(actualValue);
    if (!actual || isNaN(pay) || isNaN(actual) || pay >= actual) {
      return "";
    }
    return (pay / actual * 10).toFixed(1) + "折";
  },
  /** ISO / 时间戳 / Date -> Date，兼容 "2026-10-18T01:18:20" 与 Safari */
  toDate(time) {
    if (time === null || time === undefined || time === "") {
      return null;
    }
    if (time instanceof Date) {
      return isNaN(time.getTime()) ? null : time;
    }
    if (typeof time === "number") {
      const d = new Date(time);
      return isNaN(d.getTime()) ? null : d;
    }
    // Safari 只认 "yyyy/MM/dd HH:mm:ss"，必须把 T 和 - 都换掉
    const raw = String(time).trim()
      .replace("T", " ")
      .replace(/-/g, "/")
      .replace(/\.\d+$/, "");
    const d = new Date(raw);
    return isNaN(d.getTime()) ? null : d;
  },
  /** ISO / 时间戳 -> yyyy-MM-dd HH:mm */
  formatTime(time, withSeconds) {
    const d = util.toDate(time);
    if (!d) {
      return time ? String(time) : "";
    }
    const pad = n => (n < 10 ? "0" + n : "" + n);
    let out = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate())
      + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    if (withSeconds) {
      out += ":" + pad(d.getSeconds());
    }
    return out;
  },
  /** 毫秒 -> mm:ss / hh:mm:ss */
  formatCountdown(ms) {
    if (ms <= 0) {
      return "00:00";
    }
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const pad = n => (n < 10 ? "0" + n : "" + n);
    return h > 0 ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
  },
  /** 图片加载失败兜底 */
  imgFallback(el, fallback) {
    try {
      el.target.onerror = null;
      el.target.src = fallback || "/imgs/icons/default-icon.png";
    } catch (e) {
      // 忽略
    }
  },
  /** 取图片列表第一张 */
  firstImage(images) {
    if (!images || typeof images !== "string") {
      return "";
    }
    return images.split(",")[0] || "";
  },
  debounce(fn, delay) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), delay || 300);
    };
  }
}
