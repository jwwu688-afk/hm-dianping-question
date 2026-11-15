// =============================================================================
// 文件职责：页面底部导航栏组件（footBar）。
// 本文件定义一个全局 Vue 组件，负责渲染移动端页面底部的 Footer 导航区域。
// 组件共包含 5 个入口：首页、店铺、中间的「发布笔记」大按钮、智能问答、我的。
// 当前应高亮的菜单编号由父页面通过 prop activeBtn 传入（1~4）；
// 编号 0 对应中间的发布按钮，它不参与高亮，点击后直接跳转到笔记编辑页。
// 使用方式：页面引入 Vue、Element UI 与本文件后，
// 在 HTML 中书写 <foot-bar :active-btn="n"></foot-bar> 即可完成挂载。
// =============================================================================

// 通过 Vue.component 把组件注册为全局组件（而非某个实例内的局部组件），
// 这样所有页面无需再局部注册，直接使用 <foot-bar> 标签即可。
// 组件名采用驼峰写法 footBar，在模板中对应短横线写法 <foot-bar>。
Vue.component("footBar", {
  // 组件模板（字符串模板，由 Vue 编译为虚拟 DOM）。
  // 整体结构：一个 .foot 容器内部横向排列 5 个 .foot-box 单元格：
  // 1) 首页：el-icon-s-home 房子图标 + 文字「首页」；
  // 2) 店铺：el-icon-map-location 地图定位图标 + 文字「店铺」；
  // 3) 发布：中间凸出的 add.png 大按钮，不是图标+文字而是整张图片；
  // 4) 智能问答：el-icon-chat-dot-round 对话气泡图标 + 文字「智能问答」；
  // 5) 我的：el-icon-user 用户图标 + 文字「我的」。
  // 前两个及后两个入口均用 :class="{active: activeBtn === n}" 控制高亮，
  // 点击事件统一交给 methods 中的 toPage 方法处理；
  // 发布按钮的图片若加载失败，会触发 useFallbackImage 换成默认占位图。
  template: `
    <div class="foot">
    <div class="foot-box" :class="{active: activeBtn === 1}" @click="toPage(1)">
      <div class="foot-view"><i class="el-icon-s-home"></i></div>
      <div class="foot-text">首页</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 2}" @click="toPage(2)">
      <div class="foot-view"><i class="el-icon-map-location"></i></div>
      <div class="foot-text">店铺</div>
    </div>
    <div class="foot-box" @click="toPage(0)">
      <img @error="useFallbackImage" class="add-btn" src="/imgs/add.png" alt="">
    </div>
    <div class="foot-box" :class="{active: activeBtn === 3}" @click="toPage(3)">
      <div class="foot-view"><i class="el-icon-chat-dot-round"></i></div>
      <div class="foot-text">智能问答</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 4}" @click="toPage(4)">
      <div class="foot-view"><i class="el-icon-user"></i></div>
      <div class="foot-text">我的</div>
    </div>
  </div>
  `,
  // 组件的响应式数据。
  // 说明：Vue 的 data 必须是一个返回对象的函数（而不是直接写对象），
  // 这样每个组件实例才能拿到独立的数据副本。
  // 本组件 purely 由外部 prop 驱动，自身不维护任何内部状态，
  // 因此 data 返回空对象，仅保留结构以便日后扩展。
  data() {
    return {
    }
  },
  // 父组件传入的 props 列表：activeBtn 表示当前页面对应的菜单编号。
  // 约定：1=首页，2=店铺，3=智能问答，4=我的；0 为发布按钮，不参与高亮。
  // 父页面通过 :active-btn="'n'" 的形式传入，Vue 会自动做驼峰映射。
  props: ['activeBtn'],
  // 组件的方法集合。
  methods: {
    /** 底部导航的统一跳转方法，根据菜单编号决定跳往哪个页面 */
    // 本项目是多页面应用（MPA），没有前端路由，
    // 因此跳转直接通过修改 location.href 触发整页刷新加载目标页面。
    // 各编号与目标页面的对应关系如下：
    // 0=发布笔记(blog-edit) 1=首页 2=店铺列表 3=智能问答(rag) 4=个人信息(info)
    toPage(i) {
      // 编号 0：中间的「发布笔记」按钮，进入笔记编辑页（登录校验由编辑页自行处理）
      if (i === 0) {
        location.href = "/blog-edit.html"
      // 编号 4：底部「我的」入口，进入个人信息页
      } else if (i === 4) {
        location.href = "/info.html"
      // 编号 3：底部「智能问答」入口，进入 RAG 智能问答页
      } else if (i === 3) {
        location.href = "/rag.html"
      // 编号 2：底部「店铺」入口，进入店铺列表页
      } else if (i === 2) {
        location.href = "/shop-list.html"
      // 编号 1：底部「首页」入口，回到网站首页
      } else if (i === 1){
        location.href = "/"
      }
      // 编号不在以上枚举范围内时不做任何跳转，保持当前页面不变
      // （这是兜底逻辑，避免非法编号导致页面跳到空白地址）
    }
  }
})

// 组件定义到此结束。
// 补充说明：模板中引用的 useFallbackImage 方法并未在本文件定义，
// 它由 common.js 中的全局 Vue.mixin 注入到每一个组件实例上，
// 因此这里可以直接在模板中使用，用于图片加载失败时降级为默认占位图。
