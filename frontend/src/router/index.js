import { createRouter, createWebHashHistory } from "vue-router"

const Configuration = () => import("@/components/Configuration.vue")
const BugList = () => import("@/components/BugList.vue")
const BugView = () => import("@/components/BugView.vue")
const RunList = () => import("@/components/RunList.vue")
const RunView = () => import("@/components/RunView.vue")

const routes = [
  { path: '/', component: Configuration },
  { path: '/configuration', component: Configuration },
  { path: '/runs', component: RunList },
  { path: '/bugs', component: BugList },
  { path: '/bug_view/:bug_id', component: BugView, props: true },
  { path: '/run_view/:run_id', component: RunView, props: true }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export default router
