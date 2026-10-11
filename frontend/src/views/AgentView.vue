<script setup>
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Bot, Braces, Database, Sparkles } from 'lucide-vue-next'
import { askAgent, getCapabilities } from '../api/agent'

const suggestions = ['当前整体风险态势如何？', '严重度分布中哪一类最多？', 'Maven 高风险组件有哪些？', '风险流向的最大路径是什么？']
const question = ref(suggestions[0])
const capabilities = ref(null)
const result = ref(null)
const loading = ref(false)
const error = ref('')

const modeLabel = computed(() => ({
  llm_grounded: '大模型证据增强',
  offline_rules: '离线规则',
  offline_fallback: '离线回退'
})[result.value?.mode || capabilities.value?.mode] || '检测中')

async function loadCapabilities() {
  try { capabilities.value = await getCapabilities() }
  catch { error.value = 'Agent 服务未启动，请先运行 agent-service。' }
}
async function ask(text) {
  if (text) question.value = text
  if (!question.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try { result.value = await askAgent(question.value.trim()) }
  catch (e) { error.value = e.detail || e.message || 'Agent 查询失败' }
  finally { loading.value = false }
}
onMounted(loadCapabilities)
</script>

<template>
  <div class="page agent-page">
    <p class="eyebrow">EVIDENCE-GROUNDED SECURITY AGENT</p>
    <h1 class="page-title">离线 Agent</h1>
    <div class="agent-intro">
      <p>用自然语言查询 Spark 回写后的真实统计结果。未配置模型密钥时，系统自动使用确定性规则，不伪装成大模型回答。</p>
      <div class="mode-card"><span>当前模式</span><strong>{{ modeLabel }}</strong><small>{{ capabilities?.model || '无需 API 密钥' }}</small></div>
    </div>

    <section class="ask-panel">
      <div class="ask-mark"><Bot :size="28"/><span>ASK</span></div>
      <div class="composer">
        <label for="agent-question">向风险分析助手提问</label>
        <textarea id="agent-question" v-model="question" rows="3" maxlength="500" @keydown.ctrl.enter="ask()" />
        <div class="composer-foot"><small>Ctrl + Enter 发送 · 回答始终附带来源与证据</small><button class="btn primary" :disabled="loading" @click="ask()">{{ loading ? '查询中…' : '查询真实数据' }}<ArrowRight :size="16"/></button></div>
      </div>
    </section>

    <div class="suggestions"><button v-for="item in suggestions" :key="item" @click="ask(item)">{{ item }}</button></div>
    <div v-if="error" class="agent-error">{{ error }}</div>

    <section v-if="result" class="answer-grid">
      <article class="answer-card">
        <div class="answer-meta"><span><Sparkles :size="15"/>{{ modeLabel }}</span><span>{{ result.model_used ? result.model_name : 'MODEL NOT USED' }}</span></div>
        <h2>分析结论</h2><p class="answer-text">{{ result.answer }}</p><p class="notice">{{ result.notice }}</p>
      </article>
      <aside class="evidence-card">
        <div><Database :size="18"/><span>数据接口</span></div><code>{{ result.source_endpoint }}</code>
        <div><Braces :size="18"/><span>证据 {{ result.evidence.length }} 条</span></div>
        <pre>{{ JSON.stringify(result.evidence, null, 2) }}</pre>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.agent-page{max-width:1450px}.agent-intro{display:grid;grid-template-columns:minmax(0,1fr) 260px;border-top:1px solid #111;margin-top:34px}.agent-intro>p{font-size:18px;line-height:1.7;margin:0;padding:28px 32px 28px 0;max-width:800px}.mode-card{border-left:1px solid #111;padding:20px;display:grid;align-content:center;gap:5px}.mode-card span,.mode-card small{font-size:11px;color:#6a6a70;letter-spacing:.08em}.mode-card strong{font-size:22px}.ask-panel{border:1px solid #111;display:grid;grid-template-columns:120px 1fr;margin-top:26px}.ask-mark{background:#111;color:#fff;display:grid;place-items:center;align-content:center;gap:10px}.ask-mark svg{color:var(--red)}.ask-mark span{font-size:11px;letter-spacing:.16em}.composer{padding:22px}.composer label{display:block;font-weight:700;margin-bottom:10px}.composer textarea{width:100%;resize:vertical;border:0;border-bottom:1px solid #aaa;padding:10px 0;font:inherit;font-size:19px;line-height:1.5;outline:none}.composer textarea:focus{border-color:var(--red)}.composer-foot{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:16px}.composer-foot small{color:#777}.composer-foot button{display:flex;align-items:center;gap:9px}.composer-foot button:disabled{opacity:.5}.suggestions{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0 34px}.suggestions button{border:1px solid var(--line);background:#fff;padding:8px 11px;font-size:12px}.suggestions button:hover{border-color:#111}.agent-error{border:1px solid var(--red);color:var(--red);padding:16px;margin:22px 0}.answer-grid{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(320px,1fr);border:1px solid #111}.answer-card{padding:28px}.answer-meta{display:flex;justify-content:space-between;border-bottom:1px solid var(--line);padding-bottom:12px;font-size:11px;letter-spacing:.08em}.answer-meta span{display:flex;align-items:center;gap:7px}.answer-meta svg{color:var(--red)}.answer-card h2{font-size:14px;margin:30px 0 12px;text-transform:uppercase}.answer-text{font-size:21px;line-height:1.75;margin:0}.notice{color:#666;font-size:12px;margin:28px 0 0;border-left:3px solid var(--red);padding-left:12px}.evidence-card{border-left:1px solid #111;background:var(--soft);padding:22px;min-width:0}.evidence-card>div{display:flex;gap:8px;align-items:center;font-size:12px;font-weight:700;margin:0 0 8px}.evidence-card code{display:block;color:var(--red);font-size:11px;overflow-wrap:anywhere;margin-bottom:24px}.evidence-card pre{max-height:330px;overflow:auto;background:#111;color:#f5f5f5;padding:15px;font-size:11px;line-height:1.5;white-space:pre-wrap}.evidence-card svg{color:var(--red)}@media(max-width:850px){.agent-intro,.answer-grid{grid-template-columns:1fr}.mode-card,.evidence-card{border-left:0;border-top:1px solid #111}.ask-panel{grid-template-columns:64px 1fr}.composer-foot{align-items:flex-start;flex-direction:column}}
</style>
