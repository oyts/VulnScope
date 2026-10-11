<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, Bot, FileSearch, LayoutDashboard, LogIn, LogOut, Search, ShieldCheck, Users } from 'lucide-vue-next'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore(); const route = useRoute(); const router = useRouter()
const nav = computed(() => [
  {to:'/',label:'风险态势',icon:LayoutDashboard},
  {to:'/vulnerabilities',label:'漏洞检索',icon:Search,auth:true},
  {to:'/scan',label:'依赖扫描',icon:FileSearch,auth:true},
  {to:'/agent',label:'离线 Agent',icon:Bot,auth:true},
  {to:'/admin',label:'系统治理',icon:Users,admin:true}
].filter(item => (!item.auth || auth.isLoggedIn) && (!item.admin || auth.isAdmin)))
function logout(){ auth.logout(); router.push('/') }
</script>

<template>
  <div class="shell">
    <header class="masthead">
      <RouterLink to="/" class="brand"><ShieldCheck :size="25"/><strong>VulnScope</strong></RouterLink>
      <div class="cluster"><Activity :size="15"/><span>HDFS · YARN · SPARK</span></div>
      <div class="account" v-if="auth.isLoggedIn"><span>{{ auth.user.displayName }}</span><small>{{ auth.user.role }}</small><button @click="logout" aria-label="退出"><LogOut :size="17"/></button></div>
      <RouterLink v-else class="login-link" to="/login"><LogIn :size="17"/>登录</RouterLink>
    </header>
    <div class="body-grid">
      <aside class="sidebar">
        <RouterLink v-for="(item,index) in nav" :key="item.to" :to="item.to" :class="['nav-item',{active:route.path===item.to}]">
          <span class="nav-no">0{{ index+1 }}</span><component :is="item.icon" :size="18"/><span>{{ item.label }}</span>
        </RouterLink>
        <div class="sidebar-note"><span>DATASET</span><strong>OSV MAVEN</strong><small>真实公开数据</small></div>
      </aside>
      <main><RouterView/></main>
    </div>
  </div>
</template>

<style scoped>
.shell{min-height:100vh}.masthead{height:68px;border-bottom:1px solid #111;display:grid;grid-template-columns:260px 1fr auto;align-items:center}.brand{height:100%;display:flex;align-items:center;gap:11px;padding:0 22px;border-right:1px solid #111;font-size:20px}.brand svg{color:var(--red)}.cluster{display:flex;align-items:center;gap:8px;padding:0 22px;color:#555;font-size:11px;letter-spacing:.12em}.account{height:100%;display:grid;grid-template-columns:auto auto 48px;align-items:center;border-left:1px solid #111}.account span{padding-left:18px;font-weight:700}.account small{padding:0 14px;color:var(--red)}.account button{height:100%;width:48px;border:0;border-left:1px solid #111;background:#fff}.login-link{height:100%;padding:0 22px;border-left:1px solid #111;display:flex;align-items:center;gap:8px}.body-grid{display:grid;grid-template-columns:260px minmax(0,1fr);min-height:calc(100vh - 68px)}.sidebar{border-right:1px solid #111;display:flex;flex-direction:column}.nav-item{height:66px;border-bottom:1px solid var(--line);display:grid;grid-template-columns:36px 24px 1fr;align-items:center;padding:0 18px}.nav-item:hover,.nav-item.active{background:#111;color:#fff}.nav-item.active .nav-no{color:var(--red)}.nav-no{font-size:11px;color:#777}.sidebar-note{margin-top:auto;border-top:1px solid #111;padding:20px;display:grid;gap:5px}.sidebar-note span{font-size:10px;color:#777;letter-spacing:.1em}.sidebar-note small{color:#777}main{min-width:0}@media(max-width:900px){.masthead{grid-template-columns:1fr auto}.cluster{display:none}.body-grid{grid-template-columns:72px 1fr}.sidebar .nav-item{grid-template-columns:1fr;justify-items:center;padding:0}.nav-item span:last-child,.nav-no,.sidebar-note{display:none}.brand{border-right:0}.account span,.account small{display:none}}
</style>
