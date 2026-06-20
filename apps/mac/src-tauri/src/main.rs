#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{
  CustomMenuItem, GlobalShortcutManager, Manager, SystemTray, SystemTrayEvent, SystemTrayMenu,
};

fn main() {
  let tray_menu = SystemTrayMenu::new()
    .add_item(CustomMenuItem::new("quick_capture", "Quick Capture"))
    .add_item(CustomMenuItem::new("open_todo", "Open Todo"))
    .add_item(CustomMenuItem::new("open_calendar", "Open Calendar"))
    .add_item(CustomMenuItem::new("open_strategy", "Open Strategy"))
    .add_item(CustomMenuItem::new("sync_feishu", "Sync Feishu Now"))
    .add_item(CustomMenuItem::new("settings", "Settings"));

  tauri::Builder::default()
    .system_tray(SystemTray::new().with_menu(tray_menu))
    .setup(|app| {
      let handle = app.handle();
      app.global_shortcut_manager().register("Alt+Space", move || {
        if let Some(window) = handle.get_window("main") {
          let _ = window.show();
          let _ = window.set_focus();
          let _ = window.emit("open-quick-capture", ());
        }
      })?;
      Ok(())
    })
    .on_system_tray_event(|app, event| {
      if let SystemTrayEvent::MenuItemClick { id, .. } = event {
        if let Some(window) = app.get_window("main") {
          let _ = window.show();
          let _ = window.set_focus();
          let _ = window.emit("tray-action", id.as_str());
        }
      }
    })
    .run(tauri::generate_context!())
    .expect("error while running Resolve");
}
