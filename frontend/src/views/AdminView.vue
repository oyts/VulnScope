<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { CheckCircle2, Database, Play, RefreshCw, Server, UploadCloud, Users, XCircle } from 'lucide-vue-next'
import http from '../api/http'

const tab = ref('tasks')
const users = ref([])
const sources = ref([])
const tasks = ref([])
const error = ref('')
const message = ref('')
const submitting = ref(false)
const dimension = ref('ALL')
let timer

const activeTask = computed(() => tasks.value.find(item => ['PENDING', 'RUNNING'].includes(item.status)))
const stageNames = {
  QUEUED: '任务排队', UPLOADING_JAR: '上传 Spark JAR', HDFS_CHECK: '检查 HDFS 数据',
  YARN_SUBMITTED: '提交 YARN', SPARK_RUNNING: 'Spark 分布式计算',
  DIMENSION_D1: '写回 D1 趋势', DIMENSION_D2: '写回 D2 严重度', DIMENSION_D3: '写回 D3 CWE',
  DIMENSION_D4: '写回 D4 组件风险', DIMENSION_D5: '写回 D5 散点', DIMENSION_D6: '写回 D6 风险流',
  SUCCEEDED: 'MySQL 回写验收通过', FAILED: '执行失败'
}

async function loadAll() {
  try {
    const [u, s, t] = await Promise.all([http.get('/admin/users'), http.get('/admin/data-sources'), http.get('/admin/analysis-tasks')])
    users.value = u.data; sources.value = s.data; tasks.value = t.data
  } catch (e) { error.value = e.message || '加载失败' }
}
async function loadTasks() {
  try { tasks.value = (await http.get('/admin/analysis-tasks')).data }
  catch (e) { error.value = e.message || '任务状态刷新失败' }
}
async function submitSpark() {
  if (activeTask.value || submitting.value) return
  submitting.value = true; error.value = ''; message.value = ''
  try {
    const response = await http.post('/admin/analysis-tasks', { dimensionCode: dimension.value })
    message.value = `${response.message}：${response.data.task_no}`
    await loadTasks()
  } catch (e) { error.value = e.message || 'Spark 任务提交失败' }
  finally { submitting.value = false }
}
async function toggle(user) { await http.patch(`/admin/users/${user.id}/status`, { status: user.status ? 0 : 1 }); loadAll() }
function stageName(stage) { return stageNames[stage] || stage || '等待执行' }
function statusClass(status) { return status === 'SUCCEEDED' ? 'green' : status === 'FAILED' ? 'red' : '' }

onMounted(() => { loadAll(); timer = window.setInterval(loadTasks, 3000) })
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="page">
    <p class="eyebrow">ADMIN GOVERNANCE</p><h1 class="page-title">系统治理</h1>
    <div class="admin-metrics"><div><Server/><strong>03</strong><span>YARN 节点</span></div><div><Database/><strong>{{ sources.length }}</strong><span>公开数据源</span></div><div><Play/><strong>{{ tasks.length }}</strong><span>Spark 任务</span></div><div><Users/><strong>{{ users.length }}</strong><span>系统用户</span></div></div>
    <div class="tabs"><button :class="{active:tab==='tasks'}" @click="tab='tasks'">分析任务</button><button :class="{active:tab==='sources'}" @click="tab='sources'">数据源</button><button :class="{active:tab==='users'}" @click="tab='users'">用户管理</button></div>
    <div v-if="error" class="feedback error"><XCircle :size="17"/>{{ error }}</div><div v-if="message" class="feedback success"><CheckCircle2 :size="17"/>{{ message }}</div>

    <template v-if="tab==='tasks'">
      <section class="submit-card">
        <div class="submit-icon"><UploadCloud :size="28"/></div>
        <div class="submit-copy"><p class="eyebrow">REAL SPARK ON YARN PIPELINE</p><h2>提交 Spark 复算任务</h2><p>后端上传最新作业 JAR 到 node1，读取 HDFS 全量数据，通过 YARN 执行 Spark，再将六维结果覆盖写回 Windows MySQL。</p></div>
        <label class="dimension">分析维度<select v-model="dimension" :disabled="!!activeTask"><option value="ALL">ALL · 六维全量</option><option v-for="n in 6" :key="n" :value="`D${n}`">D{{ n }} · 单维复算</option></select></label>
        <button class="btn primary submit-btn" :disabled="!!activeTask || submitting" @click="submitSpark"><Play :size="16"/>{{ activeTask ? '已有任务运行中' : submitting ? '正在提交…' : '启动真实 Spark 作业' }}</button>
      </section>
      <section v-if="activeTask" class="running-card">
        <div><RefreshCw class="spin" :size="18"/><strong>{{ stageName(activeTask.stage) }}</strong><span>{{ activeTask.progress || 0 }}%</span></div>
        <progress :value="activeTask.progress || 0" max="100"/><small>{{ activeTask.task_no }} · 页面每 3 秒读取 MySQL 任务状态</small>
      </section>
      <div class="panel task-table"><table class="data-table"><thead><tr><th>任务编号 / HDFS</th><th>维度</th><th>执行阶段</th><th>进度</th><th>YARN Application</th><th>源数据 / 结果</th><th>完成时间</th></tr></thead><tbody><tr v-for="row in tasks" :key="row.id"><td><strong>{{ row.task_no }}</strong><small>{{ row.hdfs_input_path || '—' }}</small></td><td>{{ row.dimension_code }}</td><td><span :class="['status',statusClass(row.status)]">{{ row.status }}</span><small>{{ stageName(row.stage) }}</small><em v-if="row.error_message">{{ row.error_message }}</em></td><td>{{ row.progress ?? 0 }}%</td><td><code>{{ row.application_id || '等待 YARN 分配' }}</code></td><td>{{ row.source_rows || '—' }} / {{ row.result_rows || '—' }}</td><td>{{ row.finished_at || '—' }}</td></tr><tr v-if="!tasks.length"><td colspan="7" class="muted">暂无任务记录</td></tr></tbody></table></div>
    </template>
    <div v-else class="panel"><table v-if="tab==='sources'" class="data-table"><thead><tr><th>代码</th><th>数据源</th><th>HDFS 路径</th><th>记录数</th><th>状态</th></tr></thead><tbody><tr v-for="row in sources" :key="row.id"><td>{{ row.source_code }}</td><td>{{ row.source_name }}</td><td>{{ row.hdfs_path || '待同步' }}</td><td>{{ row.record_count }}</td><td><span class="status">{{ row.status }}</span></td></tr></tbody></table><table v-if="tab==='users'" class="data-table"><thead><tr><th>用户名</th><th>显示名称</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in users" :key="row.id"><td>{{ row.username }}</td><td>{{ row.display_name }}</td><td>{{ row.role }}</td><td>{{ row.status ? '正常' : '禁用' }}</td><td>{{ row.created_at }}</td><td><button v-if="row.role==='USER'" class="btn" @click="toggle(row)">{{ row.status ? '禁用' : '恢复' }}</button></td></tr></tbody></table></div>
  </div>
</template>

<style scoped>
.admin-metrics{display:grid;grid-template-columns:repeat(4,1fr);margin-top:35px;border-left:1px solid #111;border-top:1px solid #111}.admin-metrics div{min-height:150px;padding:20px;border-right:1px solid #111;border-bottom:1px solid #111;display:grid;grid-template-columns:1fr auto;align-items:start}.admin-metrics svg{color:var(--red)}.admin-metrics strong{font-size:60px;letter-spacing:-.06em}.admin-metrics span{grid-column:1/-1;align-self:end;color:#666}.tabs{margin-top:35px;display:flex;border-bottom:1px solid #111}.tabs button{border:0;background:#fff;padding:13px 20px}.tabs button.active{background:#111;color:#fff}.feedback{margin-top:18px;border:1px solid currentColor;padding:12px 15px;display:flex;align-items:center;gap:8px}.feedback.error{color:var(--red)}.feedback.success{color:#087f5b}.submit-card{display:grid;grid-template-columns:80px minmax(300px,1fr) 180px 220px;align-items:center;border:1px solid #111;margin:24px 0 12px}.submit-icon{height:100%;display:grid;place-items:center;background:#111;color:var(--red)}.submit-copy{padding:20px}.submit-copy h2{margin:0 0 8px}.submit-copy p:last-child{margin:0;color:#666;line-height:1.5;font-size:13px}.dimension{display:grid;gap:7px;font-size:12px;font-weight:700}.dimension select{height:42px;border:1px solid #111;background:#fff;padding:0 8px}.submit-btn{margin:0 18px;display:flex;justify-content:center;align-items:center;gap:8px}.submit-btn:disabled{opacity:.45;cursor:not-allowed}.running-card{border:1px solid #111;padding:16px;margin-bottom:12px}.running-card>div{display:grid;grid-template-columns:24px 1fr auto;align-items:center}.running-card progress{width:100%;height:7px;margin:13px 0 6px;accent-color:var(--red)}.running-card small{color:#666}.spin{animation:spin 1.5s linear infinite;color:var(--red)}@keyframes spin{to{transform:rotate(360deg)}}.task-table{overflow:auto}.task-table td strong,.task-table td small,.task-table td em{display:block}.task-table td small{color:#777;margin-top:5px;font-size:11px}.task-table td em{color:var(--red);font-size:11px;margin-top:5px;max-width:260px}.task-table code{font-size:11px;white-space:nowrap}@media(max-width:1050px){.submit-card{grid-template-columns:70px 1fr}.dimension,.submit-btn{margin:0 18px 18px;grid-column:2}.dimension{max-width:220px}}@media(max-width:800px){.admin-metrics{grid-template-columns:repeat(2,1fr)}}
</style>
