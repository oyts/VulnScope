import axios from 'axios'

const http = axios.create({ baseURL: '/api/v1', timeout: 15000 })
http.interceptors.request.use(config => {
  const token = localStorage.getItem('vulnscope_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
http.interceptors.response.use(response => response.data, error => Promise.reject(error.response?.data || error))
export default http
