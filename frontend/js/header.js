Vue.component('hmdp-header', {
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
  props: {
    active: {type: String, default: ''}
  },
  data() {
    return {keyword: '', loggedIn: Boolean(token)};
  },
  methods: {
    navClass(name) {
      return ['hmdp-nav__item', {'is-active': this.active === name}];
    },
    go(path) {
      location.href = path;
    },
    protectedGo(path) {
      location.href = token ? path : '/login.html';
    },
    search() {
      const keyword = this.keyword.trim();
      if (keyword) location.href = '/shop-list.html?keyword=' + encodeURIComponent(keyword);
    },
    openProfile() {
      location.href = token ? '/info.html' : '/login.html';
    }
  }
});
