"""店铺问答单元测试：验证「知识库无证据时诚实拒答」的核心行为。

被测对象：app/shop_qa/answer.py 的 answer_shop(shop_id, question, search)。
answer_shop 支持注入检索函数（依赖注入便于单测），本测试用恒返回空列表
的 lambda 模拟「检索不到任何资料」的场景，并断言此时：
- 不应调用大模型（否则无中生有），返回结构仍完整；
- shop_id 原样透传、sources 为空列表；
- answer 文案里明确提示「缺少资料」，而不是编造内容。

运行：python -m unittest tests.test_shop_answer（需在 services/shop-qa 下）
"""
import unittest

from app.shop_qa.answer import answer_shop


class ShopAnswerTests(unittest.TestCase):
    # 测试点：空证据 → 诚实拒答，且全程不触发 LLM 调用
    def test_no_evidence_returns_honest_answer_without_calling_model(self):
        # 注入恒返回空列表的假检索函数 → 模拟「知识库里没有该店的任何资料」
        result = answer_shop(7, "这家店有什么特色？", search=lambda *_: [])
        # 结构断言：入参店铺 ID 原样回传，来源列表为空
        self.assertEqual(result["shop_id"], 7)
        self.assertEqual(result["sources"], [])
        # 诚实性断言：答案文本应包含「资料」字样（提示用户没有查到相关资料）
        self.assertIn("资料", result["answer"])


if __name__ == "__main__":
    unittest.main()
