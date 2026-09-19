# 黑马点评响应式网页改版实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将黑马点评全部前端页面迁入 Git 仓库并改造成电脑端优先、手机端兼容的响应式网站。

**Architecture:** 保留 Vue 2、Element UI、Axios 和多页面结构，在 `frontend/hmdp` 建立统一顶部导航、手机底部导航、响应式容器和公共组件。Nginx 直接托管仓库中的前端目录，所有 API 继续经 `/api` 转发到 Java，RAG 再由 Java 调用 Python 服务。

**Tech Stack:** HTML5、CSS3、Vue 2、Element UI、Axios、Nginx、Python `unittest` 静态校验、Node.js 语法校验

**Spec:** `docs/superpowers/specs/2026-09-19-responsive-web-redesign-design.md`

## Global Constraints

- 前端正式源码必须位于 `D:\code\hm-dianping\frontend\hmdp` 并纳入当前 Git 仓库。
- 不引入 Vue 3、React、npm 依赖、打包器或新的前端构建流程。
- 保留现有 Java API、Python RAG API、登录 token 和 `/api` Nginx 代理协议。
- 品牌主色使用 `#ff6633`，桌面内容最大宽度为 `1200px`。
- `>=1024px` 使用完整桌面布局，`768px～1023px` 使用紧凑布局，`<768px` 使用手机单栏和底部导航。
- 公共 CSS 类使用 `hmdp-` 前缀，避免覆盖 Element UI 内部类。
- 浏览器不得直接提交 `owner_id` 或直接调用 Python `8003`。

## Review Focus

- 未登录用户：页面可浏览；上传、全站问答、发布和个人中心操作必须提示登录并跳转 `/login.html`。在 Task 2 与 Task 7 的测试中固定此行为。
- 超长店名、笔记标题和回答：卡片不能撑破网格，正文允许换行，页面不得出现横向滚动。在 Task 3、Task 4 与 Task 7 中检查。
- 图片地址为空或加载失败：必须切换到 `/imgs/icons/default-icon.png` 或页面规定的默认图。在 Task 2 与 Task 4 中检查。
- 慢请求和失败请求：按钮禁用、显示加载状态，失败后恢复可操作状态并显示错误。在 Task 3～Task 7 中逐页检查。
- `375×812` 手机视口：桌面导航隐藏、底部导航显示、双栏变单栏且底部内容不被遮挡。在 Task 2 和 Task 8 中检查。

---

### Task 1: 将前端源码纳入仓库并建立静态验证

**Files:**
- Create: `frontend/hmdp/**`（从 `D:\code\nginx-1.18.0\html\hmdp` 复制现有页面、CSS、JS、字体和图片）
- Create: `frontend/tests/test_frontend_structure.py`
- Create: `deploy/nginx/nginx.conf`
- Modify: `docs/RAG-使用说明.md`

**Interfaces:**
- Consumes: 当前 Nginx 前端目录和现有 `/api` 代理配置。
- Produces: Git 跟踪的 `frontend/hmdp` 静态站点和统一验证命令。

- [ ] **Step 1: 写入会失败的前端结构测试**

创建 `frontend/tests/test_frontend_structure.py`：

```python
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "hmdp"
PAGES = [
    "index.html", "shop-list.html", "shop-detail.html", "blog-detail.html",
    "blog-edit.html", "login.html", "login2.html", "info.html",
    "info-edit.html", "other-info.html", "rag.html",
]

class FrontendStructureTests(unittest.TestCase):
    def test_all_pages_are_versioned(self):
        self.assertEqual([], [name for name in PAGES if not (ROOT / name).is_file()])

    def test_local_static_references_exist(self):
        missing = []
        for name in PAGES:
            text = (ROOT / name).read_text(encoding="utf-8")
            for value in re.findall(r'(?:src|href)=["\']([^"\']+)', text):
                if value.startswith(("http:", "https:", "//", "#", "/api")) or "{{" in value:
                    continue
                path = ROOT / value.lstrip("./") if not value.startswith("/") else ROOT / value.lstrip("/")
                if not path.exists():
                    missing.append(f"{name}: {value}")
        self.assertEqual([], missing)

if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: FAIL，缺少 `frontend/hmdp` 下的页面。

- [ ] **Step 3: 复制前端并创建受版本控制的 Nginx 配置**

PowerShell：

```powershell
New-Item -ItemType Directory -Force D:\code\hm-dianping\frontend\hmdp
Copy-Item D:\code\nginx-1.18.0\html\hmdp\* D:\code\hm-dianping\frontend\hmdp -Recurse -Force
```

创建完整的 `deploy/nginx/nginx.conf`：

```nginx
worker_processes 1;

events {
    worker_connections 1024;
}

http {
    include mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;

    server {
        listen 8080;
        server_name localhost;

        location / {
            root D:/code/hm-dianping/frontend/hmdp;
            index index.html;
            try_files $uri $uri/ /index.html;
        }

        location /api {
            default_type application/json;
            proxy_http_version 1.1;
            rewrite /api(/.*) $1 break;
            proxy_pass_request_headers on;
            proxy_next_upstream error timeout;
            proxy_pass http://127.0.0.1:8081;
        }
    }
}
```

在 `docs/RAG-使用说明.md` 中把前端源码位置写为 `frontend/hmdp`，并加入将配置复制到 Nginx 后执行 `nginx -t` 的步骤。

- [ ] **Step 4: 验证结构和 Nginx 配置**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: 2 tests PASS。

Run: `Copy-Item deploy\nginx\nginx.conf D:\code\nginx-1.18.0\conf\nginx.conf -Force`

Expected: 本机 Nginx 配置被项目模板更新。

Run: `D:\code\nginx-1.18.0\nginx.exe -t -p D:\code\nginx-1.18.0\`

Expected: `syntax is ok` 和 `test is successful`。

- [ ] **Step 5: 提交迁移结果**

```bash
git add frontend deploy/nginx/nginx.conf docs/RAG-使用说明.md
git commit -m "feat: track the hmdp frontend in the application repo"
```

### Task 2: 建立公共响应式页面骨架

**Files:**
- Create: `frontend/hmdp/css/desktop.css`
- Create: `frontend/hmdp/css/components.css`
- Create: `frontend/hmdp/js/header.js`
- Modify: `frontend/hmdp/js/footer.js`
- Modify: `frontend/hmdp/js/common.js`
- Modify: `frontend/hmdp/*.html`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: Task 1 的全部页面和静态验证。
- Produces: Vue 组件 `<hmdp-header active="...">`、`<foot-bar :active-btn="...">` 和 `.hmdp-container` 页面容器。

- [ ] **Step 1: 增加公共骨架失败测试**

在测试中加入：

```python
def test_business_pages_load_shared_responsive_shell(self):
    for name in PAGES:
        text = (ROOT / name).read_text(encoding="utf-8")
        self.assertIn("./css/desktop.css", text, name)
        self.assertIn("./css/components.css", text, name)
        self.assertIn("./js/header.js", text, name)
        self.assertIn("<hmdp-header", text, name)

def test_shared_css_defines_all_breakpoints(self):
    css = (ROOT / "css" / "desktop.css").read_text(encoding="utf-8")
    self.assertIn("max-width: 1200px", css)
    self.assertRegex(css, r"@media\s*\(max-width:\s*1023px\)")
    self.assertRegex(css, r"@media\s*\(max-width:\s*767px\)")
```

- [ ] **Step 2: 运行测试并确认缺少公共骨架**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: 新增的两个测试 FAIL。

- [ ] **Step 3: 实现公共头部和导航行为**

`header.js` 注册 `hmdp-header`，属性为 `active`，模板包含 Logo、首页、店铺、发布笔记、智能问答、搜索框和用户入口。方法使用固定路由：

```javascript
go(path) { location.href = path },
search() {
  const keyword = this.keyword.trim();
  if (keyword) location.href = '/shop-list.html?keyword=' + encodeURIComponent(keyword);
},
openProfile() {
  location.href = token ? '/info.html' : '/login.html';
}
```

所有图片增加 `@error="useFallbackImage"`；在 `common.js` 的 `util` 中增加：

```javascript
useFallbackImage(event) {
  event.target.onerror = null;
  event.target.src = '/imgs/icons/default-icon.png';
}
```

- [ ] **Step 4: 实现公共 CSS 和手机导航**

`desktop.css` 必须定义 `.hmdp-shell`、`.hmdp-container`、`.hmdp-grid`、`.hmdp-main`、`.hmdp-sidebar`。桌面隐藏 `.foot`；`max-width:767px` 时隐藏 `.hmdp-header`、显示 `.foot`、将网格设为一列并为底部导航预留 `72px`。

`components.css` 定义 `.hmdp-card`、`.hmdp-button-primary`、`.hmdp-empty`、`.hmdp-loading`、文本省略和图片比例类。

更新 `footer.js`，手机入口依次为首页、店铺、发布、智能问答、我的，所有路由均有实现。

- [ ] **Step 5: 将公共资源接入全部页面并验证**

每个页面在 Element UI 后加载 `desktop.css` 和 `components.css`，根节点内第一个组件为 `<hmdp-header>`，脚本末尾加载 `header.js` 和 `footer.js`。

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: 全部 PASS。

- [ ] **Step 6: 提交公共骨架**

```bash
git add frontend/hmdp/css frontend/hmdp/js frontend/hmdp/*.html frontend/tests
git commit -m "feat: add shared responsive frontend shell"
```

### Task 3: 改造首页与登录页面

**Files:**
- Modify: `frontend/hmdp/index.html`
- Modify: `frontend/hmdp/css/index.css`
- Modify: `frontend/hmdp/login.html`
- Modify: `frontend/hmdp/login2.html`
- Modify: `frontend/hmdp/css/login.css`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: `<hmdp-header>`、`.hmdp-container`、`.hmdp-card` 和现有 Axios 实例。
- Produces: 桌面首页内容流、推荐侧栏和响应式登录布局。

- [ ] **Step 1: 写首页和登录页结构测试**

```python
def test_home_and_login_have_desktop_landmarks(self):
    home = (ROOT / "index.html").read_text(encoding="utf-8")
    login = (ROOT / "login.html").read_text(encoding="utf-8")
    self.assertIn('class="hmdp-home-grid"', home)
    self.assertIn('class="hmdp-home-sidebar"', home)
    self.assertIn('class="hmdp-login-brand"', login)
    self.assertIn('class="hmdp-login-form"', login)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure.FrontendStructureTests.test_home_and_login_have_desktop_landmarks -v`

Expected: FAIL，缺少桌面布局标记。

- [ ] **Step 3: 实现首页桌面布局**

首页使用 `.hmdp-home-grid` 两栏：主栏保留分类与探店流，侧栏显示“热门推荐”和登录状态。笔记列表桌面为两列卡片，`max-width:767px` 降为单列。店名和标题使用两行截断，加载失败图片调用 `util.useFallbackImage`。

- [ ] **Step 4: 实现两套登录页面响应式布局**

两套登录页统一使用 `.hmdp-login-layout`，左侧 `.hmdp-login-brand` 显示品牌说明，右侧 `.hmdp-login-form` 保留原验证码、登录请求和 token 保存逻辑。手机断点隐藏品牌说明并将表单设为全宽。

- [ ] **Step 5: 验证请求失败会恢复按钮**

确保登录和首页加载方法全部在 `finally` 中恢复 `loading`；首页空数据展示 `.hmdp-empty`。

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: PASS。

- [ ] **Step 6: 提交首页和登录页**

```bash
git add frontend/hmdp/index.html frontend/hmdp/login*.html frontend/hmdp/css/index.css frontend/hmdp/css/login.css frontend/tests
git commit -m "feat: redesign home and login pages"
```

### Task 4: 改造店铺列表和详情页

**Files:**
- Modify: `frontend/hmdp/shop-list.html`
- Modify: `frontend/hmdp/css/shop-list.css`
- Modify: `frontend/hmdp/shop-detail.html`
- Modify: `frontend/hmdp/css/shop-detail.css`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: URL 参数工具、店铺 Java API 和公共卡片组件。
- Produces: `.hmdp-shop-grid`、`.hmdp-shop-detail-grid` 以及 `askShop(shopId)` 跳转行为。

- [ ] **Step 1: 写店铺页面失败测试**

```python
def test_shop_pages_have_desktop_grid_and_ai_entry(self):
    listing = (ROOT / "shop-list.html").read_text(encoding="utf-8")
    detail = (ROOT / "shop-detail.html").read_text(encoding="utf-8")
    self.assertIn('class="hmdp-shop-grid"', listing)
    self.assertIn('class="hmdp-shop-detail-grid"', detail)
    self.assertIn("/rag.html?shopId=", detail)
    self.assertIn("询问 AI", detail)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure.FrontendStructureTests.test_shop_pages_have_desktop_grid_and_ai_entry -v`

Expected: FAIL。

- [ ] **Step 3: 实现店铺列表筛选与网格**

桌面左栏放类型、商圈和排序，右栏 `.hmdp-shop-grid` 使用三列卡片；平板两列；手机一列并把筛选收进可展开区域。保留现有分页或滚动加载调用，每次请求用 `loading` 防止重复触发。

- [ ] **Step 4: 实现店铺详情双栏与 AI 跳转**

主栏保留图片、评分、地址和评价；侧栏放营业时间、价格、优惠券和操作。新增：

```javascript
askShop() {
  location.href = '/rag.html?shopId=' + encodeURIComponent(this.shop.id);
}
```

按钮文本为“询问 AI：这家店怎么样”。图片失败时使用统一回退图；长地址允许换行。

- [ ] **Step 5: 运行完整静态测试并提交**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: PASS。

```bash
git add frontend/hmdp/shop-*.html frontend/hmdp/css/shop-*.css frontend/tests
git commit -m "feat: redesign shop discovery and detail pages"
```

### Task 5: 改造探店笔记页面

**Files:**
- Modify: `frontend/hmdp/blog-detail.html`
- Modify: `frontend/hmdp/css/blog-detail.css`
- Modify: `frontend/hmdp/blog-edit.html`
- Modify: `frontend/hmdp/css/blog-edit.css`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: 笔记查询、点赞、发布和图片上传接口。
- Produces: `.hmdp-blog-layout` 和 `.hmdp-editor-card`。

- [ ] **Step 1: 写笔记布局失败测试**

```python
def test_blog_pages_have_reader_and_editor_layouts(self):
    detail = (ROOT / "blog-detail.html").read_text(encoding="utf-8")
    editor = (ROOT / "blog-edit.html").read_text(encoding="utf-8")
    self.assertIn('class="hmdp-blog-layout"', detail)
    self.assertIn('class="hmdp-blog-author-card"', detail)
    self.assertIn('class="hmdp-editor-card"', editor)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure.FrontendStructureTests.test_blog_pages_have_reader_and_editor_layouts -v`

Expected: FAIL。

- [ ] **Step 3: 实现笔记阅读双栏**

主栏展示图片、标题和可换行正文，侧栏展示作者、点赞和相关推荐。手机端作者卡移到正文前。超长正文使用 `overflow-wrap:anywhere`，图片宽度限制为容器宽度。

- [ ] **Step 4: 实现发布表单卡片**

标题、正文、店铺选择和图片上传分组；发布按钮提交期间禁用，成功后按原行为跳转，失败后保留用户输入并恢复按钮。

- [ ] **Step 5: 验证并提交**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: PASS。

```bash
git add frontend/hmdp/blog-*.html frontend/hmdp/css/blog-*.css frontend/tests
git commit -m "feat: redesign blog reader and editor pages"
```

### Task 6: 改造个人资料相关页面

**Files:**
- Modify: `frontend/hmdp/info.html`
- Modify: `frontend/hmdp/info-edit.html`
- Modify: `frontend/hmdp/other-info.html`
- Modify: `frontend/hmdp/css/info.css`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: 用户资料、关注、用户笔记和退出登录接口。
- Produces: `.hmdp-profile-layout`、`.hmdp-profile-card` 和 `.hmdp-profile-content`。

- [ ] **Step 1: 写个人页面布局失败测试**

```python
def test_profile_pages_share_responsive_profile_layout(self):
    for name in ("info.html", "info-edit.html", "other-info.html"):
        text = (ROOT / name).read_text(encoding="utf-8")
        self.assertIn("hmdp-profile", text, name)
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure.FrontendStructureTests.test_profile_pages_share_responsive_profile_layout -v`

Expected: FAIL。

- [ ] **Step 3: 实现个人中心与他人主页**

桌面左侧 `.hmdp-profile-card` 展示头像、昵称、简介和操作；右侧 `.hmdp-profile-content` 保留笔记、评价、粉丝和关注标签。未登录进入本人页面仍由原拦截逻辑跳转登录。

- [ ] **Step 4: 实现资料编辑表单**

资料编辑使用桌面居中卡片，字段纵向排列，保存和取消按钮右对齐；手机端按钮全宽。保存期间禁用按钮，失败后保留输入。

- [ ] **Step 5: 验证并提交**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: PASS。

```bash
git add frontend/hmdp/info*.html frontend/hmdp/other-info.html frontend/hmdp/css/info.css frontend/tests
git commit -m "feat: redesign responsive profile pages"
```

### Task 7: 将智能问答改成桌面双栏工作区

**Files:**
- Modify: `frontend/hmdp/rag.html`
- Modify: `frontend/hmdp/css/rag.css`
- Modify: `frontend/tests/test_frontend_structure.py`

**Interfaces:**
- Consumes: `/api/rag/documents`、`/api/rag/ask`、`/api/shop/{shopId}/ask` 和现有 token。
- Produces: `.hmdp-rag-workspace`，左侧文档区和右侧问答区；手机端继续标签切换。

- [ ] **Step 1: 写 RAG 页面失败测试**

```python
def test_rag_uses_java_api_and_desktop_workspace(self):
    text = (ROOT / "rag.html").read_text(encoding="utf-8")
    self.assertIn('class="hmdp-rag-workspace"', text)
    self.assertIn("/rag/documents", text)
    self.assertIn("/rag/ask", text)
    self.assertIn("/shop/${shopId}/ask", text)
    self.assertNotIn("owner_id", text)
    self.assertNotIn("127.0.0.1:8003", text)

def test_protected_rag_actions_check_token(self):
    text = (ROOT / "rag.html").read_text(encoding="utf-8")
    self.assertIn("requireLogin", text)
    self.assertIn("location.href = '/login.html'", text)
```

- [ ] **Step 2: 运行测试并确认桌面工作区测试失败**

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure.FrontendStructureTests.test_rag_uses_java_api_and_desktop_workspace -v`

Expected: FAIL，缺少 `.hmdp-rag-workspace`。

- [ ] **Step 3: 重排 RAG 页面**

桌面 `.hmdp-rag-workspace` 使用 `grid-template-columns: minmax(320px, 380px) minmax(0, 1fr)`。左侧始终显示上传和文档列表，右侧显示问题、回答和来源。`max-width:767px` 时恢复“问一问/知识文档”标签切换。

保留 120 秒问答和上传超时、文档状态轮询、删除确认、来源链接 `rel="noopener noreferrer"`。回答正文使用 `white-space:pre-wrap` 和 `overflow-wrap:anywhere`。

- [ ] **Step 4: 验证加载和错误状态**

确认 `ask()` 与 `upload()` 使用 `try/catch/finally`，无论成功或失败均重置 `asking`、`uploading`；`pending/processing` 才继续轮询；未登录的上传和全站问答调用 `requireLogin()`。

Run: `D:\code\zgws\.venv\Scripts\python.exe -m unittest frontend.tests.test_frontend_structure -v`

Expected: PASS。

- [ ] **Step 5: 提交智能问答桌面版**

```bash
git add frontend/hmdp/rag.html frontend/hmdp/css/rag.css frontend/tests
git commit -m "feat: redesign the RAG desktop workspace"
```

### Task 8: 全站验证、部署配置与使用文档

**Files:**
- Create: `scripts/verify-frontend.ps1`
- Modify: `docs/RAG-使用说明.md`
- Modify: `frontend/tests/test_frontend_structure.py`
- Verify and modify if a check fails: `frontend/hmdp/*.html`
- Verify and modify if a check fails: `frontend/hmdp/css/*.css`
- Verify and modify if a check fails: `frontend/hmdp/js/*.js`

**Interfaces:**
- Consumes: Tasks 1～7 的完整响应式站点。
- Produces: 可重复运行的静态、Nginx 和 HTTP 验证流程。

- [ ] **Step 1: 为内嵌脚本和 HTTP 页面增加验证脚本**

`scripts/verify-frontend.ps1` 必须：

1. 运行 Python 静态测试。
2. 提取每个 HTML 的内嵌 `<script>` 到临时目录并执行 `node --check`。
3. 执行 `nginx -t`。
4. Nginx 运行时请求 11 个页面以及 `desktop.css`、`components.css`、`header.js`，要求全部返回 200。

HTTP 页面列表固定为：

```powershell
$pages = @(
  'index.html','shop-list.html','shop-detail.html','blog-detail.html',
  'blog-edit.html','login.html','login2.html','info.html','info-edit.html',
  'other-info.html','rag.html'
)
```

- [ ] **Step 2: 运行自动验证并按失败类型处理**

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-frontend.ps1`

Expected: Python tests PASS、全部 JavaScript `node --check` PASS、Nginx 配置成功、全部 HTTP 请求 200。

如果静态引用测试失败，修正输出中指定页面的 `src` 或 `href`；如果 `node --check` 失败，修正输出中指定脚本和行号的语法；如果 HTTP 状态不是 200，先确认对应文件存在，再核对 Nginx `root` 是否为 `D:/code/hm-dianping/frontend/hmdp`。每次只修正当前失败类别并重新运行脚本。

- [ ] **Step 3: 进行桌面视口人工检查**

在浏览器开发者工具依次设置 `1366×768` 和 `1920×1080`，检查全部 11 个页面：无页面级横向滚动；顶部导航可见且不遮挡内容；网格列数正确；超长标题不撑破卡片；店铺详情 AI 按钮携带当前 ID。

- [ ] **Step 4: 进行手机视口人工检查**

设置 `375×812`，检查全部页面：完整顶部导航隐藏；底部导航可见；内容为单栏；表单按钮可点击；页面底部不被导航遮挡；RAG 标签切换、上传和问答区域均可访问。

- [ ] **Step 5: 运行端到端业务冒烟检查**

依次启动 MySQL、Redis、Milvus、大模型、`shop-qa`、Java 和 Nginx。验证：

1. 登录并确认 token 保存。
2. 首页打开店铺列表和店铺详情。
3. 店铺详情点击“询问 AI”，确认 URL 包含 `shopId`。
4. 上传一个店铺 Markdown，状态最终变为 `completed`。
5. 提问“这家店有什么特色”，回答显示来源。
6. 删除测试文档，列表中不再显示。

- [ ] **Step 6: 更新启动文档**

`docs/RAG-使用说明.md` 使用仓库前端路径和新的 Nginx 配置，明确访问地址 `http://127.0.0.1:8080/rag.html`，区分首次配置、每次启动、首次上传和故障检查。

- [ ] **Step 7: 最终验证并提交**

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-frontend.ps1`

Expected: 全部检查成功。

Run: `git diff --check`

Expected: 无错误。

```bash
git add frontend deploy/nginx scripts/verify-frontend.ps1 docs/RAG-使用说明.md
git commit -m "test: verify responsive frontend deployment"
```
