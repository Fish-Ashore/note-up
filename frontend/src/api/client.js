/**
 * HTTP 客户端封装：统一处理 baseURL、token 注入、JSON 序列化与响应解析。
 * 数据流：业务层调用 api.get/post/... → request 拼接 BASE 并发起 fetch
 *        → 优先解析 JSON，失败时保留纯文本 → 非 2xx 统一抛 Error（带 status）。
 * 依赖：localStorage 持久化 token；VITE_API_BASE 可通过环境变量覆盖默认 /api。
 */
// 开发环境走 Vite 代理的 /api，部署时可用环境变量指向独立后端
const BASE = import.meta.env.VITE_API_BASE || '/api'
// localStorage 键名集中定义，避免各处硬编码导致读写不一致
const TOKEN_KEY = 'noteup_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  // 传入空值视为登出，直接移除键而不是存空字符串
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/**
 * 通用请求方法。
 * @param {string} path 以 / 开头的接口路径，会拼接到 BASE 之后
 * @param {object} options method、body、auth（是否需要携带 token）
 */
async function request(path, { method = 'GET', body, auth = true } = {}) {
  const headers = {}
  // 仅在有 body 时声明 JSON，避免 GET 请求带上无意义的 Content-Type
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  // 登录/注册等接口显式传 auth: false，避免携带过期 token 干扰后端判断
  if (auth && token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  // 先读文本再尝试解析：后端可能返回空响应或非 JSON 错误页
  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      // 非 JSON 内容原样返回，交由调用方按字符串处理
      data = text
    }
  }
  if (!response.ok) {
    // 统一错误形态：优先使用后端 message，否则回退到带状态码的提示
    const error = new Error(data?.message || `请求失败 (${response.status})`)
    error.status = response.status
    throw error
  }
  return data
}

// 对外的 HTTP 方法集合，保持 request 的 options 透传能力
export const api = {
  get(path, options) {
    return request(path, options)
  },
  post(path, body, options = {}) {
    return request(path, { ...options, method: 'POST', body })
  },
  patch(path, body, options = {}) {
    return request(path, { ...options, method: 'PATCH', body })
  },
  delete(path, options = {}) {
    return request(path, { ...options, method: 'DELETE' })
  }
}

/**
 * 将对象转换为查询串；空字符串、null、undefined 会被跳过，
 * 避免出现 ?q=&page= 这类无意义参数影响后端缓存或校验。
 */
export function queryString(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}