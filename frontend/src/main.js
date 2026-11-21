import { createApp } from 'vue'
import {
  ElAside,
  ElButton,
  ElCarousel,
  ElCarouselItem,
  ElCol,
  ElContainer,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElInput,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElProgress,
  ElRow,
  ElScrollbar,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElUpload,
} from 'element-plus'
import 'element-plus/dist/index.css'
import router from "@/router/index.js";
import App from './App.vue'

const app = createApp(App)

const components = [
  ElAside,
  ElButton,
  ElCarousel,
  ElCarouselItem,
  ElCol,
  ElContainer,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElInput,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElProgress,
  ElRow,
  ElScrollbar,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElUpload,
]

components.forEach(component => app.component(component.name, component))
app.use(router)
app.mount('#app')

