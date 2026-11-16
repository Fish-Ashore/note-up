<script setup>
/**
 * 用户管理页。
 * 路由：/admin/users（admin-users），仅管理员可访问（由全局路由守卫校验）。
 * 数据：GET /admin/users 获取用户列表；PATCH /admin/users/:id 修改角色或启用状态。
 * 权限规则：注册用户固定为 GUEST，只有管理员能调整角色（GUEST / ADMIN）或禁用账号；
 *           接口失败时会重新拉取列表，避免界面与后端状态不一致。
 */
import { onMounted, ref } from 'vue'
import { api } from '../../api/client'
import { formatDateTime } from '../../utils/format'
import { useAuthStore } from '../../stores/auth'

// 用于标记“当前登录”用户，防止误操作自己的账号时缺少提示。
const auth = useAuthStore()
const users = ref([])
const loading = ref(true)
const error = ref('')
// 正在提交修改的用户 ID，用于禁用该行控件并阻止并发请求。
const busyId = ref(null)

// 拉取全部用户；失败时展示错误信息。
async function load() {
  loading.value = true
  error.value = ''
  try {
    users.value = await api.get('/admin/users')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 提交单个用户的局部更新（patch 只包含变更字段），成功后原地替换该行数据。
async function update(user, patch) {
  if (busyId.value) {
    return
  }
  busyId.value = user.id
  error.value = ''
  try {
    const updated = await api.patch(`/admin/users/${user.id}`, patch)
    // 仅替换对应行，避免整表重新加载导致的滚动位置与焦点丢失。
    const index = users.value.findIndex((item) => item.id === user.id)
    if (index >= 0) {
      users.value[index] = updated
    }
  } catch (e) {
    error.value = e.message
    // 控件可能已在本地先行变化，失败后重新拉取以回滚到服务端真实状态。
    await load()
  } finally {
    busyId.value = null
  }
}

// 角色下拉框变更：仅在值确实变化时提交，避免无意义请求。
function changeRole(user, event) {
  const role = event.target.value
  if (role !== user.role) {
    update(user, { role })
  }
}

// 切换启用 / 禁用状态，取反后提交。
function toggleEnabled(user) {
  update(user, { enabled: !user.enabled })
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <!-- 页头：说明角色分配与禁用规则 -->
    <header class="admin-page-head">
      <div>
        <p class="eyebrow">People</p>
        <h1>用户管理</h1>
        <p class="muted">注册用户固定为 GUEST，只有管理员可以调整角色或禁用账号。</p>
      </div>
    </header>

    <!-- 错误与加载状态 -->
    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>

    <!-- 用户列表：角色下拉与启用开关均即时提交 -->
    <div v-else class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>用户名</th>
            <th>角色</th>
            <th>状态</th>
            <th>注册时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td class="muted">{{ user.id }}</td>
            <td>
              <strong>{{ user.username }}</strong>
              <!-- 标记当前登录账号，便于区分自己的记录 -->
              <span v-if="user.id === auth.user?.id" class="badge badge-muted">当前登录</span>
            </td>
            <td>
              <!-- 角色变更通过 change 事件提交，提交期间禁用下拉框 -->
              <select
                class="select"
                :value="user.role"
                :disabled="busyId === user.id"
                @change="changeRole(user, $event)"
              >
                <option value="GUEST">GUEST</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </td>
            <td>
              <span class="status" :class="user.enabled ? 'status-approved' : 'status-rejected'">
                {{ user.enabled ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="muted small">{{ formatDateTime(user.createdAt) }}</td>
            <td>
              <!-- 按钮文案随启用状态切换，点击提交相反状态 -->
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="busyId === user.id"
                @click="toggleEnabled(user)"
              >
                {{ user.enabled ? '禁用' : '启用' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>