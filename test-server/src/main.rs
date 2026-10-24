#![cfg_attr(not(test), windows_subsystem = "windows")]

//! GL-SMS testserver: en lille server til din pc, som appen GL-SMS kan forbinde til, uden at den rigtige
//! backend er rullet ud. Taler samme API som backend/src/routes/sms.js — se ../README.md.

mod core;
mod http;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, Local, Utc};
use eframe::egui::{self, Color32, RichText};

use crate::core::{KINDS, MAX_TEXT_LENGTH, NewSms, Settings, Shared};

const GREEN: Color32 = Color32::from_rgb(0x2F, 0x7A, 0x3D);
const RED: Color32 = Color32::from_rgb(0xC0, 0x39, 0x2B);
const ORANGE: Color32 = Color32::from_rgb(0xC2, 0x7C, 0x0E);

fn settings_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .unwrap_or_default()
        .join("gl-sms-testserver.json")
}

fn load_settings() -> Settings {
    let path = settings_path();
    if let Some(s) = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str::<Settings>(&t).ok()) {
        return s;
    }
    let s = Settings::default();
    save_settings(&s);
    s
}

fn save_settings(s: &Settings) {
    if let Ok(text) = serde_json::to_string_pretty(s) {
        let _ = std::fs::write(settings_path(), text);
    }
}

fn save_png(path: &Path, image: &egui::ColorImage) -> Result<(), String> {
    let file = std::fs::File::create(path).map_err(|e| e.to_string())?;
    let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), image.size[0] as u32, image.size[1] as u32);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().map_err(|e| e.to_string())?;
    writer.write_image_data(image.as_raw()).map_err(|e| e.to_string())
}

fn main() -> eframe::Result {
    // Skjult: `gl-sms-testserver --skaermbillede fil.png` tager et skærmbillede med demo-data og lukker igen.
    let capture = std::env::args().skip_while(|a| a != "--skaermbillede").nth(1).map(PathBuf::from);
    let viewport = egui::ViewportBuilder::default().with_title("GL-SMS testserver").with_inner_size([1180.0, 780.0]).with_min_inner_size([940.0, 600.0]);
    eframe::run_native(
        "GL-SMS testserver",
        eframe::NativeOptions { viewport, ..Default::default() },
        Box::new(move |cc| {
            cc.egui_ctx.all_styles_mut(|style| {
                for font in style.text_styles.values_mut() {
                    font.size *= 1.1;
                }
            });
            Ok(Box::new(App::new(&cc.egui_ctx, capture)))
        }),
    )
}

struct Capture {
    path: PathBuf,
    frames: u32,
    requested: bool,
}

struct App {
    shared: Arc<Shared>,
    server: Option<http::ServerHandle>,
    server_error: Option<String>,
    /// (netværkets navn, adresse til appen)
    addresses: Vec<(String, String)>,
    addresses_at: std::time::Instant,
    // Send-formular
    to: String,
    text: String,
    kind: String,
    ttl_minutes: u32,
    send_message: Option<(bool, String)>,
    // Indstillinger (redigeres, før de anvendes)
    edit: Settings,
    settings_message: Option<String>,
    capture: Option<Capture>,
}

impl App {
    fn new(ctx: &egui::Context, capture: Option<PathBuf>) -> Self {
        let settings = if capture.is_some() { Settings::default() } else { load_settings() };
        let shared = Shared::new(settings.clone());
        shared.set_repaint(ctx.clone());
        let mut app = Self {
            shared,
            server: None,
            server_error: None,
            addresses: Vec::new(),
            addresses_at: std::time::Instant::now() - Duration::from_secs(60),
            to: String::new(),
            text: String::new(),
            kind: "free".into(),
            ttl_minutes: 60,
            send_message: None,
            edit: settings,
            settings_message: None,
            capture: capture.clone().map(|path| Capture { path, frames: 0, requested: false }),
        };
        if capture.is_some() {
            app.seed_demo();
        }
        app.start_server();
        app
    }

    /// Demo-data til skærmbilledet: et par beskeder i forskellige tilstande.
    fn seed_demo(&mut self) {
        let mut inner = self.shared.lock();
        let add = |inner: &mut core::Inner, to: &str, text: &str, kind: &str| {
            let _ = inner.queue(NewSms { to: to.into(), text: text.into(), kind: kind.into(), reference: None, ttl_seconds: None, created_by: "demo".into() });
        };
        add(&mut inner, "12345678", "Gøngelandet: Din bekræftelseskode er 482913. Den udløber om 15 minutter.", "verification");
        add(&mut inner, "87654321", "Hej fra testserveren — vi ses lørdag!", "free");
        add(&mut inner, "20304050", "Gøngelandet: Din engangskode er 118204.", "event-edit");
        add(&mut inner, "40506070", "Husk at hente kurven i Præstø.", "free");
        let claimed = inner.claim(2);
        inner.touch_gateway(Some(87), Some("1.0.0"));
        for (i, m) in claimed.iter().enumerate() {
            let id = m["id"].as_str().unwrap_or_default().to_string();
            if i == 0 {
                inner.report(&id, "sent", None);
                inner.report(&id, "delivered", None);
            } else {
                inner.report(&id, "failed", Some("Ingen dækning"));
            }
        }
        drop(inner);
        for line in ["GET /api/sms-gateway/ping → 200 (telefon)", "GET /api/sms-gateway/poll → 200 (telefon)", "POST /api/sms-gateway/report → 200 (telefon)"] {
            self.shared.log(line.to_string());
        }
    }

    fn start_server(&mut self) {
        let port = self.shared.lock().settings.port;
        match http::start(self.shared.clone(), port) {
            Ok(handle) => {
                self.server = Some(handle);
                self.server_error = None;
                self.shared.log(format!("Server startet på port {port}"));
            }
            Err(e) => {
                self.server_error = Some(format!("Kunne ikke lytte på port {port}: {e} — er en anden server (eller en tidligere kørsel) i gang?"));
            }
        }
    }

    fn stop_server(&mut self) {
        if let Some(s) = self.server.take() {
            s.stop();
            self.shared.log("Server stoppet".to_string());
        }
    }

    fn refresh_addresses(&mut self) {
        if self.addresses_at.elapsed() < Duration::from_secs(5) {
            return;
        }
        self.addresses_at = std::time::Instant::now();
        let port = self.shared.lock().settings.port;
        self.addresses = if_addrs::get_if_addrs()
            .unwrap_or_default()
            .into_iter()
            .filter_map(|i| match i.ip() {
                std::net::IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_link_local() => Some((i.name.clone(), format!("http://{ip}:{port}"))),
                _ => None,
            })
            .collect();
    }

    fn drive_capture(&mut self, ctx: &egui::Context) {
        let Some(cap) = &mut self.capture else { return };
        ctx.request_repaint();
        cap.frames += 1;
        if !cap.requested && cap.frames >= 25 {
            cap.requested = true;
            let path = cap.path.clone();
            ctx.request_screenshot(move |image| {
                if let Err(e) = save_png(&path, &image) {
                    eprintln!("Skærmbillede fejlede: {e}");
                }
                std::process::exit(0);
            });
        }
    }
}

fn status_color(status: &str) -> Color32 {
    match status {
        "sent" | "delivered" => GREEN,
        "failed" | "expired" => RED,
        "sending" => ORANGE,
        _ => Color32::GRAY,
    }
}

fn status_label(status: &str) -> &str {
    match status {
        "queued" => "I kø",
        "sending" => "Hentet",
        "sent" => "Sendt",
        "delivered" => "Leveret",
        "failed" => "Fejlet",
        "expired" => "Udløbet",
        "cancelled" => "Trukket tilbage",
        other => other,
    }
}

fn ago(t: Option<DateTime<Utc>>) -> String {
    let Some(t) = t else { return "aldrig".into() };
    let s = (Utc::now() - t).num_seconds().max(0);
    match s {
        0..=59 => format!("for {s} sek. siden"),
        60..=3599 => format!("for {} min. siden", s / 60),
        _ => format!("for {} t. siden", s / 3600),
    }
}

fn dot(ui: &mut egui::Ui, color: Color32) {
    ui.label(RichText::new("●").color(color));
}

fn copy_row(ui: &mut egui::Ui, label: &str, value: &str) {
    ui.horizontal(|ui| {
        ui.label(label);
        ui.add(egui::Label::new(RichText::new(value).monospace()).selectable(true));
        if ui.small_button("Kopiér").clicked() {
            ui.ctx().copy_text(value.to_string());
        }
    });
}

impl App {
    fn left(&mut self, ui: &mut egui::Ui) {
        ui.heading("Server");
        ui.horizontal(|ui| {
            if self.server.is_some() {
                dot(ui, GREEN);
                let port = self.shared.lock().settings.port;
                ui.label(format!("Kører på port {port}"));
                if ui.button("Stop").clicked() {
                    self.stop_server();
                }
            } else {
                dot(ui, Color32::GRAY);
                ui.label("Stoppet");
                if ui.button("Start").clicked() {
                    self.start_server();
                }
            }
        });
        if let Some(e) = &self.server_error {
            ui.label(RichText::new(e).color(RED));
        }
        self.refresh_addresses();
        ui.label(RichText::new("Skriv en af disse adresser i appen på telefonen (samme Wi-Fi som pc'en):").weak());
        for (name, url) in self.addresses.clone() {
            copy_row(ui, &format!("{name}:"), &url);
        }
        if self.addresses.is_empty() {
            ui.label("Ingen netværksadresse fundet.");
        }
        ui.label(RichText::new("Første gang spørger Windows om firewall-adgang — vælg «Private netværk».").weak());
        ui.add_space(4.0);

        let (phone_key, sender_key) = {
            let inner = self.shared.lock();
            (inner.settings.phone_key.clone(), inner.settings.sender_key.clone())
        };
        copy_row(ui, "Telefon-nøgle (i appen):", &phone_key);
        copy_row(ui, "Afsender-nøgle (til curl/scripts):", &sender_key);
        ui.horizontal(|ui| {
            if ui.button("Ny telefon-nøgle").clicked() {
                self.new_keys(true);
            }
            if ui.button("Ny afsender-nøgle").clicked() {
                self.new_keys(false);
            }
        });

        ui.separator();
        ui.heading("Telefon");
        {
            let inner = self.shared.lock();
            ui.horizontal(|ui| {
                dot(ui, if inner.is_online() { GREEN } else { RED });
                ui.label(if inner.is_online() { "Online" } else { "Ikke forbundet" });
            });
            ui.label(format!(
                "Sidst set: {}  ·  batteri: {}  ·  app: {}",
                ago(inner.gateway_seen),
                inner.gateway_battery.map_or("?".into(), |b| format!("{b} %")),
                inner.gateway_version.clone().unwrap_or_else(|| "?".into()),
            ));
        }

        ui.separator();
        ui.heading("Send en SMS");
        ui.add(egui::TextEdit::singleline(&mut self.to).hint_text("Telefonnummer, fx 12 34 56 78").desired_width(f32::INFINITY));
        ui.add(egui::TextEdit::multiline(&mut self.text).hint_text("Tekst").desired_rows(4).desired_width(f32::INFINITY));
        ui.horizontal(|ui| {
            ui.label(format!("{}/{MAX_TEXT_LENGTH} tegn", self.text.chars().count()));
            ui.label("Type:");
            egui::ComboBox::from_id_salt("kind").selected_text(self.kind.clone()).show_ui(ui, |ui| {
                for k in KINDS {
                    ui.selectable_value(&mut self.kind, k.to_string(), k);
                }
            });
            ui.label("Udløber efter:");
            ui.add(egui::DragValue::new(&mut self.ttl_minutes).range(1..=10080).suffix(" min."));
        });
        if ui.button("Læg i kø").clicked() {
            let result = self.shared.queue(NewSms {
                to: self.to.clone(),
                text: self.text.clone(),
                kind: self.kind.clone(),
                reference: None,
                ttl_seconds: Some(self.ttl_minutes as i64 * 60),
                created_by: "testserverens vindue".into(),
            });
            self.send_message = Some(match result {
                Ok((sms, online)) => {
                    self.text.clear();
                    (true, format!("Lagt i kø til {}{}", sms.to, if online { "" } else { " — ingen telefon er online lige nu" }))
                }
                Err(e) => (false, e.message),
            });
        }
        if let Some((ok, msg)) = &self.send_message {
            ui.label(RichText::new(msg).color(if *ok { GREEN } else { RED }));
        }

        ui.separator();
        egui::CollapsingHeader::new("Indstillinger (som i den rigtige server)").show(ui, |ui| {
            egui::Grid::new("settings").num_columns(2).spacing([10.0, 6.0]).show(ui, |ui| {
                ui.label("Port");
                ui.add(egui::DragValue::new(&mut self.edit.port).range(1024..=65535));
                ui.end_row();
                ui.label("Tilladte landekoder");
                ui.add(egui::TextEdit::singleline(&mut self.edit.allowed_prefixes).desired_width(120.0));
                ui.end_row();
                ui.label("Højst pr. nummer pr. time");
                ui.add(egui::DragValue::new(&mut self.edit.max_per_number_hour).range(1..=1000));
                ui.end_row();
                ui.label("Højst i alt pr. time");
                ui.add(egui::DragValue::new(&mut self.edit.max_per_hour).range(1..=10000));
                ui.end_row();
            });
            if ui.button("Anvend").clicked() {
                self.apply_settings();
            }
            if let Some(m) = &self.settings_message {
                ui.label(m);
            }
        });
    }

    fn new_keys(&mut self, phone: bool) {
        let mut inner = self.shared.lock();
        if phone {
            inner.settings.phone_key = core::new_phone_key();
        } else {
            inner.settings.sender_key = core::new_sender_key();
        }
        save_settings(&inner.settings);
        self.edit.phone_key = inner.settings.phone_key.clone();
        self.edit.sender_key = inner.settings.sender_key.clone();
    }

    fn apply_settings(&mut self) {
        let port_changed = {
            let mut inner = self.shared.lock();
            let changed = inner.settings.port != self.edit.port;
            inner.settings.port = self.edit.port;
            inner.settings.allowed_prefixes = self.edit.allowed_prefixes.clone();
            inner.settings.max_per_number_hour = self.edit.max_per_number_hour;
            inner.settings.max_per_hour = self.edit.max_per_hour;
            save_settings(&inner.settings);
            changed
        };
        if port_changed && self.server.is_some() {
            self.stop_server();
            // Den gamle lytter slipper porten, når tråden er ude af accept-løkken.
            std::thread::sleep(Duration::from_millis(300));
            self.start_server();
        }
        self.addresses_at = std::time::Instant::now() - Duration::from_secs(60);
        self.settings_message = Some("Gemt.".into());
    }

    fn right(&mut self, ui: &mut egui::Ui) {
        ui.heading("Beskeder");
        let (messages, log): (Vec<core::Sms>, Vec<String>) = {
            let mut inner = self.shared.lock();
            inner.housekeeping();
            (inner.messages.iter().rev().cloned().collect(), inner.log.iter().cloned().collect())
        };
        let mut cancel: Option<String> = None;
        egui::ScrollArea::both().id_salt("messages").max_height(ui.available_height() - 230.0).auto_shrink([false, false]).show(ui, |ui| {
            if messages.is_empty() {
                ui.label(RichText::new("Ingen endnu. Læg en SMS i kø til venstre, eller send den med curl.").weak());
            }
            egui::Grid::new("messages").num_columns(5).spacing([14.0, 6.0]).striped(true).show(ui, |ui| {
                for m in &messages {
                    ui.label(m.created_at.with_timezone(&Local).format("%H:%M:%S").to_string());
                    ui.label(format!("{} · {}", m.to, m.kind));
                    ui.label(RichText::new(status_label(&m.status)).color(status_color(&m.status)).strong());
                    let detail = match (&m.error, m.kind.as_str()) {
                        (Some(e), _) => e.clone(),
                        (None, "free") => m.text.clone(),
                        _ => String::new(),
                    };
                    ui.scope(|ui| {
                        ui.set_min_width(110.0);
                        ui.add(egui::Label::new(detail.clone()).truncate()).on_hover_text(detail);
                    });
                    if m.status == "queued" {
                        if ui.small_button("Træk tilbage").clicked() {
                            cancel = Some(m.id.clone());
                        }
                    } else {
                        ui.label("");
                    }
                    ui.end_row();
                }
            });
        });
        if let Some(id) = cancel {
            self.shared.lock().cancel(&id);
            self.shared.changed();
        }

        ui.separator();
        egui::CollapsingHeader::new("Forbindelseslog").default_open(true).show(ui, |ui| {
            egui::ScrollArea::vertical().id_salt("log").max_height(170.0).stick_to_bottom(true).auto_shrink([false, true]).show(ui, |ui| {
                for line in &log {
                    ui.label(RichText::new(line).monospace().size(12.0));
                }
            });
        });
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        ctx.request_repaint_after(Duration::from_millis(500));
        self.drive_capture(&ctx);
        egui::CentralPanel::default().show(ui, |ui| {
            ui.columns(2, |cols| {
                egui::ScrollArea::vertical().id_salt("left").auto_shrink([false, false]).show(&mut cols[0], |ui| self.left(ui));
                self.right(&mut cols[1]);
            });
        });
    }
}
