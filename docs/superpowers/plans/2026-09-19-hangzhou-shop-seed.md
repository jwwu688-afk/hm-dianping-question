# 杭州真实门店数据扩充实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为黑马点评 10 个分类各新增 5 家经网络核对的杭州门店，并生成可重复执行的 SQL 与 RAG Markdown。

**Architecture:** 以 JSON 作为唯一数据源，用生成脚本输出幂等 MySQL SQL 和 50 份 Markdown。独立校验测试检查数量、分类分布、ID、坐标、来源链接、SQL 与 Markdown 的一致性。

**Tech Stack:** Python 3.11 标准库、MySQL 8、Markdown、unittest

**Spec:** 用户确认每个分类 5 家；门店为杭州真实门店；事实来源必须可追溯；评分、销量和评论数仅作演示数据。

## Global Constraints

- 类型 ID 1 至 10，每类正好 5 家，共 50 家。
- 新门店 ID 固定为 1001 至 1050，脚本可重复执行。
- 名称、地址、坐标和分类均保留核对来源与日期 2026-09-19。
- 营业时间未知时写“以门店当日公示为准”，不编造实时营业状态。
- `avg_price`、`sold`、`comments`、`score` 是界面演示字段，Markdown 必须明确说明。
- 图片使用项目已有分类图标，作为分类示意图。

## Review Focus

- 记录缺失或重复时，测试应指出数量或 ID 错误。
- 任一分类不是 5 家时，测试应给出分类计数。
- 非杭州地址、异常经纬度或非 HTTP 来源应被拒绝。
- SQL 必须使用 `ON DUPLICATE KEY UPDATE`，重复执行不能新增重复行。
- 每份 Markdown 必须包含来源、核对日期、演示字段说明和店铺特色。

---

### Task 1: 建立数据契约测试

**Files:**
- Create: `services/shop-qa/tests/test_verified_shop_seed.py`

- [ ] 写入覆盖 50 家、每类 5 家、连续 ID、来源和坐标的测试。
- [ ] 运行测试并确认因数据文件不存在而失败。

### Task 2: 增加门店数据和生成器

**Files:**
- Create: `services/shop-qa/data/verified_hangzhou_shops.json`
- Create: `services/shop-qa/scripts/generate_verified_shop_seed.py`
- Create: `src/main/resources/db/shop_seed_verified_2026.sql`
- Create: `services/shop-qa/data/shop-markdown/shop-1001.md` ... `shop-1050.md`

- [ ] 写入 50 家经网络核对的数据。
- [ ] 生成幂等 SQL 与 Markdown。
- [ ] 运行契约测试并确认通过。

### Task 3: 写入本地数据库并验证

**Files:**
- Modify live MySQL table: `hmdp.tb_shop`

- [ ] 执行 SQL。
- [ ] 查询每个 `type_id` 的新增数量，确认均为 5。
- [ ] 再次执行 SQL并确认总数仍为 50。
- [ ] 运行 shop-qa 全量测试。

