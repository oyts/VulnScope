import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('vulnscope_token') || '',
    user: JSON.parse(localStorage.getItem('vulnscope_user') || 'null')
  }),
  getters: { isAdmin: state => state.user?.role === 'ADMIN', isLoggedIn: state => Boolean(state.token) },
  actions: {
    async login(payload) {
      const response = await http.post('/auth/login', payload)
      this.token = response.data.token; this.user = response.data.user
      localStorage.setItem('vulnscope_token', this.token)
      localStorage.setItem('vulnscope_user', JSON.stringify(this.user))
    },
    async register(payload) { return http.post('/auth/register', payload) },
    logout() {
      this.token = ''; this.user = null
      localStorage.removeItem('vulnscope_token'); localStorage.removeItem('vulnscope_user')
    }
  }
})
