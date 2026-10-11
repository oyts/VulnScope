import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import LoginView from '../views/LoginView.vue'
import ScanView from '../views/ScanView.vue'
import VulnerabilityView from '../views/VulnerabilityView.vue'
import AdminView from '../views/AdminView.vue'
import AgentView from '../views/AgentView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: DashboardView },
    { path: '/login', component: LoginView },
    { path: '/scan', component: ScanView, meta: { auth: true } },
    { path: '/agent', component: AgentView, meta: { auth: true } },
    { path: '/vulnerabilities', component: VulnerabilityView, meta: { auth: true } },
    { path: '/admin', component: AdminView, meta: { auth: true, admin: true } }
  ]
})

router.beforeEach(to => {
  const token = localStorage.getItem('vulnscope_token')
  const user = JSON.parse(localStorage.getItem('vulnscope_user') || 'null')
  if (to.meta.auth && !token) return '/login'
  if (to.meta.admin && user?.role !== 'ADMIN') return '/'
})
export default router
