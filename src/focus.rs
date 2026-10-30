use tauri::{AppHandle, Manager, Runtime};

/// Window calls run on the main thread because clicks arrive on COM or D-Bus worker threads.
pub fn focus_app<R: Runtime>(app: &AppHandle<R>) {
    let handle = app.clone();
    if let Err(e) = app.run_on_main_thread(move || focus_window(&handle)) {
        log::warn!("Failed to schedule focusing the app window: {e}");
    }
}

fn focus_window<R: Runtime>(app: &AppHandle<R>) {
    let Some(window) = app
        .get_webview_window("main")
        .or_else(|| app.webview_windows().into_values().next())
    else {
        return;
    };
    if let Err(e) = window.unminimize() {
        log::warn!("Failed to unminimize the app window: {e}");
    }
    if let Err(e) = window.show() {
        log::warn!("Failed to show the app window: {e}");
    }
    if let Err(e) = window.set_focus() {
        log::warn!("Failed to focus the app window: {e}");
    }
}
