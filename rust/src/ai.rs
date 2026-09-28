//! AI 图片分析模块（OpenAI-compatible Chat Completions API）
//!
//! 将截图 Base64 通过 OpenAI 兼容的 chat/completions 接口发送给多模态大模型，
//! 提取灵动岛所需结构化数据并反序列化返回。

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

const MICLAW_CHAT_URL: &str = "https://api.miclaw.xiaomi.net/osbot/pc/llm/v1/chat/completions";

// ------------------------------------------------------------------------------
// 返回结构体
// ------------------------------------------------------------------------------

/// 从 AI 响应 JSON 反序列化的灵动岛展示数据。
#[derive(Debug, Deserialize, Serialize)]
pub struct IslandInfo {
    /// 最核心凭证（取餐码、取件码、座位号等），去除前缀后的纯字符
    #[serde(deserialize_with = "deserialize_credential")]
    pub title: String,
    /// title 的简短描述标签（2-4 个汉字）
    pub content: String,
    /// 商家、餐厅或服务网点名称；截图未提供时为空
    #[serde(default, deserialize_with = "deserialize_optional_text")]
    pub merchant: String,
    /// 订单总价或当前应付价格；截图未提供时为空
    #[serde(default, deserialize_with = "deserialize_optional_text")]
    pub price: String,
    /// 核心商品或服务名称；截图未提供时为空
    #[serde(default, deserialize_with = "deserialize_optional_text")]
    pub item: String,
    /// 商品规格、温度、甜度等直接描述商品本身的次级信息
    #[serde(
        rename = "itemDetail",
        default,
        deserialize_with = "deserialize_optional_text"
    )]
    pub item_detail: String,
    /// 预计时间、状态等不属于商品规格的辅助详情
    #[serde(default, deserialize_with = "deserialize_optional_text")]
    pub info: String,
    /// 图标枚举类型
    #[serde(
        rename = "iconType",
        default = "default_icon",
        deserialize_with = "deserialize_optional_text"
    )]
    pub icon_type: String,
    /// 与当前场景匹配的完成操作文字（2-4 个汉字）
    #[serde(
        rename = "buttonText",
        default = "default_action",
        deserialize_with = "deserialize_optional_text"
    )]
    pub button_text: String,
}

fn default_icon() -> String {
    "RECEIPT".to_owned()
}
fn default_action() -> String {
    "已完成".to_owned()
}

fn deserialize_credential<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<String, D::Error> {
    match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::String(text) => Ok(text),
        serde_json::Value::Number(number) => Ok(number.to_string()),
        _ => Err(serde::de::Error::custom(
            "credential must be text or a number",
        )),
    }
}

fn deserialize_optional_text<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<String, D::Error> {
    fn text(value: serde_json::Value) -> Option<String> {
        match value {
            serde_json::Value::String(text) => Some(text),
            serde_json::Value::Number(number) => Some(number.to_string()),
            serde_json::Value::Null => Some(String::new()),
            _ => None,
        }
    }
    let value = serde_json::Value::deserialize(deserializer)?;
    let converted = if let serde_json::Value::Array(values) = value {
        values
            .into_iter()
            .map(text)
            .collect::<Option<Vec<_>>>()
            .map(|values| {
                values
                    .into_iter()
                    .filter(|v| !v.is_empty())
                    .collect::<Vec<_>>()
                    .join("、")
            })
    } else {
        text(value)
    };
    converted.ok_or_else(|| serde::de::Error::custom("display field must contain text"))
}

// ------------------------------------------------------------------------------
// 提示词
// ------------------------------------------------------------------------------

pub(crate) const USER_PROMPT: &str = r#"# Role
你是“灵动岛”UI截图信息提取专用引擎。
目标：从截图中提取关键信息，并输出**严格受控、结构稳定的 JSON**。
# Language
设备语言：zh-CN
所有文本字段必须使用简体中文。
# 最高优先级规则（不可违反）
1. 仅输出纯 JSON 字符串。
2. 禁止 Markdown、代码块、解释性文字。
3. 非必填字段无法识别时返回 ""。
4. 严格遵守字段长度与结构限制。
---
# 字段提取规则
## 1. title (必填)
**定义**：完成线下动作的核心凭证。
示例：取餐码、取件码、座位号、登机口、排队号。
**规则**：
- 只保留核心字符。
- 去除前缀词（如“取餐码 A888”→“A888”）。
- 通常为数字或字母组合。
- 必须是最醒目且最具行动性的编号。
---
## 2. content (必填)
**定义**：title 的类型标签。
**要求**：
- 2–4 个汉字。
- 示例：取餐码、快递柜、登机口、检票口、排队号。
- 不得超过 4 个字。
---
## 3. merchant (场景必填)
**定义**：商家、餐厅、品牌门店或服务网点名称。
**强制规则**：
- 餐饮、饮品、外卖、到店取餐、零售订单截图中，只要任意位置出现品牌或门店，必须提取，禁止省略。
- 品牌和分店分别出现时合并为“品牌(分店简称)”，例如“蜜雪冰城(崇文门店)”。
- 仅保留品牌 + 分店简称，不保留门牌号、楼层、行政区或完整地址。
- 只能使用截图中可见的信息，禁止猜测；确实无法识别时返回 ""。
- 最长 16 个全角字符，超出时缩略分店名，不得删除品牌名。
---
## 4. item (场景选填)
**定义**：核心商品或服务名称。
**规则**：
- 餐饮、饮品和零售订单中截图存在商品名时必须提取。
- 仅保留一个最核心商品；多个商品可使用“商品名等N件”。
- 不得包含商家、价格、温度、甜度、规格或取餐时间。
- 最长 12 个全角字符；无商品或服务名称时返回 ""。
---
## 5. itemDetail (选填)
**定义**：直接描述 item 本身的规格或偏好，将作为商品名下方的次级文字。
**规则**：
- 可提取温度、冰量、甜度、杯型、尺寸、口味、加料等，例如“正常冰 · 七分糖”。
- 多项使用“ · ”连接，禁止换行。
- 不得包含商家、价格、地址、取餐码、预计完成时间或订单状态。
- 最多保留 2 个最重要规格，最长 10 个全角字符；没有商品规格时返回 ""。
---
## 6. info (选填)
**定义**：不属于商品规格的状态或时间补充。
**规则**：
- 仅保留预计完成时间、窗口、状态等对行动有帮助的信息。
- 最多 1 行，最长 12 个全角字符。
- 不得重复 merchant、price、item 或 itemDetail，不得输出地址。
- 无有效信息返回 ""。
---
## 7. price (选填)
**定义**：订单总价、实付金额或当前应付金额。
**规则**：
- 只提取截图中明确可见的最终总价、实付或应付金额，禁止猜测。
- 同时出现原价、优惠、单价和总价时，优先最终实付或应付金额。
- 保留货币符号与必要小数，使用紧凑格式，例如“¥29.90”“12元”。
- 最长 8 个字符；无法确定时返回 ""。
- 不得把价格重复写入 info。
---
## 8. iconType (必填枚举)
根据主体内容精确匹配：
饮品类
- MILK_TEA
- COFFEE
主食类
- BURGER
- FRIED_CHICKEN
- RICE_BOWL
- NOODLES
- PIZZA
甜品类
- DESSERT
- CAKE
- FRUIT
通用
- TAKEOUT_BAG
- PACKAGE
- SHOPPING_BAG
默认
- RECEIPT
不确定时优先使用泛类。
---
## 9. buttonText (必填)
根据截图所代表的场景，生成用户完成当前事项后用于结束超级岛的按钮文字：
- 餐饮到店取餐 → 已取餐
- 快递或寄存取件 → 已取件
- 核销券码 → 已核销
- 票务检票 → 已检票
- 排队 / 等位 → 不等了
- 普通提醒或无法判断 → 已完成
限制：2–4 个汉字，表达必须自然、明确，不得输出“确定”“按钮”等无场景含义文案。
---
# 输出结构（严格一致）
{"title":"String","content":"String","merchant":"String","price":"String","item":"String","itemDetail":"String","info":"String","iconType":"Enum String","buttonText":"String"}
禁止新增字段。
禁止缺失必填字段。
禁止改变键名顺序。
# Few-Shot Examples
User Input: [肯德基截图: K555, 香辣鸡腿堡套餐, 实付29.90元, 北京大学北门店]
Assistant Output: {"title":"K555","content":"取餐码","merchant":"肯德基(北大北门店)","price":"¥29.90","item":"香辣鸡腿堡套餐","itemDetail":"","info":"","iconType":"BURGER","buttonText":"已取餐"}
User Input: [蜜雪冰城截图: 取餐码5312, 芭乐奶绿, 正常冰/七分糖, 合计12元, 崇文门对面店]
Assistant Output: {"title":"5312","content":"取餐码","merchant":"蜜雪冰城(崇文门店)","price":"12元","item":"芭乐奶绿","itemDetail":"正常冰 · 七分糖","info":"","iconType":"MILK_TEA","buttonText":"已取餐"}
User Input: [丰巢截图: 取件码 882299, 顺丰快递]
Assistant Output: {"title":"882299","content":"快递柜","merchant":"丰巢快递柜","price":"","item":"顺丰速运","itemDetail":"","info":"","iconType":"PACKAGE","buttonText":"已取件"}"#;

// ------------------------------------------------------------------------------
// 错误类型
// ------------------------------------------------------------------------------

#[derive(Debug)]
pub enum AiError {
    /// 构建客户端失败
    Client(String),
    /// JSON 反序列化失败
    Parse(serde_json::Error),
    /// AI 返回了非预期内容（无文本、无法找到 JSON 等）
    InvalidResponse(String),
}

impl std::fmt::Display for AiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AiError::Client(e) => write!(f, "client error: {e}"),
            AiError::Parse(e) => write!(f, "json parse error: {e}"),
            AiError::InvalidResponse(s) => write!(f, "invalid response: {s}"),
        }
    }
}

impl std::error::Error for AiError {}

impl From<serde_json::Error> for AiError {
    fn from(e: serde_json::Error) -> Self {
        AiError::Parse(e)
    }
}

fn shared_http_client() -> Result<reqwest::Client, AiError> {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    if let Some(client) = CLIENT.get() {
        return Ok(client.clone());
    }

    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls_config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS12, &rustls::version::TLS13])
        .expect("TLS protocol versions are always valid")
        .with_root_certificates(root_store)
        .with_no_client_auth();
    let client = reqwest::Client::builder()
        .use_preconfigured_tls(tls_config)
        .timeout(Duration::from_secs(90))
        .build()
        .map_err(|error| AiError::Client(error.to_string()))?;
    let _ = CLIENT.set(client);
    Ok(CLIENT.get().expect("HTTP client was initialized").clone())
}

// ------------------------------------------------------------------------------
// 核心异步函数
// ------------------------------------------------------------------------------

fn chat_completions_url(base_url: &str) -> String {
    let trimmed = base_url.trim().trim_end_matches('/');
    if trimmed.ends_with("/chat/completions") {
        trimmed.to_owned()
    } else {
        format!("{trimmed}/chat/completions")
    }
}

fn assistant_text_from_response(response: &str) -> Result<String, AiError> {
    let envelope: serde_json::Value = serde_json::from_str(response)?;
    envelope
        .pointer("/choices/0/message/content")
        .and_then(|value| value.as_str())
        .or_else(|| {
            envelope
                .pointer("/choices/0/message/content/0/text")
                .and_then(|value| value.as_str())
        })
        .map(str::to_owned)
        .ok_or_else(|| AiError::InvalidResponse("response has no assistant text".into()))
}

/// 调用 OpenAI 兼容接口分析截图，返回反序列化后的灵动岛数据。
///
/// # 参数
/// - `api_key`   — API 密钥
/// - `base_url`  — OpenAI 兼容端点，例如 `"https://api.example.com/v1"`
/// - `model_id`  — 模型 ID，例如 `"qwen/qwen3.6-27b"`
/// - `jpeg_b64`  — 截图的 Base64 编码（无前缀，仅纯 Base64 字符串）
pub async fn analyze_screenshot_with_ai(
    api_key: &str,
    base_url: &str,
    model_id: &str,
    jpeg_b64: &str,
    reasoning_effort: Option<&str>,
) -> Result<IslandInfo, AiError> {
    let client = shared_http_client()?;
    let url = chat_completions_url(base_url);
    let mut body = json!({
        "model": model_id,
        "messages": [{
            "role": "user",
            "content": [
                { "type": "text", "text": USER_PROMPT },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": format!("data:image/jpeg;base64,{jpeg_b64}"),
                        "detail": "auto"
                    }
                }
            ]
        }],
        "temperature": 0.1,
        "stream": false
    });
    let params = body.as_object_mut().expect("request body is an object");
    let is_groq_qwen = base_url.contains("api.groq.com") && model_id.starts_with("qwen/");
    if is_groq_qwen {
        params.insert("max_completion_tokens".into(), json!(1024));
        params.insert(
            "reasoning_effort".into(),
            json!(reasoning_effort.unwrap_or("none")),
        );
        params.insert("response_format".into(), json!({ "type": "json_object" }));
    } else if let Some(effort) = reasoning_effort {
        params.insert("reasoning_effort".into(), json!(effort));
    }

    // Capacity and transient transport failures are retried once.
    let ai_start = std::time::Instant::now();
    let mut attempt = 1;
    let response_text = loop {
        log::info!("AI 请求: model={}, attempt={}/2", model_id, attempt);
        let response = match client
            .post(&url)
            .bearer_auth(api_key)
            .json(&body)
            .send()
            .await
        {
            Ok(response) => response,
            Err(error) if attempt == 1 => {
                log::warn!("AI transport failed, retrying once: {error}");
                attempt += 1;
                tokio::time::sleep(Duration::from_secs(2)).await;
                continue;
            }
            Err(error) => return Err(AiError::Client(format!("AI request failed: {error}"))),
        };
        let status = response.status();
        let text = response
            .text()
            .await
            .map_err(|error| AiError::Client(format!("AI response read failed: {error}")))?;
        if status.is_success() {
            break text;
        }
        if attempt == 1 && (status.as_u16() == 429 || status.is_server_error()) {
            log::warn!("AI HTTP {}, retrying once", status.as_u16());
            attempt += 1;
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        }
        let preview: String = text.chars().take(300).collect();
        return Err(AiError::Client(format!(
            "AI HTTP {}: {}",
            status.as_u16(),
            preview
        )));
    };
    log::info!("AI 请求耗时: {}ms", ai_start.elapsed().as_millis());

    let raw_text = assistant_text_from_response(&response_text)?;
    let info = parse_island_info(&raw_text)?;
    log::info!("AI analysis completed successfully");
    Ok(info)
}

/// Calls Xiaomi's Miclaw PC endpoint directly. No Miclaw process or agent prompt is involved.
pub async fn analyze_screenshot_with_miclaw(
    service_token: &str,
    c_user_id: &str,
    jpeg_b64: &str,
    enable_thinking: bool,
) -> Result<IslandInfo, AiError> {
    if service_token.trim().is_empty() {
        return Err(AiError::Client("Miclaw serviceToken is empty".into()));
    }

    let client = shared_http_client()?;

    let cookie = if c_user_id.trim().is_empty() {
        format!("serviceToken={service_token}")
    } else {
        format!("serviceToken={service_token}; cUserId={c_user_id}")
    };
    let body = json!({
        "model": "xiaomi/mimo",
        "messages": [{
            "role": "user",
            "content": [
                { "type": "text", "text": USER_PROMPT },
                {
                    "type": "image_url",
                    "image_url": { "url": format!("data:image/jpeg;base64,{jpeg_b64}") }
                }
            ]
        }],
        "temperature": 0.1,
        "max_tokens": 1024,
        "stream": false,
        "chat_template_kwargs": { "enable_thinking": enable_thinking },
        "response_format": { "type": "json_object" }
    });

    let started = std::time::Instant::now();
    let response = client
        .post(MICLAW_CHAT_URL)
        .header("User-Agent", "node")
        .header("Accept", "*/*")
        .header("Cookie", cookie)
        .json(&body)
        .send()
        .await
        .map_err(|e| AiError::Client(format!("Miclaw request failed: {e}")))?;
    let status = response.status();
    let text = response
        .text()
        .await
        .map_err(|e| AiError::Client(format!("Miclaw response read failed: {e}")))?;
    if !status.is_success() {
        let preview: String = text.chars().take(300).collect();
        return Err(AiError::Client(format!(
            "Miclaw HTTP {}: {}",
            status.as_u16(),
            preview
        )));
    }
    log::info!(
        "Miclaw direct request completed: thinking={}, elapsed={}ms",
        enable_thinking,
        started.elapsed().as_millis()
    );

    let assistant_text = assistant_text_from_response(&text)
        .map_err(|error| AiError::InvalidResponse(format!("Miclaw envelope: {error}")))?;
    parse_island_info(&assistant_text)
        .map_err(|error| AiError::InvalidResponse(format!("Miclaw model output: {error}")))
}

/// Parses the final text returned by either an OpenAI-compatible model or Miclaw.
pub(crate) fn parse_island_info(raw_text: &str) -> Result<IslandInfo, AiError> {
    let without_thinking = if let Some(end) = raw_text.rfind("</think>") {
        &raw_text[end + "</think>".len()..]
    } else {
        raw_text
    };
    let cleaned = without_thinking
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();
    let parsed = match serde_json::from_str::<IslandInfo>(cleaned) {
        Ok(info) => Ok(info),
        Err(original) => {
            let start = cleaned.find('{');
            let end = cleaned.rfind('}');
            match (start, end) {
                (Some(start), Some(end)) if end > start => {
                    Ok(serde_json::from_str(&cleaned[start..=end])?)
                }
                _ => Err(original.into()),
            }
        }
    };
    parsed.map(|mut info| {
        if info.icon_type.is_empty() {
            info.icon_type = default_icon();
        }
        if info.button_text.is_empty() {
            info.button_text = default_action();
        }
        info
    })
}

// ------------------------------------------------------------------------------
// 测试
// ------------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    const BASE_URL: &str = "https://api.groq.com/openai/v1";
    const MODEL_ID: &str = "qwen/qwen3.6-27b";

    #[test]
    fn parses_price_and_contextual_action() {
        let result = parse_island_info(
            r#"{"title":"35060","content":"取餐码","merchant":"麦当劳","price":"¥29.90","info":"麦辣鸡腿堡","iconType":"BURGER","buttonText":"已取餐"}"#,
        )
        .expect("valid island JSON");

        assert_eq!(result.price, "¥29.90");
        assert_eq!(result.button_text, "已取餐");
    }

    #[test]
    fn missing_price_remains_backward_compatible() {
        let result = parse_island_info(
            r#"{"title":"35060","content":"取餐码","merchant":"麦当劳","info":"麦辣鸡腿堡","iconType":"BURGER","buttonText":"已取餐"}"#,
        )
        .expect("legacy island JSON");

        assert!(result.price.is_empty());
    }

    #[test]
    fn accepts_numeric_code_and_missing_presentation_fields() {
        let result = parse_island_info(
            r#"{"title":5312,"content":"取餐码","price":9,"item":["芭乐奶绿"],"merchant":null}"#,
        )
        .unwrap();
        assert_eq!(result.title, "5312");
        assert_eq!(result.price, "9");
        assert_eq!(result.item, "芭乐奶绿");
        assert!(result.info.is_empty());
        assert_eq!(result.icon_type, "RECEIPT");
        assert_eq!(result.button_text, "已完成");
    }

    #[test]
    fn incomplete_or_ambiguous_credentials_still_fail() {
        for response in [
            r#"{}"#,
            r#"{"content":"取餐码"}"#,
            r#"{"title":["5312","9697"],"content":"取餐码"}"#,
            r#"{"title":{"code":"5312"},"content":"取餐码"}"#,
            "not JSON",
        ] {
            assert!(parse_island_info(response).is_err());
        }
    }

    #[test]
    fn preserves_code_leading_zeroes_and_defaults_null_optional_fields() {
        let result = parse_island_info(
            r#"```json
{"title":"0053","content":"取餐码","info":null,"iconType":null,"buttonText":null}
```"#,
        )
        .unwrap();
        assert_eq!(result.title, "0053");
        assert!(result.info.is_empty());
        assert_eq!(result.button_text, "已完成");
    }

    #[test]
    fn builds_chat_completions_url_once() {
        assert_eq!(
            chat_completions_url("https://api.groq.com/openai/v1/"),
            "https://api.groq.com/openai/v1/chat/completions"
        );
        assert_eq!(
            chat_completions_url("https://example.com/v1/chat/completions"),
            "https://example.com/v1/chat/completions"
        );
    }

    #[test]
    fn parses_text_and_array_response_shapes() {
        let direct = r#"{"choices":[{"message":{"content":"ok"}}]}"#;
        let array = r#"{"choices":[{"message":{"content":[{"type":"text","text":"ok"}]}}]}"#;
        assert_eq!(assistant_text_from_response(direct).unwrap(), "ok");
        assert_eq!(assistant_text_from_response(array).unwrap(), "ok");
    }

    /// 运行前请将实际截图放到 tests/sample_screenshot.jpg
    /// cargo test -- --nocapture --ignored
    #[tokio::test]
    #[ignore]
    async fn test_ai_with_screenshot_file() {
        let api_key = std::env::var("OPENAI_API_KEY")
            .expect("set OPENAI_API_KEY before running the ignored integration test");
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests")
            .join("sample_screenshot.jpg");

        let bytes =
            std::fs::read(&path).unwrap_or_else(|_| panic!("请将测试截图放到 {}", path.display()));

        let jpeg_b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);

        println!("发送图片大小: {} bytes", bytes.len());

        let result = analyze_screenshot_with_ai(&api_key, BASE_URL, MODEL_ID, &jpeg_b64, None)
            .await
            .expect("AI 调用失败");

        println!("\n=== AI 返回结果 ===");
        println!("title:      {}", result.title);
        println!("content:    {}", result.content);
        println!("price:      {}", result.price);
        println!("item:       {}", result.item);
        println!("itemDetail: {}", result.item_detail);
        println!("info:       {}", result.info);
        println!("iconType:   {}", result.icon_type);
        println!("buttonText: {}", result.button_text);
        println!("JSON: {}", serde_json::to_string_pretty(&result).unwrap());

        assert!(!result.title.is_empty(), "title 不应为空");
        assert!(!result.content.is_empty(), "content 不应为空");
        assert!(!result.icon_type.is_empty(), "iconType 不应为空");
        assert!(!result.button_text.is_empty(), "buttonText 不应为空");
    }
}
