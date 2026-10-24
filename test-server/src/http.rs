//! HTTP-laget: samme endpoints som backend/src/routes/sms.js (telefonen og afsendere).

use std::io::Read;
use std::sync::Arc;
use std::thread;

use serde_json::{Value, json};
use tiny_http::{Header, Method, Request, Response, Server};

use crate::core::{GATEWAY_ID, GATEWAY_NAME, Inner, KINDS, MAX_TEXT_LENGTH, NewSms, Shared};

pub struct ServerHandle {
    server: Arc<Server>,
}

impl ServerHandle {
    pub fn stop(&self) {
        self.server.unblock();
    }
}

pub fn start(shared: Arc<Shared>, port: u16) -> Result<ServerHandle, String> {
    let server = Server::http(("0.0.0.0", port)).map_err(|e| e.to_string())?;
    let server = Arc::new(server);
    let accept = server.clone();
    thread::spawn(move || {
        for request in accept.incoming_requests() {
            let shared = shared.clone();
            // Hver forespørgsel i sin egen tråd, så en long-poll kan vente uden at blokere de andre.
            thread::spawn(move || handle(shared, request));
        }
    });
    Ok(ServerHandle { server })
}

fn header(req: &Request, name: &str) -> Option<String> {
    req.headers().iter().find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name)).map(|h| h.value.as_str().to_string())
}

fn bearer(req: &Request) -> Option<String> {
    header(req, "Authorization").and_then(|v| v.strip_prefix("Bearer ").map(|t| t.trim().to_string()))
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < bytes.len() => {
                match std::str::from_utf8(&bytes[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok()) {
                    Some(b) => {
                        out.push(b);
                        i += 2;
                    }
                    None => out.push(b'%'),
                }
            }
            b => out.push(b),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn query_param(query: &str, key: &str) -> Option<String> {
    query.split('&').filter_map(|p| p.split_once('=')).find(|(k, _)| *k == key).map(|(_, v)| percent_decode(v))
}

fn respond(req: Request, status: u16, body: Value) {
    let json = body.to_string();
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).expect("header");
    let _ = req.respond(Response::from_string(json).with_status_code(status).with_header(header));
}

fn respond_html(req: Request, html: String) {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/html; charset=utf-8"[..]).expect("header");
    let _ = req.respond(Response::from_string(html).with_header(header));
}

fn error(req: Request, status: u16, message: &str) {
    respond(req, status, json!({ "error": message }));
}

fn handle(shared: Arc<Shared>, mut req: Request) {
    let method = req.method().clone();
    let url = req.url().to_string();
    let (path, query) = url.split_once('?').unwrap_or((url.as_str(), ""));
    let (path, query) = (path.trim_end_matches('/').to_string(), query.to_string());
    let path = if path.is_empty() { "/".to_string() } else { path };
    let token = bearer(&req);

    let mut body = String::new();
    if matches!(method, Method::Post | Method::Put | Method::Patch) {
        let _ = req.as_reader().take(1 << 20).read_to_string(&mut body);
    }

    let (phone_key, sender_key) = {
        let inner = shared.lock();
        (inner.settings.phone_key.clone(), inner.settings.sender_key.clone())
    };
    let is_phone = token.as_deref() == Some(phone_key.as_str());
    let is_sender = token.as_deref() == Some(sender_key.as_str());
    let who = if path.starts_with("/api/sms-gateway") { "telefon" } else { "afsender" };

    // Loggen skal vise forespørgslen, uanset hvordan den ender — også de afviste.
    let log = |status: u16| shared.log(format!("{method} {path} → {status} ({who})"));

    match (&method, path.as_str()) {
        (Method::Get, "/") => {
            let (port, key_hint) = {
                let inner = shared.lock();
                (inner.settings.port, inner.settings.phone_key.chars().take(9).collect::<String>())
            };
            shared.log(format!("GET / → 200 (browser)"));
            respond_html(
                req,
                format!("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>GL-SMS testserver</title><body style='font:16px sans-serif;margin:2em'><h2>GL-SMS testserver kører</h2><p>Port {port}. Telefon-nøglen starter med <code>{key_hint}…</code>.</p><p>Kan du se denne side fra telefonen, kan appen også nå serveren.</p></body>"),
            );
        }

        // ---- Telefonen ----
        (Method::Get, "/api/sms-gateway/ping") => {
            if !is_phone {
                log(401);
                return error(req, 401, "Ukendt nøgle");
            }
            shared.lock().touch_gateway(None, query_param(&query, "v").as_deref());
            shared.changed();
            log(200);
            respond(req, 200, json!({ "ok": true, "gateway": { "id": GATEWAY_ID, "name": GATEWAY_NAME } }));
        }
        (Method::Get, "/api/sms-gateway/poll") => {
            if !is_phone {
                log(401);
                return error(req, 401, "Ukendt nøgle");
            }
            let wait = query_param(&query, "wait").and_then(|w| w.parse::<f64>().ok()).unwrap_or(0.0).clamp(0.0, 30.0) as u64;
            let battery = query_param(&query, "battery").and_then(|b| b.parse::<i64>().ok());
            let version = query_param(&query, "v");
            let messages = shared.poll(wait, battery, version.as_deref());
            if !messages.is_empty() {
                log(200);
            }
            respond(req, 200, json!({ "messages": messages, "serverTime": chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true) }));
        }
        (Method::Post, "/api/sms-gateway/report") => {
            if !is_phone {
                log(401);
                return error(req, 401, "Ukendt nøgle");
            }
            let Some(reports) = parse_reports(&body) else {
                log(400);
                return error(req, 400, "Ugyldig kvittering");
            };
            let mut applied = 0;
            {
                let mut inner = shared.lock();
                for (id, status, err) in &reports {
                    if inner.report(id, status, err.as_deref()) {
                        applied += 1;
                    }
                }
            }
            shared.changed();
            log(200);
            respond(req, 200, json!({ "ok": true, "applied": applied }));
        }

        // ---- Afsendere ----
        (Method::Post, "/api/sms") => {
            if !is_sender {
                log(401);
                return error(req, 401, "Ikke logget ind, eller nøglen er forkert");
            }
            let new = match parse_new_sms(&body) {
                Ok(n) => n,
                Err(msg) => {
                    log(400);
                    return error(req, 400, &msg);
                }
            };
            match shared.queue(new) {
                Ok((sms, online)) => {
                    log(201);
                    respond(req, 201, json!({
                        "id": sms.id, "to": sms.to, "status": sms.status,
                        "expiresAt": sms.expires_at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true), "gatewayOnline": online,
                    }));
                }
                Err(e) => {
                    log(e.status);
                    error(req, e.status, &e.message);
                }
            }
        }
        (Method::Get, "/api/sms/gateways") => {
            if !is_sender {
                log(401);
                return error(req, 401, "Ikke logget ind, eller nøglen er forkert");
            }
            let json = shared.lock().gateways_json();
            log(200);
            respond(req, 200, json);
        }
        (Method::Get, "/api/sms") => {
            if !is_sender {
                log(401);
                return error(req, 401, "Ikke logget ind, eller nøglen er forkert");
            }
            let limit = query_param(&query, "limit").and_then(|l| l.parse::<usize>().ok()).unwrap_or(50).clamp(1, 200);
            let status = query_param(&query, "status");
            let to = query_param(&query, "to");
            let messages: Vec<Value> = {
                let mut inner = shared.lock();
                inner.housekeeping();
                inner
                    .messages
                    .iter()
                    .rev()
                    .filter(|m| status.as_deref().is_none_or(|s| m.status == s) && to.as_deref().is_none_or(|t| m.to == t))
                    .take(limit)
                    .map(Inner::message_json)
                    .collect()
            };
            log(200);
            respond(req, 200, json!({ "messages": messages }));
        }
        (Method::Get | Method::Delete, p) if p.starts_with("/api/sms/") => {
            if !is_sender {
                log(401);
                return error(req, 401, "Ikke logget ind, eller nøglen er forkert");
            }
            let id = &p["/api/sms/".len()..];
            if method == Method::Get {
                let found = {
                    let mut inner = shared.lock();
                    inner.housekeeping();
                    inner.messages.iter().find(|m| m.id == id).map(Inner::message_json)
                };
                match found {
                    Some(json) => {
                        log(200);
                        respond(req, 200, json);
                    }
                    None => {
                        log(404);
                        error(req, 404, "SMS ikke fundet");
                    }
                }
            } else if shared.lock().cancel(id) {
                shared.changed();
                log(200);
                respond(req, 200, json!({ "ok": true }));
            } else {
                log(409);
                error(req, 409, "Kan kun trækkes tilbage, mens den står i kø");
            }
        }
        _ => {
            log(404);
            error(req, 404, "Ukendt adresse");
        }
    }
}

/// {"reports":[{id,status,error?}]} eller en enkelt kvittering — højst 50, status sent|delivered|failed.
fn parse_reports(body: &str) -> Option<Vec<(String, String, Option<String>)>> {
    let v: Value = serde_json::from_str(body).ok()?;
    let items: Vec<Value> = match v.get("reports") {
        Some(Value::Array(a)) => a.clone(),
        Some(_) => return None,
        None => vec![v],
    };
    if items.is_empty() || items.len() > 50 {
        return None;
    }
    items
        .iter()
        .map(|r| {
            let id = r.get("id")?.as_str().filter(|s| !s.is_empty() && s.len() <= 60)?.to_string();
            let status = r.get("status")?.as_str().filter(|s| matches!(*s, "sent" | "delivered" | "failed"))?.to_string();
            let err = r.get("error").and_then(|e| e.as_str()).map(|e| e.chars().take(300).collect());
            Some((id, status, err))
        })
        .collect()
}

fn parse_new_sms(body: &str) -> Result<NewSms, String> {
    let invalid = || "Ugyldig forespørgsel".to_string();
    let v: Value = serde_json::from_str(body).map_err(|_| invalid())?;
    let to = v.get("to").and_then(Value::as_str).ok_or("Angiv modtager")?;
    if to.is_empty() || to.chars().count() > 40 {
        return Err("Angiv modtager".into());
    }
    let text = v.get("text").and_then(Value::as_str).ok_or("Teksten er tom")?;
    if text.is_empty() || text.chars().count() > MAX_TEXT_LENGTH * 2 {
        return Err("Teksten er tom".into());
    }
    let kind = match v.get("kind") {
        None | Some(Value::Null) => "free".to_string(),
        Some(k) => k.as_str().filter(|k| KINDS.contains(k)).ok_or_else(invalid)?.to_string(),
    };
    let reference = match v.get("ref") {
        None | Some(Value::Null) => None,
        Some(r) => Some(r.as_str().filter(|r| r.chars().count() <= 100).ok_or_else(invalid)?.to_string()),
    };
    let ttl_seconds = match v.get("ttlSeconds") {
        None | Some(Value::Null) => None,
        Some(t) => Some(t.as_i64().filter(|t| (30..=7 * 24 * 3600).contains(t)).ok_or_else(invalid)?),
    };
    Ok(NewSms { to: to.to_string(), text: text.to_string(), kind, reference, ttl_seconds, created_by: "sender-nøgle".into() })
}
