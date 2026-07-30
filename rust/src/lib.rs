//! liveupdate_core  Android JNI library
//!
//! JNI interface:
//!   `analyzeScreenshotNative(rgbaBytes, width, height, apiKey, baseUrl, modelId, reasoningEffort, jpegB64) -> JSON`
//!   QR 检测与 AI 分析并发执行，AI 结果填入 title/body。

pub mod ai;

use base64::Engine;
use image::GenericImageView;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

// ------------------------------------------------------------------------------
// Result struct
// ------------------------------------------------------------------------------

/// 返回给 Android 的分析结果（序列化为 JSON）。
#[derive(Serialize)]
struct AnalysisResult {
    /// 核心凭证（AI 提取：取餐码 / 取件码等纯字符）
    title: String,
    /// 辅助详情第一行（用于通知单行紧凑展示）
    body: String,
    /// 辅助详情各行（按 `\n` 拆分，最多 3 行，供灵动岛富界面按行使用）
    #[serde(rename = "infoLines", skip_serializing_if = "Vec::is_empty")]
    info_lines: Vec<String>,
    qr_found: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    qr_region_png_base64: Option<String>,
    // ---- AI 扩展字段 ----
    /// 2-4 汉字标签（如"取餐码"）
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<String>,
    /// 图标枚举（如"BURGER"）
    #[serde(rename = "iconType", skip_serializing_if = "Option::is_none")]
    icon_type: Option<String>,
    /// 按钮文字（如"已取"）
    #[serde(rename = "buttonText", skip_serializing_if = "Option::is_none")]
    button_text: Option<String>,
    /// 订单总价或当前应付价格
    #[serde(skip_serializing_if = "Option::is_none")]
    price: Option<String>,
    /// 商品或服务名称
    #[serde(skip_serializing_if = "Option::is_none")]
    item: Option<String>,
    /// 商品规格等次级描述
    #[serde(rename = "itemDetail", skip_serializing_if = "Option::is_none")]
    item_detail: Option<String>,
    /// 商家、门店或服务网点
    #[serde(skip_serializing_if = "Option::is_none")]
    merchant: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    /// Sanitized failure category for local diagnostics. Never contains response bodies or credentials.
    #[serde(rename = "debugError", skip_serializing_if = "Option::is_none")]
    debug_error: Option<String>,
}

// ------------------------------------------------------------------------------
// QR 检测（纯同步，无 IO）
// ------------------------------------------------------------------------------

#[derive(Deserialize, Serialize)]
struct QrResult {
    found: bool,
    region_png_b64: Option<String>,
}

fn detect_qr(rgba_bytes: Vec<u8>, width: i32, height: i32) -> QrResult {
    let img = match image::RgbaImage::from_raw(width as u32, height as u32, rgba_bytes) {
        Some(buf) => image::DynamicImage::ImageRgba8(buf),
        None => {
            log::error!("RgbaImage::from_raw failed  buffer too small?");
            return QrResult {
                found: false,
                region_png_b64: None,
            };
        }
    };

    let gray = img.to_luma8();
    let mut prepared = rqrr::PreparedImage::prepare(gray);
    let grids = prepared.detect_grids();

    if grids.is_empty() {
        log::debug!("No QR codes detected");
        return QrResult {
            found: false,
            region_png_b64: None,
        };
    }

    let mut blocked = 0usize;
    let selected = grids
        .into_iter()
        .filter_map(|grid| {
            let payload = match grid.decode() {
                Ok((_, payload)) => Some(payload),
                Err(error) => {
                    log::debug!("Initial QR payload decode failed: {error}");
                    retry_decode_qr_payload(&img, &grid.bounds)
                }
            };
            if payload
                .as_deref()
                .is_some_and(is_enterprise_wechat_qr_payload)
            {
                blocked += 1;
                log::info!("Filtered enterprise WeChat QR payload");
                return None;
            }
            let width = grid.bounds.iter().map(|point| point.x).max().unwrap_or(0)
                - grid.bounds.iter().map(|point| point.x).min().unwrap_or(0);
            let height = grid.bounds.iter().map(|point| point.y).max().unwrap_or(0)
                - grid.bounds.iter().map(|point| point.y).min().unwrap_or(0);
            Some((i64::from(width) * i64::from(height), grid))
        })
        .max_by_key(|(area, _)| *area);

    let Some((_, grid)) = selected else {
        log::info!(
            "QR codes detected but filtered: enterprise_wechat={}",
            blocked
        );
        return QrResult {
            found: false,
            region_png_b64: None,
        };
    };

    let region_png_b64 = match crop_to_base64(&img, &grid.bounds) {
        Ok(b64) => Some(b64),
        Err(e) => {
            log::warn!("QR crop failed: {}", e);
            None
        }
    };

    QrResult {
        found: true,
        region_png_b64,
    }
}

fn is_enterprise_wechat_qr_payload(payload: &str) -> bool {
    let mut normalized = payload.trim().to_ascii_lowercase().replace("\\/", "/");
    for _ in 0..3 {
        if normalized.contains("work.weixin.qq.com") {
            return true;
        }
        let decoded = percent_decode_lossy(&normalized);
        if decoded == normalized {
            break;
        }
        normalized = decoded;
    }
    false
}

fn percent_decode_lossy(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 2 < bytes.len() {
            if let (Some(high), Some(low)) =
                (hex_value(bytes[index + 1]), hex_value(bytes[index + 2]))
            {
                decoded.push((high << 4) | low);
                index += 3;
                continue;
            }
        }
        decoded.push(bytes[index]);
        index += 1;
    }
    String::from_utf8_lossy(&decoded).into_owned()
}

fn hex_value(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn retry_decode_qr_payload(img: &image::DynamicImage, bounds: &[rqrr::Point; 4]) -> Option<String> {
    let (img_w, img_h) = img.dimensions();
    let min_x = bounds.iter().map(|point| point.x).min()?;
    let max_x = bounds.iter().map(|point| point.x).max()?;
    let min_y = bounds.iter().map(|point| point.y).min()?;
    let max_y = bounds.iter().map(|point| point.y).max()?;
    let side = (max_x - min_x).max(max_y - min_y).max(1);
    let padding = ((side as f32 * 0.2).ceil() as i32).max(8);
    let left = (min_x - padding).max(0) as u32;
    let top = (min_y - padding).max(0) as u32;
    let right = ((max_x + padding) as u32).min(img_w);
    let bottom = ((max_y + padding) as u32).min(img_h);
    if right <= left || bottom <= top {
        return None;
    }

    let gray = img
        .crop_imm(left, top, right - left, bottom - top)
        .to_luma8();
    let longest_side = gray.width().max(gray.height()).max(1);
    let scale = (900 / longest_side).clamp(2, 4);
    let scaled_width = gray.width() * scale;
    let scaled_height = gray.height() * scale;
    let variants = [
        image::imageops::resize(
            &gray,
            scaled_width,
            scaled_height,
            image::imageops::FilterType::Nearest,
        ),
        image::imageops::resize(
            &gray,
            scaled_width,
            scaled_height,
            image::imageops::FilterType::Triangle,
        ),
    ];

    for variant in variants {
        let mut prepared = rqrr::PreparedImage::prepare(variant);
        for retry_grid in prepared.detect_grids() {
            if let Ok((_, payload)) = retry_grid.decode() {
                log::info!("QR payload decoded after crop upscale retry");
                return Some(payload);
            }
        }
    }
    None
}

// ------------------------------------------------------------------------------
// 并发分析（QR + AI）
// ------------------------------------------------------------------------------

struct ScreenshotInput {
    rgba_bytes: Vec<u8>,
    width: i32,
    height: i32,
    jpeg_b64: String,
}

struct OpenAiRequest {
    api_key: String,
    base_url: String,
    model_id: String,
    reasoning_effort: Option<String>,
}

async fn analyze_screenshot(image: ScreenshotInput, request: OpenAiRequest) -> AnalysisResult {
    let ScreenshotInput {
        rgba_bytes,
        width,
        height,
        jpeg_b64,
    } = image;
    // QR 检测放到线程池，避免阻塞异步运行时；在闭包内部独立计时
    let qr_task = tokio::task::spawn_blocking(move || {
        let t = std::time::Instant::now();
        let res = detect_qr(rgba_bytes, width, height);
        (res, t.elapsed())
    });

    // AI 分析异步调用；用 async 块包裹以独立计时
    let ai_task = async {
        let t = std::time::Instant::now();
        let res = ai::analyze_screenshot_with_ai(
            &request.api_key,
            &request.base_url,
            &request.model_id,
            &jpeg_b64,
            request.reasoning_effort.as_deref(),
        )
        .await;
        (res, t.elapsed())
    };

    // 并发等待两个任务
    let (qr_joined, (ai_res, ai_elapsed)) = tokio::join!(qr_task, ai_task);

    let (
        QrResult {
            found: qr_found,
            region_png_b64: qr_region_png_base64,
        },
        qr_elapsed,
    ) = qr_joined.unwrap_or((
        QrResult {
            found: false,
            region_png_b64: None,
        },
        std::time::Duration::ZERO,
    ));

    log::info!(
        "耗时统计 — QR 检测: {}ms | AI 调用（含压缩）: {}ms",
        qr_elapsed.as_millis(),
        ai_elapsed.as_millis()
    );

    combine_results(
        QrResult {
            found: qr_found,
            region_png_b64: qr_region_png_base64,
        },
        ai_res,
    )
}

async fn analyze_miclaw_screenshot(
    image: ScreenshotInput,
    service_token: String,
    c_user_id: String,
    enable_thinking: bool,
) -> AnalysisResult {
    let ScreenshotInput {
        rgba_bytes,
        width,
        height,
        jpeg_b64,
    } = image;
    let qr_task = tokio::task::spawn_blocking(move || detect_qr(rgba_bytes, width, height));
    let ai_task =
        ai::analyze_screenshot_with_miclaw(&service_token, &c_user_id, &jpeg_b64, enable_thinking);
    let (qr, ai_result) = tokio::join!(qr_task, ai_task);
    combine_results(
        qr.unwrap_or(QrResult {
            found: false,
            region_png_b64: None,
        }),
        ai_result,
    )
}

fn combine_results(qr: QrResult, ai_res: Result<ai::IslandInfo, ai::AiError>) -> AnalysisResult {
    match ai_res {
        Ok(info) => {
            let (item, item_detail, remaining_info) =
                normalize_item_fields(&info.item, &info.item_detail, &info.info);
            let info_lines = build_info_lines(&item, &item_detail, &remaining_info, &info.merchant);
            let body = info_lines.first().cloned().unwrap_or_default();
            AnalysisResult {
                title: info.title,
                body,
                info_lines,
                qr_found: qr.found,
                qr_region_png_base64: qr.region_png_b64,
                content: Some(info.content),
                icon_type: Some(info.icon_type),
                button_text: Some(info.button_text),
                price: Some(info.price),
                item: Some(item),
                item_detail: Some(item_detail),
                merchant: Some(info.merchant),
                error: None,
                debug_error: None,
            }
        }
        Err(e) => {
            let debug_error = diagnostic_ai_error(&e);
            let error = e.to_string();
            let message = user_facing_ai_error(&error).to_owned();
            log::error!("AI analysis failed: {} ({})", message, debug_error);
            AnalysisResult {
                title: "识别失败".to_owned(),
                body: message.clone(),
                info_lines: vec![message.clone()],
                qr_found: qr.found,
                qr_region_png_base64: qr.region_png_b64,
                content: Some("AI 服务".to_owned()),
                icon_type: Some("RECEIPT".to_owned()),
                button_text: Some("知道了".to_owned()),
                price: None,
                item: None,
                item_detail: None,
                merchant: None,
                error: Some(message),
                debug_error: Some(debug_error),
            }
        }
    }
}

fn analyze_miclaw_result(qr: QrResult, raw_text: &str) -> AnalysisResult {
    combine_results(qr, ai::parse_island_info(raw_text))
}

fn user_facing_ai_error(error: &str) -> &'static str {
    if error.contains("over capacity") || error.contains("503 Service Unavailable") {
        "AI 服务繁忙，请稍后重试"
    } else if error.contains("429 Too Many Requests") {
        "请求过于频繁，请稍后重试"
    } else if error.contains("401 Unauthorized") {
        "API Key 无效，请检查配置"
    } else if error.contains("403 Forbidden") {
        "当前模型无权访问"
    } else {
        "AI 识别暂时不可用"
    }
}

fn diagnostic_ai_error(error: &ai::AiError) -> String {
    match error {
        ai::AiError::Client(message) => {
            if let Some(status) = message
                .strip_prefix("Miclaw HTTP ")
                .and_then(|rest| rest.split(':').next())
                .filter(|status| status.chars().all(|char| char.is_ascii_digit()))
            {
                format!("miclaw_http_{status}")
            } else if message.contains("Miclaw request failed") {
                "miclaw_network_request_failed".to_owned()
            } else if message.contains("Miclaw response read failed") {
                "miclaw_response_read_failed".to_owned()
            } else if message.contains("serviceToken is empty") {
                "miclaw_service_token_empty".to_owned()
            } else {
                "miclaw_client_error".to_owned()
            }
        }
        ai::AiError::Parse(_) => "miclaw_json_parse_failed".to_owned(),
        ai::AiError::InvalidResponse(message) => {
            if message.starts_with("Miclaw envelope") {
                "miclaw_response_envelope_invalid".to_owned()
            } else if message.starts_with("Miclaw model output") {
                "miclaw_model_json_invalid".to_owned()
            } else if message.contains("no assistant text") {
                "miclaw_response_missing_assistant_text".to_owned()
            } else {
                "miclaw_invalid_response".to_owned()
            }
        }
    }
}

/// Uses legacy `info` lines as item fields when older model output omits the new keys.
fn normalize_item_fields(item: &str, item_detail: &str, info: &str) -> (String, String, String) {
    if !item.trim().is_empty() {
        return (
            item.trim().to_owned(),
            item_detail.trim().to_owned(),
            info.trim().to_owned(),
        );
    }
    let mut legacy_lines = info
        .split('\n')
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned);
    let item = legacy_lines.next().unwrap_or_default();
    let item_detail = if item_detail.trim().is_empty() {
        legacy_lines.next().unwrap_or_default()
    } else {
        item_detail.trim().to_owned()
    };
    (
        item,
        item_detail,
        legacy_lines.collect::<Vec<_>>().join("\n"),
    )
}

/// Produces the notification order: item, item detail, merchant, then status.
fn build_info_lines(item: &str, item_detail: &str, info: &str, merchant: &str) -> Vec<String> {
    let mut lines = [item, item_detail, merchant]
        .into_iter()
        .chain(info.split('\n'))
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .fold(Vec::<String>::new(), |mut result, line| {
            if !result.iter().any(|existing| existing == line) {
                result.push(line.to_owned());
            }
            result
        });

    lines.truncate(4);
    lines
}

// ------------------------------------------------------------------------------
// QR 区域裁切辅助
// ------------------------------------------------------------------------------

fn crop_to_base64(
    img: &image::DynamicImage,
    bounds: &[rqrr::Point; 4],
) -> Result<String, Box<dyn std::error::Error>> {
    let (img_w, img_h) = img.dimensions();

    let min_x = bounds.iter().map(|p| p.x).min().unwrap();
    let max_x = bounds.iter().map(|p| p.x).max().unwrap();
    let min_y = bounds.iter().map(|p| p.y).min().unwrap();
    let max_y = bounds.iter().map(|p| p.y).max().unwrap();

    let qr_size = (max_x - min_x).max(max_y - min_y);
    let padding = (((qr_size as f64) * 0.1).ceil() as i32).max(5);

    let x = (min_x - padding).max(0) as u32;
    let y = (min_y - padding).max(0) as u32;
    let x2 = ((max_x + padding) as u32).min(img_w);
    let y2 = ((max_y + padding) as u32).min(img_h);
    let (crop_w, crop_h) = (x2 - x, y2 - y);

    if crop_w == 0 || crop_h == 0 {
        return Err("裁切区域尺寸为零".into());
    }

    let cropped = img.crop_imm(x, y, crop_w, crop_h);
    let mut buf = Vec::new();
    cropped.write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Png)?;
    Ok(base64::engine::general_purpose::STANDARD.encode(&buf))
}

// ------------------------------------------------------------------------------
// JNI entry point
// ------------------------------------------------------------------------------

/// QR 检测与 AI 分析并发执行，返回合并后的 JSON。
///
/// 参数：rgbaBytes, width, height（图像），apiKey, baseUrl, modelId, reasoningEffort, jpegB64（AI）
#[no_mangle]
pub extern "system" fn Java_com_jizizr_signaldock_RustBridge_analyzeScreenshotNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rgba_bytes: JByteArray<'local>,
    width: jint,
    height: jint,
    j_api_key: JString<'local>,
    j_base_url: JString<'local>,
    j_model_id: JString<'local>,
    j_reasoning_effort: JString<'local>,
    j_jpeg_b64: JString<'local>,
) -> jstring {
    ensure_logger();

    macro_rules! jstr {
        ($j:expr, $field:literal) => {
            match env.get_string(&$j) {
                Ok(s) => String::from(s),
                Err(e) => return err_json(&mut env, &format!("bad {}: {e}", $field)),
            }
        };
    }

    let rgba = match env.convert_byte_array(&rgba_bytes) {
        Ok(b) => b,
        Err(e) => return err_json(&mut env, &format!("convert_byte_array failed: {e}")),
    };
    let api_key = jstr!(j_api_key, "apiKey");
    let base_url = jstr!(j_base_url, "baseUrl");
    let model_id = jstr!(j_model_id, "modelId");
    let reasoning_effort = jstr!(j_reasoning_effort, "reasoningEffort");
    let jpeg_b64 = jstr!(j_jpeg_b64, "jpegB64");

    let rt = match runtime() {
        Ok(rt) => rt,
        Err(e) => return err_json(&mut env, &format!("tokio build error: {e}")),
    };

    let result = rt.block_on(analyze_screenshot(
        ScreenshotInput {
            rgba_bytes: rgba,
            width,
            height,
            jpeg_b64,
        },
        OpenAiRequest {
            api_key,
            base_url,
            model_id,
            reasoning_effort: if reasoning_effort.is_empty() {
                None
            } else {
                Some(reasoning_effort)
            },
        },
    ));

    let json = serde_json::to_string(&result)
        .unwrap_or_else(|e| format!(r#"{{"error":"serialize failed: {e}"}}"#));

    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_jizizr_signaldock_RustBridge_miclawPromptNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    env.new_string(ai::USER_PROMPT)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_jizizr_signaldock_RustBridge_analyzeMiclawResultNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    j_raw_text: JString<'local>,
    j_qr_json: JString<'local>,
) -> jstring {
    ensure_logger();
    let raw_text = match env.get_string(&j_raw_text) {
        Ok(text) => String::from(text),
        Err(error) => return err_json(&mut env, &format!("bad Miclaw response: {error}")),
    };
    let qr_json = match env.get_string(&j_qr_json) {
        Ok(text) => String::from(text),
        Err(error) => return err_json(&mut env, &format!("bad QR result: {error}")),
    };
    let qr = serde_json::from_str(&qr_json).unwrap_or_else(|error| {
        log::error!("Unable to parse QR result: {error}");
        QrResult {
            found: false,
            region_png_b64: None,
        }
    });
    let result = analyze_miclaw_result(qr, &raw_text);
    let json = serde_json::to_string(&result)
        .unwrap_or_else(|error| format!(r#"{{"error":"serialize failed: {error}"}}"#));
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_jizizr_signaldock_RustBridge_detectQrNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rgba_bytes: JByteArray<'local>,
    width: jint,
    height: jint,
) -> jstring {
    ensure_logger();
    let rgba = match env.convert_byte_array(&rgba_bytes) {
        Ok(bytes) => bytes,
        Err(error) => return err_json(&mut env, &format!("convert_byte_array failed: {error}")),
    };
    let result = detect_qr(rgba, width, height);
    let json = serde_json::to_string(&result)
        .unwrap_or_else(|error| format!(r#"{{"error":"serialize failed: {error}"}}"#));
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_jizizr_signaldock_RustBridge_analyzeMiclawDirectNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rgba_bytes: JByteArray<'local>,
    width: jint,
    height: jint,
    j_service_token: JString<'local>,
    j_c_user_id: JString<'local>,
    enable_thinking: jboolean,
    j_jpeg_b64: JString<'local>,
) -> jstring {
    ensure_logger();
    let rgba = match env.convert_byte_array(&rgba_bytes) {
        Ok(bytes) => bytes,
        Err(error) => return err_json(&mut env, &format!("convert_byte_array failed: {error}")),
    };
    let service_token = match env.get_string(&j_service_token) {
        Ok(value) => String::from(value),
        Err(error) => return err_json(&mut env, &format!("bad serviceToken: {error}")),
    };
    let c_user_id = match env.get_string(&j_c_user_id) {
        Ok(value) => String::from(value),
        Err(error) => return err_json(&mut env, &format!("bad cUserId: {error}")),
    };
    let jpeg_b64 = match env.get_string(&j_jpeg_b64) {
        Ok(value) => String::from(value),
        Err(error) => return err_json(&mut env, &format!("bad jpegB64: {error}")),
    };
    let runtime = match runtime() {
        Ok(runtime) => runtime,
        Err(error) => return err_json(&mut env, &format!("tokio build error: {error}")),
    };
    let result = runtime.block_on(analyze_miclaw_screenshot(
        ScreenshotInput {
            rgba_bytes: rgba,
            width,
            height,
            jpeg_b64,
        },
        service_token,
        c_user_id,
        enable_thinking != 0,
    ));
    let json = serde_json::to_string(&result)
        .unwrap_or_else(|error| format!(r#"{{"error":"serialize failed: {error}"}}"#));
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ------------------------------------------------------------------------------
// Android logger
// ------------------------------------------------------------------------------

static LOGGER_INIT: std::sync::Once = std::sync::Once::new();
static RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();

fn runtime() -> Result<&'static tokio::runtime::Runtime, std::io::Error> {
    if let Some(runtime) = RUNTIME.get() {
        return Ok(runtime);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let _ = RUNTIME.set(runtime);
    Ok(RUNTIME.get().expect("runtime was initialized"))
}

fn ensure_logger() {
    LOGGER_INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(if cfg!(debug_assertions) {
                    log::LevelFilter::Debug
                } else {
                    log::LevelFilter::Info
                })
                .with_tag("liveupdate_core"),
        );
    });
}

fn err_json(env: &mut JNIEnv<'_>, msg: &str) -> jstring {
    let json = serde_json::json!({ "error": msg }).to_string();
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
/// # Safety
/// Called by the JVM on library load; `vm` is a valid `JavaVM` pointer.
pub unsafe extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    ensure_logger();
    // TLS 证书由 webpki-roots（Mozilla 根证书包）在 ai.rs 中静态加载，
    // 无需依赖 rustls-platform-verifier 的 JVM 初始化。
    let _ = vm;
    jni::sys::JNI_VERSION_1_6
}

// ------------------------------------------------------------------------------
// 测试
// ------------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;
    use image::GenericImageView;

    const BASE_URL: &str = "https://generativelanguage.googleapis.com/v1beta/openai";
    const MODEL_ID: &str = "gemini-2.5-flash";

    #[test]
    fn inserts_merchant_after_product() {
        assert_eq!(
            build_info_lines("芭乐奶绿", "正常冰 · 七分糖", "", "蜜雪冰城(崇文门店)"),
            ["芭乐奶绿", "正常冰 · 七分糖", "蜜雪冰城(崇文门店)"]
        );
    }

    #[test]
    fn does_not_duplicate_existing_merchant() {
        assert_eq!(
            build_info_lines("芭乐奶绿", "", "蜜雪冰城(崇文门店)", "蜜雪冰城(崇文门店)"),
            ["芭乐奶绿", "蜜雪冰城(崇文门店)"]
        );
    }

    #[test]
    fn maps_legacy_info_lines_to_item_hierarchy() {
        assert_eq!(
            normalize_item_fields("", "", "芭乐奶绿\n正常冰/七分糖\n预计12:30完成"),
            (
                "芭乐奶绿".to_owned(),
                "正常冰/七分糖".to_owned(),
                "预计12:30完成".to_owned(),
            )
        );
    }

    #[test]
    fn maps_capacity_error_to_actionable_message() {
        assert_eq!(
            user_facing_ai_error("503 Service Unavailable: model is currently over capacity"),
            "AI 服务繁忙，请稍后重试"
        );
    }

    #[test]
    fn filters_enterprise_wechat_qr_urls() {
        assert!(is_enterprise_wechat_qr_payload(
            "https://work.weixin.qq.com/kfid/kfc123456"
        ));
        assert!(is_enterprise_wechat_qr_payload(
            "HTTPS://WORK.WEIXIN.QQ.COM/ca/cawcde123"
        ));
        assert!(is_enterprise_wechat_qr_payload(
            "https%3A%2F%2Fwork%2Eweixin%2Eqq%2Ecom%2Fkfid%2Fabc"
        ));
        assert!(is_enterprise_wechat_qr_payload(
            r#"https:\/\/work.weixin.qq.com\/ca\/abc"#
        ));
    }

    #[test]
    fn keeps_non_enterprise_wechat_qr_urls() {
        assert!(!is_enterprise_wechat_qr_payload(
            "https://u.wechat.com/example"
        ));
        assert!(!is_enterprise_wechat_qr_payload(
            "https://merchant.example.com/pickup/order-123"
        ));
    }
    /// 与 ai.rs 使用同一张 tests/sample_screenshot.jpg。
    /// cargo test -- --nocapture --ignored
    #[tokio::test]
    #[ignore]
    async fn test_analyze_screenshot() {
        let api_key = std::env::var("OPENAI_API_KEY")
            .expect("set OPENAI_API_KEY before running the ignored integration test");
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests")
            .join("sample_screenshot.jpg");

        let bytes =
            std::fs::read(&path).unwrap_or_else(|_| panic!("请将测试截图放到 {}", path.display()));

        // 解码为 RGBA 像素 + 尺寸
        let img = image::load_from_memory(&bytes).expect("图片解码失败");
        let (width, height) = img.dimensions();
        let rgba_bytes = img.to_rgba8().into_raw();

        // JPEG Base64（与 JNI 调用路径一致）
        let jpeg_b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);

        println!(
            "图片尺寸: {}x{}, RGBA bytes: {}",
            width,
            height,
            rgba_bytes.len()
        );

        let result = analyze_screenshot(
            ScreenshotInput {
                rgba_bytes,
                width: width as i32,
                height: height as i32,
                jpeg_b64,
            },
            OpenAiRequest {
                api_key,
                base_url: BASE_URL.to_owned(),
                model_id: MODEL_ID.to_owned(),
                reasoning_effort: None,
            },
        )
        .await;

        println!("\n=== analyze_screenshot 返回结果 ===");
        println!("title:           {}", result.title);
        println!("body:            {}", result.body);
        println!("info_lines:      {:?}", result.info_lines);
        println!("qr_found:        {}", result.qr_found);
        println!("content:         {:?}", result.content);
        println!("icon_type:       {:?}", result.icon_type);
        println!("button_text:     {:?}", result.button_text);
        println!("price:           {:?}", result.price);
        println!("merchant:        {:?}", result.merchant);
        // println!("JSON: {}", serde_json::to_string_pretty(&result).unwrap());
    }
}
