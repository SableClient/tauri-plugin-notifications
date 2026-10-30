//! Shared listener management for desktop platforms.
//!
//! This is a replication of Tauri's plugin listener implementation which is
//! currently only available for mobile plugins. Once Tauri adds desktop support
//! for plugin listeners, this module can be removed.
//!
//! Provides channel-based event delivery for notification events such as
//! notification received, action performed, and notification clicked.

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

#[cfg(all(target_os = "windows", not(feature = "notify-rust")))]
use tauri::Manager;
use tauri::{AppHandle, Runtime};

type ChannelMap = HashMap<u32, tauri::ipc::Channel<serde_json::Value>>;
type ListenerMap = HashMap<String, ChannelMap>;

static LISTENERS: OnceLock<RwLock<ListenerMap>> = OnceLock::new();

/// Initialize the listeners registry. Call this during plugin init.
pub fn init() {
    let _ = LISTENERS.get_or_init(|| RwLock::new(HashMap::new()));
}

/// Trigger an event to all registered listeners for the given event name.
///
/// Channels whose send fails (the webview is gone) are pruned, so `false` means
/// nobody is listening and callers can buffer.
///
/// Called by platform-specific code when notification events occur.
// Owned `payload` is taken from the FFI bridge in `macos.rs`.
#[allow(dead_code, clippy::needless_pass_by_value)]
pub fn trigger(event: &str, payload: String) -> crate::Result<bool> {
    let listeners = LISTENERS
        .get()
        .ok_or_else(|| crate::error::rejected(None, "Listeners not initialized"))?;

    let channels: Vec<(u32, tauri::ipc::Channel<serde_json::Value>)> = {
        let guard = listeners.read().map_err(|e| {
            crate::error::rejected(None, format!("Failed to acquire read lock: {e}"))
        })?;
        guard
            .get(event)
            .map(|c| c.iter().map(|(id, ch)| (*id, ch.clone())).collect())
            .unwrap_or_default()
    };
    if channels.is_empty() {
        return Ok(false);
    }

    let value: serde_json::Value = serde_json::from_str(&payload)
        .map_err(|e| crate::error::rejected(None, format!("Failed to parse payload JSON: {e}")))?;
    let mut delivered = false;
    let mut dead = Vec::new();
    for (id, channel) in &channels {
        match channel.send(value.clone()) {
            Ok(()) => delivered = true,
            Err(e) => {
                log::debug!("Dropping dead '{event}' listener {id}: {e}");
                dead.push(*id);
            }
        }
    }
    if !dead.is_empty()
        && let Ok(mut guard) = listeners.write()
        && let Some(channels) = guard.get_mut(event)
    {
        for id in dead {
            channels.remove(&id);
        }
    }
    Ok(delivered)
}

/// Register a channel to receive events for the given event name.
///
/// On Windows, subscribing synchronously drains the cold-start activation
/// payload buffered for that event by the COM activator.
// Tauri commands receive serde-deserialized owned values.
#[allow(clippy::needless_pass_by_value)]
#[tauri::command]
pub fn register_listener<R: Runtime>(
    app: AppHandle<R>,
    event: String,
    handler: tauri::ipc::Channel<serde_json::Value>,
) -> crate::Result<()> {
    let listeners = LISTENERS.get_or_init(|| RwLock::new(HashMap::new()));
    #[cfg(all(target_os = "windows", not(feature = "notify-rust")))]
    let drained_event = event.clone();
    {
        let mut guard = listeners.write().map_err(|e| {
            crate::error::rejected(None, format!("Failed to acquire write lock: {e}"))
        })?;
        guard
            .entry(event)
            .or_default()
            .insert(handler.id(), handler);
    }
    #[cfg(all(target_os = "windows", not(feature = "notify-rust")))]
    if let Some(notif) = app.try_state::<crate::Notifications<R>>() {
        notif.drain_pending(&drained_event);
    }
    #[cfg(not(all(target_os = "windows", not(feature = "notify-rust"))))]
    let _ = app;
    Ok(())
}

/// Remove a previously registered listener by event name and channel ID.
// Tauri commands receive serde-deserialized owned values.
#[allow(clippy::needless_pass_by_value)]
#[tauri::command]
pub fn remove_listener(event: String, channel_id: u32) -> crate::Result<()> {
    let listeners = LISTENERS
        .get()
        .ok_or_else(|| crate::error::rejected(None, "Listeners not initialized"))?;
    {
        let mut guard = listeners.write().map_err(|e| {
            crate::error::rejected(None, format!("Failed to acquire write lock: {e}"))
        })?;
        if let Some(channels) = guard.get_mut(&event) {
            channels.remove(&channel_id);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };

    fn subscribe(event: &str, fail: bool, sent: Arc<AtomicUsize>) -> u32 {
        let channel = tauri::ipc::Channel::new(move |_| {
            if fail {
                Err(tauri::Error::WebviewNotFound)
            } else {
                sent.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }
        });
        let id = channel.id();
        init();
        LISTENERS
            .get()
            .expect("listeners")
            .write()
            .expect("listeners")
            .entry(event.to_string())
            .or_default()
            .insert(id, channel);
        id
    }

    fn subscribed(event: &str) -> usize {
        LISTENERS
            .get()
            .expect("listeners")
            .read()
            .expect("listeners")
            .get(event)
            .map_or(0, HashMap::len)
    }

    #[test]
    fn trigger_reports_false_without_listeners() {
        init();
        assert!(!trigger("none", "{}".into()).expect("listeners"));
    }

    #[test]
    fn trigger_prunes_dead_channels_and_reports_no_delivery() {
        let sent = Arc::new(AtomicUsize::new(0));
        subscribe("dead", true, sent.clone());
        assert!(!trigger("dead", "{}".into()).expect("listeners"));
        assert_eq!(subscribed("dead"), 0);
        assert_eq!(sent.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn trigger_delivers_to_live_channels_and_keeps_them() {
        let sent = Arc::new(AtomicUsize::new(0));
        subscribe("mixed", true, sent.clone());
        subscribe("mixed", false, sent.clone());
        assert!(trigger("mixed", "{}".into()).expect("listeners"));
        assert_eq!(sent.load(Ordering::SeqCst), 1);
        assert_eq!(subscribed("mixed"), 1);
    }
}
