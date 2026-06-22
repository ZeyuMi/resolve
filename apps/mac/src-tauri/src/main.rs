#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    process::Command,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tauri::{GlobalShortcutManager, Manager};

const FEISHU_CALLBACK_PORT: u16 = 36321;
const FEISHU_CALLBACK_PATH: &str = "/oauth/feishu/callback";

#[derive(Debug, Serialize)]
struct FeishuOAuthResult {
    code: String,
    state: String,
    redirect_uri: String,
}

#[derive(Debug, Deserialize)]
struct SecureStoreKey {
    service: String,
    account: String,
}

#[tauri::command]
async fn run_feishu_oauth(
    app: tauri::AppHandle,
    app_id: String,
    scopes: Vec<String>,
) -> Result<FeishuOAuthResult, String> {
    if app_id.trim().is_empty() {
        return Err("Missing Feishu App ID.".to_string());
    }

    let listener = TcpListener::bind(("127.0.0.1", FEISHU_CALLBACK_PORT)).map_err(|error| {
        format!("Could not start local Feishu callback on port {FEISHU_CALLBACK_PORT}: {error}")
    })?;
    let redirect_uri = format!("http://127.0.0.1:{FEISHU_CALLBACK_PORT}{FEISHU_CALLBACK_PATH}");
    let state = random_state();
    let authorize_url = build_feishu_authorize_url(app_id.trim(), &redirect_uri, &state, &scopes);

    tauri::api::shell::open(&app.shell_scope(), authorize_url, None)
        .map_err(|error| format!("Could not open Feishu authorization page: {error}"))?;

    tauri::async_runtime::spawn_blocking(move || {
        wait_for_feishu_callback(listener, state, redirect_uri)
    })
    .await
    .map_err(|error| format!("Feishu OAuth listener failed: {error}"))?
}

#[tauri::command]
fn secure_store_get(key: SecureStoreKey) -> Result<Option<String>, String> {
    let output = Command::new("/usr/bin/security")
        .args([
            "find-generic-password",
            "-s",
            &key.service,
            "-a",
            &key.account,
            "-w",
        ])
        .output()
        .map_err(|error| format!("Could not read macOS Keychain: {error}"))?;

    if !output.status.success() {
        return Ok(None);
    }

    let value = String::from_utf8_lossy(&output.stdout)
        .trim_end_matches(['\r', '\n'])
        .to_string();
    Ok(Some(value))
}

#[tauri::command]
fn secure_store_set(key: SecureStoreKey, value: String) -> Result<(), String> {
    let output = Command::new("/usr/bin/security")
        .args([
            "add-generic-password",
            "-s",
            &key.service,
            "-a",
            &key.account,
            "-w",
            &value,
            "-U",
        ])
        .output()
        .map_err(|error| format!("Could not write macOS Keychain: {error}"))?;

    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[tauri::command]
fn secure_store_delete(key: SecureStoreKey) -> Result<(), String> {
    let output = Command::new("/usr/bin/security")
        .args([
            "delete-generic-password",
            "-s",
            &key.service,
            "-a",
            &key.account,
        ])
        .output()
        .map_err(|error| format!("Could not update macOS Keychain: {error}"))?;

    if output.status.success()
        || String::from_utf8_lossy(&output.stderr).contains("could not be found")
    {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

fn wait_for_feishu_callback(
    listener: TcpListener,
    expected_state: String,
    redirect_uri: String,
) -> Result<FeishuOAuthResult, String> {
    listener
        .set_nonblocking(true)
        .map_err(|error| format!("Could not configure Feishu callback listener: {error}"))?;

    let deadline = Instant::now() + Duration::from_secs(180);
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                if let Some(result) =
                    handle_feishu_callback(stream, &expected_state, &redirect_uri)?
                {
                    return Ok(result);
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() > deadline {
                    return Err("Feishu authorization timed out.".to_string());
                }
                std::thread::sleep(Duration::from_millis(120));
            }
            Err(error) => return Err(format!("Feishu callback failed: {error}")),
        }
    }
}

fn handle_feishu_callback(
    mut stream: TcpStream,
    expected_state: &str,
    redirect_uri: &str,
) -> Result<Option<FeishuOAuthResult>, String> {
    let mut buffer = [0_u8; 4096];
    let length = stream
        .read(&mut buffer)
        .map_err(|error| format!("Could not read Feishu callback: {error}"))?;
    let request = String::from_utf8_lossy(&buffer[..length]);
    let first_line = request.lines().next().unwrap_or_default();
    let target = first_line.split_whitespace().nth(1).unwrap_or("/");
    let (path, query) = target.split_once('?').unwrap_or((target, ""));

    if path != FEISHU_CALLBACK_PATH {
        write_oauth_response(&mut stream, 404, "Resolve did not recognize this callback.");
        return Ok(None);
    }

    let params = parse_query(query);
    if let Some(error) = params.get("error") {
        let message = params.get("error_description").unwrap_or(error);
        write_oauth_response(
            &mut stream,
            400,
            "Feishu authorization was cancelled or rejected.",
        );
        return Err(format!("Feishu OAuth error: {message}"));
    }

    let returned_state = params.get("state").cloned().unwrap_or_default();
    if returned_state != expected_state {
        write_oauth_response(
            &mut stream,
            400,
            "Resolve rejected this callback because the state did not match.",
        );
        return Err("Feishu OAuth state mismatch.".to_string());
    }

    let code = params
        .get("code")
        .filter(|value| !value.is_empty())
        .cloned()
        .ok_or_else(|| "Feishu callback did not include an authorization code.".to_string())?;

    write_oauth_response(
        &mut stream,
        200,
        "Feishu is connected. You can return to Resolve.",
    );
    Ok(Some(FeishuOAuthResult {
        code,
        state: returned_state,
        redirect_uri: redirect_uri.to_string(),
    }))
}

fn write_oauth_response(stream: &mut TcpStream, status: u16, message: &str) {
    let title = if status == 200 {
        "Resolve"
    } else {
        "Resolve OAuth"
    };
    let body = format!(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>{title}</title></head>\
     <body style=\"font: -apple-system-body; padding: 48px; color: #1d1d1f;\">\
     <h1 style=\"font-size: 20px;\">{title}</h1><p>{message}</p></body></html>"
    );
    let response = format!(
    "HTTP/1.1 {status} OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
    body.as_bytes().len(),
    body
  );
    let _ = stream.write_all(response.as_bytes());
}

fn build_feishu_authorize_url(
    app_id: &str,
    redirect_uri: &str,
    state: &str,
    scopes: &[String],
) -> String {
    let mut query = vec![
        ("app_id", app_id.to_string()),
        ("redirect_uri", redirect_uri.to_string()),
        ("state", state.to_string()),
    ];
    if !scopes.is_empty() {
        query.push(("scope", scopes.join(" ")));
    }
    let encoded = query
        .into_iter()
        .map(|(key, value)| format!("{}={}", percent_encode(key), percent_encode(&value)))
        .collect::<Vec<_>>()
        .join("&");
    format!("https://open.feishu.cn/open-apis/authen/v1/index?{encoded}")
}

fn parse_query(query: &str) -> HashMap<String, String> {
    query
        .split('&')
        .filter(|part| !part.is_empty())
        .filter_map(|part| {
            let (key, value) = part.split_once('=').unwrap_or((part, ""));
            Some((percent_decode(key), percent_decode(value)))
        })
        .collect()
}

fn percent_encode(value: &str) -> String {
    value
        .bytes()
        .flat_map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                vec![byte as char]
            }
            _ => format!("%{byte:02X}").chars().collect(),
        })
        .collect()
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => {
                output.push(b' ');
                index += 1;
            }
            b'%' if index + 2 < bytes.len() => {
                let hex = &value[index + 1..index + 3];
                if let Ok(byte) = u8::from_str_radix(hex, 16) {
                    output.push(byte);
                    index += 3;
                } else {
                    output.push(bytes[index]);
                    index += 1;
                }
            }
            byte => {
                output.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&output).to_string()
}

fn random_state() -> String {
    let mut bytes = [0_u8; 16];
    if let Ok(mut file) = std::fs::File::open("/dev/urandom") {
        if file.read_exact(&mut bytes).is_ok() {
            return bytes.iter().map(|byte| format!("{byte:02x}")).collect();
        }
    }

    let fallback = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("resolve-{fallback}-{}", std::process::id())
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            run_feishu_oauth,
            secure_store_get,
            secure_store_set,
            secure_store_delete
        ])
        .setup(|app| {
            let handle = app.handle();
            app.global_shortcut_manager()
                .register("Alt+Space", move || {
                    if let Some(window) = handle.get_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                        let _ = window.emit("open-quick-capture", ());
                    }
                })?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Resolve");
}
