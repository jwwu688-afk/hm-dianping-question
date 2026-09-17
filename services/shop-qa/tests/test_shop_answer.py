import unittest

from app.shop_qa.answer import answer_shop


class ShopAnswerTests(unittest.TestCase):
    def test_no_evidence_returns_honest_answer_without_calling_model(self):
        result = answer_shop(7, "这家店有什么特色？", search=lambda *_: [])
        self.assertEqual(result["shop_id"], 7)
        self.assertEqual(result["sources"], [])
        self.assertIn("资料", result["answer"])


if __name__ == "__main__":
    unittest.main()
