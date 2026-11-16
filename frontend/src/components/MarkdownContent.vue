<script setup>
/**
 * Markdown 渲染增强组件：接收后端渲染好的 HTML，挂载后按需增强为
 * 代码复制按钮、Mermaid 图表、highlight.js 高亮与 KaTeX 公式。
 * 数据流：父组件传入 html → v-html 渲染 → enhance 在 DOM 上做后处理；
 *        html 变化时重新执行，但通过 lastHtml 跳过无意义重复处理。
 * 依赖：highlight.js、mermaid、katex 均为动态 import，未用到时不会增加首屏体积。
 */
import { nextTick, onMounted, ref, watch } from 'vue'
import 'highlight.js/styles/github-dark.css'

const props = defineProps({
  html: {
    type: String,
    default: ''
  }
})

const root = ref(null)
// 记录上次增强过的 HTML，避免同一内容（如仅父组件重渲染）重复挂载按钮
let lastHtml = ''

async function copyText(text) {
  // 安全上下文且支持 Clipboard API 时优先使用，失败再走兼容方案
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  // 非 HTTPS 或旧浏览器下的降级方案：临时 textarea + execCommand
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  // 固定定位并透明，防止插入时页面抖动或闪烁
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

function addCopyButton(pre) {
  // 幂等：重复增强时已存在按钮就不再叠加
  if (pre.querySelector('.code-copy-btn')) {
    return
  }
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'code-copy-btn'
  button.textContent = '复制'
  button.addEventListener('click', async () => {
    // 只复制 code 内文本，排除按钮自身文字
    const code = pre.querySelector('code')
    const text = code ? code.textContent : pre.textContent
    try {
      await copyText(text)
      button.textContent = '已复制'
      button.classList.add('is-copied')
    } catch (error) {
      console.error('Copy failed', error)
      button.textContent = '复制失败'
    }
    // 1.6 秒后恢复初始文案，给用户明确的操作反馈
    window.setTimeout(() => {
      button.textContent = '复制'
      button.classList.remove('is-copied')
    }, 1600)
  })
  pre.appendChild(button)
}

async function enhance() {
  // 等 v-html 完成 DOM 更新后再查询节点
  await nextTick()
  const element = root.value
  if (!element) {
    return
  }
  const html = props.html || ''
  if (html === lastHtml) {
    return
  }
  lastHtml = html

  // Mermaid：先找出代码块，替换为容器后统一渲染
  const mermaidNodes = [...element.querySelectorAll('code.language-mermaid, code.lang-mermaid')]
  if (mermaidNodes.length) {
    try {
      const mermaid = (await import('mermaid')).default
      mermaid.initialize({
        startOnLoad: false,
        theme: 'base',
        // strict 模式过滤脚本与事件，降低不可信 Markdown 的 XSS 风险
        securityLevel: 'strict',
        fontFamily: 'system-ui, sans-serif',
        themeVariables: {
          primaryColor: '#eef2ff',
          primaryBorderColor: '#6366f1',
          lineColor: '#64748b'
        }
      })
      for (const code of mermaidNodes) {
        const pre = code.closest('pre')
        const container = document.createElement('div')
        container.className = 'mermaid'
        // 用 textContent 写入源码，避免被当作 HTML 解析
        container.textContent = code.textContent
        if (pre) {
          pre.replaceWith(container)
        } else {
          code.replaceWith(container)
        }
      }
      await mermaid.run({ nodes: element.querySelectorAll('.mermaid') })
    } catch (error) {
      // 单张图失败不影响正文其他增强，仅在控制台记录
      console.error('Mermaid render failed', error)
    }
  }

  // 代码高亮：排除 Mermaid 源码块，它们已被替换或不应高亮
  const codeNodes = [...element.querySelectorAll('pre code')]
    .filter((node) => !node.classList.contains('language-mermaid') && !node.classList.contains('lang-mermaid'))
  if (codeNodes.length) {
    try {
      // 只引入 common 语言包，平衡体积与覆盖率
      const hljs = (await import('highlight.js/lib/common')).default
      codeNodes.forEach((node) => hljs.highlightElement(node))
    } catch (error) {
      console.error('Highlight render failed', error)
    }
  }

  // 为普通代码块添加复制按钮（Mermaid 容器内不添加）
  const preNodes = [...element.querySelectorAll('pre')]
    .filter((node) => !node.closest('.mermaid'))
  preNodes.forEach((pre) => addCopyButton(pre))

  // 仅当内容可能含公式时才加载 KaTeX，避免无谓的 CSS/JS 请求
  if (html.includes('$')) {
    try {
      await import('katex/dist/katex.min.css')
      const renderMath = (await import('katex/contrib/auto-render')).default
      renderMath(element, {
        delimiters: [
          { left: '$$', right: '$$', display: true },
          { left: '$', right: '$', display: false },
          { left: '\\(', right: '\\)', display: false },
          { left: '\\[', right: '\\]', display: true }
        ],
        // 公式语法错误时不抛异常，保留原文展示
        throwOnError: false,
        // 跳过代码与脚本标签，防止误渲染代码示例中的 $ 符号
        ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code']
      })
    } catch (error) {
      console.error('KaTeX render failed', error)
    }
  }
}

onMounted(enhance)
watch(() => props.html, enhance)
</script>

<template>
  <!-- html 由后端 Markdown 渲染，前端只负责增强，不再二次解析 -->
  <div ref="root" class="markdown-body" v-html="html"></div>
</template>