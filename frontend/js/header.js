// =============================================================================
// 文件职责：页面顶部导航栏组件（hmdp-header）。
// 本文件定义一个全局 Vue 组件，负责渲染 PC 端页面顶部的 Header 区域，
// 包含：左侧站点 Logo「黑马点评」、中间的主导航（首页/店铺/发布笔记/智能问答）、
// 右侧的店铺搜索框与用户入口（登录/个人中心）。
// 父页面通过 prop active 传入当前页面的导航标识（home/shops/publish/rag），
// 用于高亮当前所在的导航项。
// 使用方式：页面引入 Vue、Element UI 与本文件后，
// 在 HTML 中书写 <hmdp-header active="home"></hmdp-header> 即可完成挂载。
// =============================================================================

// 通过 Vue.component 把组件注册为全局组件（而非某个实例内的局部组件），
// 这样所有页面无需局部注册，直接使用 <hmdp-header> 标签即可。
// 组件名采用短横线写法 hmdp-header，符合自定义元素的命名规范。
Vue.component('hmdp-header', {
  // 组件模板（字符串模板，由 Vue 编译为虚拟 DOM）。
  // 整体结构分为四个区域，外层由 .hmdp-header 包裹、.hmdp-header__inner 控制内边距：
  // 1) .hmdp-logo：站点 Logo，点击回首页；
  // 2) .hmdp-nav：主导航，四个菜单项都通过 :class="navClass(...)" 生成样式类，
  //    其中「发布笔记」是写操作入口，用 protectedGo 做登录保护，其余用 go 直接跳转；
  // 3) .hmdp-header__search：搜索框，用 Element UI 的 el-input 组件，
  //    v-model 双向绑定 keyword，回车（keyup.enter.native）触发 search 方法；
  // 4) .hmdp-user-entry：用户入口，根据登录态显示「个人中心」或「登录」。
  // aria-label="主导航" 用于无障碍访问，方便读屏软件识别该导航区域。
  template: `
    <header class="hmdp-header">
      <div class="hmdp-header__inner">
        <div class="hmdp-logo" @click="go('/')">黑马点评</div>
        <nav class="hmdp-nav" aria-label="主导航">
          <div :class="navClass('home')" @click="go('/')">首页</div>
          <div :class="navClass('shops')" @click="go('/shop-list.html')">店铺</div>
          <div :class="navClass('publish')" @click="protectedGo('/blog-edit.html')">发布笔记</div>
          <div :class="navClass('rag')" @click="go('/rag.html')">智能问答</div>
        </nav>
        <div class="hmdp-header__search">
          <el-input v-model="keyword" size="small" placeholder="搜索店铺名称或地点" @keyup.enter.native="search">
            <i slot="prefix" class="el-input__icon el-icon-search"></i>
          </el-input>
        </div>
        <div class="hmdp-user-entry" @click="openProfile">
          <i class="el-icon-user"></i><span>{{ loggedIn ? '个人中心' : '登录' }}</span>
        </div>
      </div>
    </header>
  `,
  // 父组件传入的 props 列表。
  props: {
    // 当前页面对应的导航标识：home=首页，shops=店铺，publish=发布笔记，rag=智能问答。
    // 不传时默认空字符串，表示当前页面不属于导航中的任何一项（都不高亮）。
    // 声明类型为 String 并给出默认值，避免父组件未传时的 undefined 警告。
    active: {type: String, default: ''}
  },
  // 组件的响应式数据。
  data() {
    return {keyword: '', loggedIn: Boolean(token)};
    // keyword：搜索框的双向绑定值，配合 el-input 的 v-model 使用；
    // loggedIn：登录态标记。token 是 common.js 中定义的全局变量，
    // 取自 sessionStorage，非空即视为已登录；用 Boolean() 显式转为布尔值。
  },
  // 组件的方法集合。
  methods: {
    /** 生成导航项的样式类：固定基类 hmdp-nav__item + 命中当前页时追加 is-active */
    // 返回数组形式是为了让 Vue 同时绑定静态类名与条件类名；
    // 对象里的 is-active 是否生效取决于 active prop 是否与传入的 name 相等。
    navClass(name) {
      return ['hmdp-nav__item', {'is-active': this.active === name}];
    },
    /** 普通跳转方法：不做任何登录校验，直接整页跳转到目标路径 */
    // 本项目是多页面应用（MPA），没有前端路由，
    // 因此跳转通过修改 location.href 触发整页刷新加载目标页面。
    go(path) {
      location.href = path;
    },
    /** 带登录保护的跳转方法：用于「发布笔记」这类需要登录才能使用的入口 */
    // 已登录（token 存在）时直接前往目标页；
    // 未登录则先跳到登录页，由登录页在登录成功后再回跳。
    protectedGo(path) {
      location.href = token ? path : '/login.html';
    },
    /** 店铺搜索方法：由搜索框的回车事件触发 */
    // 先对输入做 trim 去除首尾空白，输入为空则不跳转；
    // 非空则携带 keyword 参数跳转到店铺列表页，
    // encodeURIComponent 对关键字做 URL 编码，防止中文与特殊字符破坏 URL 结构。
    search() {
      const keyword = this.keyword.trim();
      if (keyword) location.href = '/shop-list.html?keyword=' + encodeURIComponent(keyword);
    },
    /** 用户入口点击方法：根据登录态决定去个人中心还是登录页 */
    // 与 protectedGo 类似的登录保护逻辑：
    // 已登录进入个人信息页（info.html），未登录先去登录页（login.html）。
    // 页面上显示的文案（个人中心/登录）由模板中的 loggedIn 三元表达式同步切换。
    openProfile() {
      location.href = token ? '/info.html' : '/login.html';
    }
  }
});

// 组件定义到此结束。
// 注意：本组件依赖 common.js 提供的全局变量 token（sessionStorage 中的登录令牌），
// 因此页面引入顺序必须保证 common.js 在本文件之前加载。
