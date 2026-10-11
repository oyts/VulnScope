import axios from 'axios'

const agent = axios.create({ baseURL: '/agent-api', timeout: 45000 })
agent.interceptors.response.use(response => response.data, error => Promise.reject(error.response?.data || error))

export const getCapabilities = () => agent.get('/capabilities')
export const askAgent = question => agent.post('/ask', { question })
