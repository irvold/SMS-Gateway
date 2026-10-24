//! Køen og reglerne — samme opførsel som backend/src/lib/sms.js, men i hukommelsen (intet gemmes mellem kørsler).

use std::collections::VecDeque;
use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use chrono::{DateTime, Duration as ChronoDuration, SecondsFormat, Utc};
use eframe::egui;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub const KINDS: [&str; 4] = ["verification", "event-edit", "admin-login", "free"];
pub const MAX_TEXT_LENGTH: usize = 480;
const MAX_TTL_SECONDS: i64 = 7 * 24 * 60 * 60;
const LEASE_SECONDS: i64 = 90;
const MAX_ATTEMPTS: u32 = 2;
const ONLINE_WITHIN_SECONDS: i64 = 90;
const KEEP_MESSAGES: usize = 1000;
const KEEP_LOG_LINES: usize = 400;
const FINAL: [&str; 5] = ["sent", "delivered", "failed", "expired", "cancelled"];
pub const GATEWAY_ID: &str = "testphone";
pub const GATEWAY_NAME: &str = "Testtelefon";

/// Tilfældig streng til nøgler. Hver `RandomState` er seedet af styresystemet, så det er nok til en testserver.
fn random_token(len: usize) -> String {
    const ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut out = String::new();
    let mut n = 0u64;
    while out.len() < len {
        let mut h = RandomState::new().build_hasher();
        h.write_u64(n);
        let mut v = h.finish();
        n += 1;
        for _ in 0..8 {
            if out.len() < len {
                out.push(ALPHABET[(v % ALPHABET.len() as u64) as usize] as char);
            }
            v /= 64;
        }
    }
    out
}

pub fn new_phone_key() -> String {
    format!("glsms_{}", random_token(32))
}

pub fn new_sender_key() -> String {
    format!("glsend_{}", random_token(32))
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub port: u16,
    pub phone_key: String,
    pub sender_key: String,
    /// Kommasepareret, fx "+45,+46".
    pub allowed_prefixes: String,
    pub max_per_number_hour: u32,
    pub max_per_hour: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            port: 4100,
            phone_key: new_phone_key(),
            sender_key: new_sender_key(),
            allowed_prefixes: "+45".into(),
            max_per_number_hour: 6,
            max_per_hour: 120,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Sms {
    pub id: String,
    pub to: String,
    pub text: String,
    pub kind: String,
    pub reference: Option<String>,
    /// queued → sending → sent → delivered, eller failed / expired / cancelled.
    pub status: String,
    pub error: Option<String>,
    pub attempts: u32,
    pub expires_at: DateTime<Utc>,
    pub leased_until: Option<DateTime<Utc>>,
    /// Telefonen har hentet beskeden (kun den må kvittere for den).
    pub claimed: bool,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub sent_at: Option<DateTime<Utc>>,
    pub delivered_at: Option<DateTime<Utc>>,
}

pub struct SmsError {
    pub status: u16,
    pub message: String,
}

impl SmsError {
    fn bad(message: impl Into<String>) -> Self {
        Self { status: 400, message: message.into() }
    }
    fn too_many(message: impl Into<String>) -> Self {
        Self { status: 429, message: message.into() }
    }
}

pub struct NewSms {
    pub to: String,
    pub text: String,
    pub kind: String,
    pub reference: Option<String>,
    pub ttl_seconds: Option<i64>,
    pub created_by: String,
}

pub struct Inner {
    pub settings: Settings,
    pub messages: Vec<Sms>,
    pub gateway_seen: Option<DateTime<Utc>>,
    pub gateway_battery: Option<i64>,
    pub gateway_version: Option<String>,
    pub log: VecDeque<String>,
    next_id: u64,
}

pub struct Shared {
    inner: Mutex<Inner>,
    wake: Condvar,
    repaint: Mutex<Option<egui::Context>>,
}

fn iso(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn iso_opt(t: Option<DateTime<Utc>>) -> Value {
    t.map_or(Value::Null, |t| Value::String(iso(t)))
}

// ---- Telefonnumre og tekst ------------------------------------------------

/// "12 34 56 78", "+45 12345678", "0045 12345678" → "+4512345678". Otte cifre uden landekode regnes som danske.
pub fn normalize_phone(raw: &str) -> Option<String> {
    let mut s: String = raw
        .trim()
        .chars()
        .filter(|c| !c.is_whitespace() && !matches!(c, '(' | ')' | '.' | '-'))
        .collect();
    if let Some(rest) = s.strip_prefix("00") {
        s = format!("+{rest}");
    }
    if s.len() == 8 && s.chars().all(|c| c.is_ascii_digit()) {
        s = format!("+45{s}");
    }
    let digits = s.strip_prefix('+')?;
    let ok = (8..=15).contains(&digits.len()) && digits.chars().all(|c| c.is_ascii_digit()) && !digits.starts_with('0');
    ok.then_some(s)
}

/// Typografiske tegn skrives om til GSM-alfabetet, og kontroltegn fjernes (som cleanText i sms.js).
pub fn clean_text(text: &str) -> String {
    let mut out = String::new();
    for c in text.chars() {
        match c {
            '\u{2010}'..='\u{2015}' | '\u{2212}' | '\u{2022}' => out.push('-'),
            '\u{2018}' | '\u{2019}' | '\u{201A}' | '\u{2032}' => out.push('\''),
            '\u{201C}' | '\u{201D}' | '\u{201E}' | '\u{2033}' => out.push('"'),
            '\u{2026}' => out.push_str("..."),
            '\u{00A0}' | '\u{2002}'..='\u{2009}' | '\u{202F}' | '\t' => out.push(' '),
            '\u{200B}'..='\u{200D}' | '\u{FEFF}' => {}
            '\u{0000}'..='\u{0008}' | '\u{000B}' | '\u{000C}' | '\u{000E}'..='\u{001F}' | '\u{007F}' => {}
            c => out.push(c),
        }
    }
    out.trim().to_string()
}

fn default_ttl_seconds(kind: &str) -> i64 {
    match kind {
        "verification" => 15 * 60,
        "event-edit" | "admin-login" => 10 * 60,
        _ => 24 * 60 * 60,
    }
}

// ---- Køen -----------------------------------------------------------------

impl Inner {
    fn online(&self) -> bool {
        self.gateway_seen.is_some_and(|t| Utc::now() - t < ChronoDuration::seconds(ONLINE_WITHIN_SECONDS))
    }

    pub fn is_online(&self) -> bool {
        self.online()
    }

    pub fn queue(&mut self, req: NewSms) -> Result<(Sms, bool), SmsError> {
        if !KINDS.contains(&req.kind.as_str()) {
            return Err(SmsError::bad(format!("Ukendt type: {}", req.kind)));
        }
        let to = normalize_phone(&req.to).ok_or_else(|| SmsError::bad("Ugyldigt telefonnummer"))?;
        let prefixes: Vec<String> = self.settings.allowed_prefixes.split(',').map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect();
        if !prefixes.iter().any(|p| to.starts_with(p.as_str())) {
            return Err(SmsError::bad(format!("Der sendes ikke SMS til dette land (tilladt: {})", prefixes.join(", "))));
        }
        let text = clean_text(&req.text);
        if text.is_empty() {
            return Err(SmsError::bad("Teksten er tom"));
        }
        if text.chars().count() > MAX_TEXT_LENGTH {
            return Err(SmsError::bad(format!("Teksten er for lang (maks. {MAX_TEXT_LENGTH} tegn)")));
        }

        let hour_ago = Utc::now() - ChronoDuration::hours(1);
        let recent = |m: &&Sms| m.created_at > hour_ago && m.status != "cancelled";
        let per_number = self.messages.iter().filter(recent).filter(|m| m.to == to).count() as u32;
        let total = self.messages.iter().filter(recent).count() as u32;
        if per_number >= self.settings.max_per_number_hour {
            return Err(SmsError::too_many("For mange SMS til dette nummer den seneste time"));
        }
        if total >= self.settings.max_per_hour {
            return Err(SmsError::too_many("Timegrænsen for SMS er nået"));
        }

        let ttl = req.ttl_seconds.unwrap_or_else(|| default_ttl_seconds(&req.kind)).clamp(30, MAX_TTL_SECONDS);
        self.next_id += 1;
        let now = Utc::now();
        let sms = Sms {
            id: format!("m{:06}", self.next_id),
            to,
            text,
            kind: req.kind,
            reference: req.reference.filter(|r| !r.is_empty()).map(|r| r.chars().take(100).collect()),
            status: "queued".into(),
            error: None,
            attempts: 0,
            expires_at: now + ChronoDuration::seconds(ttl),
            leased_until: None,
            claimed: false,
            created_by: req.created_by,
            created_at: now,
            sent_at: None,
            delivered_at: None,
        };
        self.messages.push(sms.clone());
        if self.messages.len() > KEEP_MESSAGES {
            let drop = self.messages.len() - KEEP_MESSAGES;
            self.messages.drain(..drop);
        }
        Ok((sms, self.online()))
    }

    /// Udløb, beskeder telefonen hentede men aldrig kvitterede for, og koder, der ikke må blive stående i klartekst.
    pub fn housekeeping(&mut self) {
        let now = Utc::now();
        for m in &mut self.messages {
            if (m.status == "queued" || m.status == "sending") && m.expires_at < now {
                m.status = "expired".into();
                m.error = Some("Udløbet, før den blev sendt".into());
            } else if m.status == "sending" && m.leased_until.is_some_and(|l| l < now) {
                if m.attempts < MAX_ATTEMPTS {
                    m.status = "queued".into();
                    m.leased_until = None;
                } else {
                    m.status = "failed".into();
                    m.error = Some("Ingen kvittering fra telefonen".into());
                }
            }
            if m.kind != "free" && FINAL.contains(&m.status.as_str()) && !m.text.is_empty() {
                m.text.clear();
            }
        }
    }

    /// Giver telefonen op til `max` ventende beskeder og markerer dem som hentet. Koder først.
    pub fn claim(&mut self, max: usize) -> Vec<Value> {
        self.housekeeping();
        let now = Utc::now();
        let waiting = |m: &Sms| m.status == "queued" && m.expires_at > now;
        let mut order: Vec<usize> = (0..self.messages.len()).filter(|&i| waiting(&self.messages[i]) && self.messages[i].kind != "free").collect();
        order.extend((0..self.messages.len()).filter(|&i| waiting(&self.messages[i]) && self.messages[i].kind == "free"));
        order.truncate(max);
        order
            .into_iter()
            .map(|i| {
                let m = &mut self.messages[i];
                m.status = "sending".into();
                m.claimed = true;
                m.leased_until = Some(now + ChronoDuration::seconds(LEASE_SECONDS));
                m.attempts += 1;
                json!({ "id": m.id, "to": m.to, "text": m.text, "kind": m.kind, "expiresAt": iso(m.expires_at) })
            })
            .collect()
    }

    /// Telefonens kvittering. Giver true, hvis den ændrede noget (samme overgange som reportResult i sms.js).
    pub fn report(&mut self, id: &str, status: &str, error: Option<&str>) -> bool {
        let from: &[&str] = match status {
            "sent" => &["queued", "sending", "expired", "failed"],
            "delivered" => &["queued", "sending", "sent", "expired", "failed"],
            "failed" => &["queued", "sending", "sent", "expired"],
            _ => return false,
        };
        let Some(m) = self.messages.iter_mut().find(|m| m.id == id) else { return false };
        if !m.claimed || !from.contains(&m.status.as_str()) {
            return false;
        }
        let now = Utc::now();
        m.status = status.into();
        m.error = (status == "failed").then(|| error.filter(|e| !e.is_empty()).unwrap_or("Fejl på telefonen").chars().take(300).collect());
        match status {
            "sent" => m.sent_at = Some(now),
            "delivered" => m.delivered_at = Some(now),
            _ => {}
        }
        if m.kind != "free" {
            m.text.clear();
        }
        true
    }

    /// Træk en SMS tilbage, der endnu står i kø.
    pub fn cancel(&mut self, id: &str) -> bool {
        match self.messages.iter_mut().find(|m| m.id == id && m.status == "queued") {
            Some(m) => {
                m.status = "cancelled".into();
                m.text.clear();
                true
            }
            None => false,
        }
    }

    pub fn touch_gateway(&mut self, battery: Option<i64>, version: Option<&str>) {
        self.gateway_seen = Some(Utc::now());
        if let Some(b) = battery.filter(|b| (0..=100).contains(b)) {
            self.gateway_battery = Some(b);
        }
        if let Some(v) = version.filter(|v| !v.is_empty()) {
            self.gateway_version = Some(v.chars().take(20).collect());
        }
    }

    pub fn gateways_json(&self) -> Value {
        json!({ "gateways": [{
            "id": GATEWAY_ID, "name": GATEWAY_NAME, "lastSeenAt": iso_opt(self.gateway_seen),
            "battery": self.gateway_battery, "appVersion": self.gateway_version, "online": self.online(),
        }] })
    }

    pub fn message_json(m: &Sms) -> Value {
        let mut v = json!({
            "id": m.id, "to": m.to, "kind": m.kind, "ref": m.reference, "status": m.status, "error": m.error,
            "attempts": m.attempts, "createdBy": m.created_by, "createdAt": iso(m.created_at), "expiresAt": iso(m.expires_at),
            "sentAt": iso_opt(m.sent_at), "deliveredAt": iso_opt(m.delivered_at),
        });
        // Som i den rigtige server vises teksten kun for frie beskeder (feltet udelades helt for koder).
        if m.kind == "free" {
            v["text"] = Value::String(m.text.clone());
        }
        v
    }
}

impl Shared {
    pub fn new(settings: Settings) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(Inner {
                settings,
                messages: Vec::new(),
                gateway_seen: None,
                gateway_battery: None,
                gateway_version: None,
                log: VecDeque::new(),
                next_id: 0,
            }),
            wake: Condvar::new(),
            repaint: Mutex::new(None),
        })
    }

    pub fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn set_repaint(&self, ctx: egui::Context) {
        *self.repaint.lock().unwrap_or_else(|e| e.into_inner()) = Some(ctx);
    }

    /// Væk ventende long-polls og få vinduet til at tegne forfra.
    pub fn changed(&self) {
        self.wake.notify_all();
        if let Some(ctx) = self.repaint.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            ctx.request_repaint();
        }
    }

    pub fn log(&self, line: String) {
        let stamp = chrono::Local::now().format("%H:%M:%S");
        let mut inner = self.lock();
        inner.log.push_back(format!("{stamp}  {line}"));
        while inner.log.len() > KEEP_LOG_LINES {
            inner.log.pop_front();
        }
        drop(inner);
        self.changed();
    }

    pub fn queue(&self, req: NewSms) -> Result<(Sms, bool), SmsError> {
        let result = self.lock().queue(req);
        self.changed();
        result
    }

    /// Long-poll: svarer med det samme, hvis der ligger beskeder, ellers først når der kommer en — eller efter `wait` sekunder.
    pub fn poll(&self, wait_seconds: u64, battery: Option<i64>, version: Option<&str>) -> Vec<Value> {
        let deadline = Instant::now() + Duration::from_secs(wait_seconds);
        let mut inner = self.lock();
        inner.touch_gateway(battery, version);
        loop {
            let messages = inner.claim(5);
            let now = Instant::now();
            if !messages.is_empty() || now >= deadline {
                if wait_seconds > 0 {
                    inner.touch_gateway(None, None);
                }
                drop(inner);
                if !messages.is_empty() {
                    self.changed();
                }
                return messages;
            }
            // 5 sekunder som sikkerhedsnet, så udløb og lease-tid også passes under ventetiden.
            let step = (deadline - now).min(Duration::from_secs(5));
            inner = self.wake.wait_timeout(inner, step).unwrap_or_else(|e| e.into_inner()).0;
        }
    }
}
