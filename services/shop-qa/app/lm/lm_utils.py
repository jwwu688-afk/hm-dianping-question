# 环境配置与依赖导入
import os
from dotenv import load_dotenv
from langchain_openai import ChatOpenAI
from langchain_core.exceptions import LangChainException
from typing import Optional

# 项目内部依赖
from app.conf.lm_config import lm_config
from app.core.logger import logger

# 全局缓存：键为(模型名, JSON输出模式)元组，值为ChatOpenAI实例
# 作用：避免重复初始化客户端，提升性能，统一实例管理
_llm_client_cache = {}


def get_llm_client(model: Optional[str] = None, json_mode: bool = False,
                   base_url: Optional[str] = None, api_key: Optional[str] = None) -> ChatOpenAI:
    """
    获取带全局缓存的LangChain ChatOpenAI客户端实例
    适配OpenAI/千问/即梦AI等**OpenAI兼容API**，支持自定义模型和JSON标准化输出
    核心特性：缓存机制+配置统一加载+异常精准捕获+国产模型参数适配+**多供应商支持**

    :param model: 模型名称，优先级：传入参数 > 配置文件lm_config.llm_model > 内置默认qwen3-32b
    :param json_mode: 是否开启JSON输出模式，开启后返回标准json_object格式（适配结构化数据解析）
    :param base_url: 指定API基础地址（用于跨供应商混搭，如 item_name 走百炼、图片摘要走方舟）；
                     不传则使用 .env 中的 OPENAI_BASE_URL
    :param api_key: 指定API密钥，需与 base_url 属于同一平台；不传则使用 .env 中的 OPENAI_API_KEY
    :return: 初始化完成的ChatOpenAI实例（优先从全局缓存获取，未命中则新建并缓存）
    :raise ValueError: 缺失API密钥/基础地址等核心配置
    :raise Exception: 模型初始化失败（LangChain封装层异常）
    """
    # 1. 确定目标模型（优先级递减，保证模型名非空）
    target_model = model or lm_config.llm_model or "qwen3-32b"
    # 确定目标供应商地址与密钥（未指定则回落到默认供应商）
    target_base_url = base_url or lm_config.base_url
    target_api_key = api_key or lm_config.api_key
    # 缓存键：模型名 + JSON模式 + 基础地址，唯一标识不同供应商下的不同客户端
    # 注意必须带上 base_url：不同平台的同名模型不能复用同一个实例
    cache_key = (target_model, json_mode, target_base_url)

    # 2. 缓存命中：直接返回已初始化的实例，避免重复创建
    if cache_key in _llm_client_cache:
        logger.debug(f"[LLM客户端] 缓存命中，直接返回实例：模型={target_model}，JSON模式={json_mode}，地址={target_base_url}")
        return _llm_client_cache[cache_key]

    # 3. 核心配置校验：拦截缺失的API关键配置，提前抛出明确异常
    if not target_api_key:
        raise ValueError("[LLM客户端] 配置缺失：请在.env中配置OPENAI_API_KEY（大模型API密钥）")
    if not target_base_url:
        raise ValueError("[LLM客户端] 配置缺失：请在.env中配置OPENAI_API_BASE（API接口基础地址）")
    logger.info(f"[LLM客户端] 开始初始化新实例：模型={target_model}，JSON模式={json_mode}，地址={target_base_url}")

    # 4. 配置参数组装：区分「国产模型私有参数」和「OpenAI通用参数」
    # extra_body：千问/即梦等国产模型专属私有参数（LangChain透传至API）
    extra_body = {"enable_thinking": False}  # 千问专属：关闭思考链输出，减少冗余内容
    # model_kwargs：OpenAI通用参数，所有兼容API均支持
    model_kwargs = {}
    if json_mode:
        # 开启JSON标准输出模式，强制模型返回可解析的json_object
        model_kwargs["response_format"] = {"type": "json_object"}
        logger.debug(f"[LLM客户端] 已开启JSON输出模式，模型将返回标准JSON结构")

    # 5. 客户端初始化：捕获LangChain封装层异常，抛出更友好的提示
    try:
        llm_client = ChatOpenAI(
            model=target_model,  # 目标模型名
            temperature=lm_config.llm_temperature or 0.1,  # 低温度保证输出确定性（0~1）
            api_key=target_api_key,  # API密钥
            base_url=target_base_url,  # API基础地址（适配国产模型代理地址）
            extra_body=extra_body,  # 国产模型私有参数透传
            model_kwargs=model_kwargs,  # OpenAI通用参数
        )
    except LangChainException as e:
        raise Exception(f"[LLM客户端] 模型【{target_model}】初始化失败（LangChain层）：{str(e)}") from e

    # 6. 新实例存入全局缓存，供后续调用复用
    _llm_client_cache[cache_key] = llm_client
    logger.info(f"[LLM客户端] 实例初始化成功并缓存：模型={target_model}，JSON模式={json_mode}，地址={target_base_url}")

    return llm_client


def get_item_name_llm_client(json_mode: bool = False) -> ChatOpenAI:
    """
    item_name 识别专用客户端（多供应商混搭入口）
    设计目标：
        1. 图片摘要（node_md_img）继续走默认供应商（火山方舟/豆包 VL_MODEL）
        2. 商品名识别（node_item_name_recognition）独立走第二供应商（阿里云百炼/通义千问）
        3. 三者配置任一缺失时**自动回退**到默认供应商，绝不因配置不全中断导入流程
    配置来源（.env）：
        QWEN_BASE_URL + QWEN_API_KEY + ITEM_NAME_MODEL 三者齐全才启用千问
    :param json_mode: 是否开启JSON输出模式（item_name识别需要纯文本，默认False）
    :return: ChatOpenAI实例（千问优先，缺失则回退默认模型）
    """
    model = lm_config.item_name_model
    base_url = lm_config.qwen_base_url
    api_key = lm_config.qwen_api_key

    if model and base_url and api_key:
        logger.info(f"[item_name模型] 使用独立供应商（千问）：模型={model}，地址={base_url}")
        return get_llm_client(model=model, json_mode=json_mode, base_url=base_url, api_key=api_key)

    logger.warning(
        "[item_name模型] QWEN_BASE_URL / QWEN_API_KEY / ITEM_NAME_MODEL 未配置完整，"
        f"已回退到默认供应商模型（{lm_config.llm_model}）。如需改用千问，请在 .env 中补全这三个变量。"
    )
    return get_llm_client(json_mode=json_mode)


# 测试示例：验证客户端创建、缓存机制及日志输出
if __name__ == "__main__":
    logger.info("===== 开始执行LLM客户端工具测试 =====")
    try:
        # 测试1：默认配置（默认模型+普通模式）
        client1 = get_llm_client()
        logger.info("✅ 测试1通过：默认配置客户端创建成功")

        # 测试2：指定多模态模型（qwen-vl-plus）+ 普通模式
        client2 = get_llm_client(model="qwen-vl-plus")
        logger.info("✅ 测试2通过：指定多模态模型客户端创建成功")

        # 测试3：同一模型+模式，验证缓存命中
        client3 = get_llm_client(model="qwen-vl-plus")
        logger.info(f"✅ 测试3通过：缓存机制验证成功，client2与client3为同一实例：{client2 is client3}")

        # 测试4：开启JSON输出模式
        client4 = get_llm_client(model="qwen3-32b", json_mode=True)
        logger.info("✅ 测试4通过：JSON输出模式客户端创建成功")

    except Exception as e:
        logger.error(f"❌ LLM客户端工具测试失败：{str(e)}", exc_info=True)
    finally:
        logger.info("===== LLM客户端工具测试结束 =====")