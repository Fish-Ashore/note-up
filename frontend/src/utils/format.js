/**
 * 展示层格式化工具，纯函数、无副作用，供卡片、详情与评论区复用。
 * 约定：空值返回空串；无法解析的时间原样返回，避免页面出现 Invalid Date。
 */

export function formatDate(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  // 后端时间格式异常时退化为原始字符串，保证信息不丢失
  if (Number.isNaN(date.getTime())) {
    return value
  }
  // 固定 zh-CN 与两位数月日，输出稳定不随浏览器语言变化
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

export function formatDateTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  // 评论场景需要精确到分钟，便于体现讨论时间线
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

export function tagUrl(name) {
  // 标签可能含空格、中文或特殊符号，必须编码后再拼进查询串
  return `/tags?name=${encodeURIComponent(name)}`
}