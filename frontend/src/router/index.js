import { createRouter, createWebHistory } from 'vue-router'

// Who uses which screen:
//   /station    the monitor facing the queue (staff browser, logged in)
//   /dashboard  the organiser's laptop (staff browser, logged in)
//   /p/:token   each attendee's phone (opened from their personal QR)
//   /about      public info page, linked from the phone page
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/about' },
    { path: '/station', component: () => import('@/views/StationView.vue') },
    { path: '/dashboard', component: () => import('@/views/DashboardView.vue') },
    { path: '/p/:token', component: () => import('@/views/PersonalView.vue'), props: true },
    { path: '/about', component: () => import('@/views/AboutView.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue') },
  ],
})

export default router
