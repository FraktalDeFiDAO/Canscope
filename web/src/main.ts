import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import './style.css'
import App from './App.vue'
import DashboardView from './views/DashboardView.vue'
import PidExplorerView from './views/PidExplorerView.vue'
import SnifferView from './views/SnifferView.vue'
import TransmitView from './views/TransmitView.vue'
import DtcView from './views/DtcView.vue'
import SessionsView from './views/SessionsView.vue'
import ConnectionView from './views/ConnectionView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/pids', name: 'pids', component: PidExplorerView },
    { path: '/sniffer', name: 'sniffer', component: SnifferView },
    { path: '/transmit', name: 'transmit', component: TransmitView },
    { path: '/dtc', name: 'dtc', component: DtcView },
    { path: '/sessions', name: 'sessions', component: SessionsView },
    { path: '/connection', name: 'connection', component: ConnectionView },
  ],
})

createApp(App).use(createPinia()).use(router).mount('#app')
