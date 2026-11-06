use tauri::{
    AppHandle, Runtime,
    plugin::{PermissionState, PluginApi},
};

use crate::NotificationsBuilder;

/// Tracks a single live `notify-rust` notification on Linux. Owning the
/// `NotificationHandle` keeps the underlying D-Bus `Connection` alive
/// (preventing the "popup disappears when the sending client disconnects"
/// behavior some Linux daemons exhibit) and lets us implement
/// `active`/`cancel` for the caller-supplied id.
///
/// macOS / Windows: `notify_rust::NotificationHandle` on those platforms
/// doesn't expose a useful `close()` (macOS daemon doesn't dismiss on
/// sender disconnect; Windows's handle is a thin wrapper without close
/// semantics), so we don't track there and the active-list / cancel
/// methods stay as the existing stubs.
#[cfg(target_os = "linux")]
struct ActiveEntry {
    caller_id: i32,
    handle: std::sync::Arc<notify_rust::NotificationHandle>,
    title: Option<String>,
    body: Option<String>,
}

// Returns `Result` like the other backends so the cfg-gated call sites in `lib.rs::init` stay uniform.
#[allow(clippy::unnecessary_wraps)]
pub fn init<R: Runtime>(
    app: &AppHandle<R>,
    api: &PluginApi<R, Option<crate::PluginConfig>>,
) -> crate::Result<Notifications<R>> {
    #[cfg(target_os = "linux")]
    let action_types = api
        .config()
        .as_ref()
        .map(|config| {
            config
                .action_types
                .iter()
                .map(|t| (t.id().to_owned(), t.clone()))
                .collect()
        })
        .unwrap_or_default();
    #[cfg(not(target_os = "linux"))]
    let _ = api;
    Ok(Notifications {
        app: app.clone(),
        #[cfg(target_os = "linux")]
        active: std::sync::Mutex::new(std::collections::HashMap::new()),
        #[cfg(target_os = "linux")]
        active_counter: std::sync::atomic::AtomicU64::new(0),
        #[cfg(target_os = "linux")]
        action_types: std::sync::Mutex::new(action_types),
        #[cfg(all(target_os = "linux", feature = "push-notifications"))]
        unifiedpush: tokio::sync::OnceCell::new(),
    })
}

/// Access to the notification APIs.
///
/// You can get an instance of this type via [`NotificationsExt`](crate::NotificationsExt)
pub struct Notifications<R: Runtime> {
    app: AppHandle<R>,
    /// Currently-displayed notifications, keyed by an internal monotonic
    /// counter. A notification shown with a caller id that is already tracked
    /// replaces the earlier one on the server and in this map. Holding the handles
    /// keeps the popups visible and lets `cancel`/`cancel_all`/
    /// `remove_active`/`active` work without leaking. Entries are removed by
    /// explicit cancel; expired/auto-dismissed notifications may linger
    /// because notify-rust doesn't expose a non-consuming "closed" callback.
    #[cfg(target_os = "linux")]
    active: std::sync::Mutex<std::collections::HashMap<u64, ActiveEntry>>,
    #[cfg(target_os = "linux")]
    active_counter: std::sync::atomic::AtomicU64,
    #[cfg(target_os = "linux")]
    action_types: std::sync::Mutex<std::collections::HashMap<String, crate::ActionType>>,
    #[cfg(all(target_os = "linux", feature = "push-notifications"))]
    unifiedpush: tokio::sync::OnceCell<std::sync::Arc<crate::unifiedpush::UnifiedPushState>>,
}

#[cfg(target_os = "linux")]
const DEFAULT_ACTION: &str = "default";

#[cfg(target_os = "linux")]
fn click_payload(
    caller_id: i32,
    extra: &std::collections::HashMap<String, serde_json::Value>,
) -> serde_json::Value {
    serde_json::json!({ "id": caller_id, "data": extra })
}

/// Same shape as the Windows and mobile `actionPerformed` payload.
#[cfg(target_os = "linux")]
fn action_payload(action_id: &str, notification: &serde_json::Value) -> serde_json::Value {
    serde_json::json!({ "actionId": action_id, "inputValue": null, "notification": notification })
}

/// What a click or an action button reports back for one displayed notification.
#[cfg(target_os = "linux")]
struct Reply {
    click: serde_json::Value,
    notification: serde_json::Value,
}

#[cfg(target_os = "linux")]
impl Reply {
    fn new(
        caller_id: i32,
        title: Option<&str>,
        body: Option<&str>,
        action_type_id: Option<&str>,
        extra: &std::collections::HashMap<String, serde_json::Value>,
    ) -> Self {
        Self {
            click: click_payload(caller_id, extra),
            notification: serde_json::json!({
                "id": caller_id,
                "title": title,
                "body": body,
                "actionTypeId": action_type_id,
                "extra": extra,
            }),
        }
    }
}

/// Linux-only toast options layered on top of `imp::build_notification`.
#[cfg(target_os = "linux")]
struct Extras {
    replaces_id: Option<u32>,
    silent: bool,
    sound: Option<String>,
    image: Option<String>,
    actions: Vec<(String, String)>,
}

#[cfg(target_os = "linux")]
fn apply_extras(notification: &mut notify_rust::Notification, extras: Extras) {
    use notify_rust::Hint;
    if let Some(id) = extras.replaces_id {
        notification.id(id);
    }
    if extras.silent {
        notification.hint(Hint::SuppressSound(true));
    } else if let Some(sound) = extras.sound {
        notification.hint(if std::path::Path::new(&sound).is_absolute() {
            Hint::SoundFile(sound)
        } else {
            Hint::SoundName(sound)
        });
    }
    if let Some(image) = extras.image {
        notification.hint(Hint::ImagePath(image));
    }
    if server_capabilities().iter().any(|c| c == "actions") {
        for (id, title) in &extras.actions {
            notification.action(id, title);
        }
    }
}

/// A failed query is not cached so a daemon that starts later is picked up.
#[cfg(target_os = "linux")]
fn server_capabilities() -> &'static [String] {
    static CAPABILITIES: std::sync::OnceLock<Vec<String>> = std::sync::OnceLock::new();
    if let Some(capabilities) = CAPABILITIES.get() {
        return capabilities;
    }
    match notify_rust::get_capabilities() {
        Ok(capabilities) => CAPABILITIES.get_or_init(|| capabilities),
        Err(e) => {
            log::warn!("Failed to query notification server capabilities: {e}");
            &[]
        }
    }
}

/// GNOME Shell only accepts the five named XML entities, so a numeric entity
/// like `&#39;` would be shown literally.
#[cfg(any(target_os = "linux", test))]
fn escape_markup(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            _ => escaped.push(c),
        }
    }
    escaped
}

#[cfg(target_os = "linux")]
fn watch_notification<R: Runtime>(
    app: AppHandle<R>,
    entry_id: u64,
    handle: std::sync::Arc<notify_rust::NotificationHandle>,
    reply: Reply,
) {
    use notify_rust::NotificationResponse;
    tauri::async_runtime::spawn(async move {
        let mut response = None;
        handle
            .wait_for_action_async(|r| response = Some(r.clone()))
            .await;

        // A replaced or cancelled entry is already gone, and its stale payload must not fire.
        let tracked = {
            use tauri::Manager;
            app.state::<Notifications<R>>().forget(entry_id)
        };
        let (action_id, clicked) = match response {
            Some(NotificationResponse::Default) => ("tap".to_owned(), true),
            Some(NotificationResponse::Action(id)) => (id, false),
            _ => return,
        };
        if !tracked {
            return;
        }
        let action = action_payload(&action_id, &reply.notification);
        if let Err(e) = crate::listeners::trigger("actionPerformed", action.to_string()) {
            log::warn!("Failed to deliver a notification action: {e}");
        }
        if clicked {
            crate::focus::focus_app(&app);
            if let Err(e) =
                crate::listeners::trigger("notificationClicked", reply.click.to_string())
            {
                log::warn!("Failed to deliver a notification click: {e}");
            }
        }
    });
}

#[cfg(target_os = "linux")]
fn active_lock_err(e: impl std::fmt::Display) -> crate::Error {
    crate::Error::Io(std::io::Error::other(format!(
        "active notifications mutex poisoned: {e}"
    )))
}

#[cfg(target_os = "linux")]
impl<R: Runtime> Notifications<R> {
    /// Finds every tracked notification whose caller id is in `caller_ids`,
    /// removes them from the active map, and dispatches `handle.close()` on
    /// the blocking pool so the command call returns quickly.
    fn close_by_caller_ids(&self, caller_ids: &[i32]) -> crate::Result<()> {
        let mut to_close: Vec<ActiveEntry> = Vec::new();
        {
            let mut active = self.active.lock().map_err(active_lock_err)?;
            // Move the map out, partition entries into "close" vs "keep" in
            // one pass, then put the kept ones back. Avoids the borrow-
            // checker dance of iter-then-remove (which would need a
            // throwaway `Vec<u64>` of keys) without holding the lock any
            // longer than necessary.
            let kept: std::collections::HashMap<u64, ActiveEntry> = std::mem::take(&mut *active)
                .into_iter()
                .filter_map(|(k, entry)| {
                    if caller_ids.contains(&entry.caller_id) {
                        to_close.push(entry);
                        None
                    } else {
                        Some((k, entry))
                    }
                })
                .collect();
            *active = kept;
        }
        for entry in to_close {
            tauri::async_runtime::spawn(async move { entry.handle.close_async().await });
        }
        Ok(())
    }

    fn extras(&self, data: &crate::models::NotificationData) -> Extras {
        let replaces_id = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .values()
            .find(|entry| entry.caller_id == data.id)
            .map(|entry| entry.handle.id());
        let actions: Vec<(String, String)> = data
            .action_type_id
            .as_deref()
            .and_then(|id| {
                let types = self
                    .action_types
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                types.get(id).map(|action_type| {
                    // Freedesktop notifications have no text field, so a reply button would send nothing.
                    action_type
                        .actions()
                        .iter()
                        .filter(|a| !a.input())
                        .map(|a| (a.id().to_owned(), a.title().to_owned()))
                        .collect()
                })
            })
            .unwrap_or_default();
        Extras {
            replaces_id,
            silent: data.silent,
            sound: data.sound.clone(),
            image: data
                .attachments
                .iter()
                .map(crate::Attachment::url)
                .find(|url| url.scheme() == "file")
                .map(|url| url.as_str().to_owned()),
            actions,
        }
    }

    /// Recovers a poisoned lock because losing the handle would dismiss the popup on some daemons.
    /// `replaced` is the server id this notification took over, whose entry is dropped.
    fn track(
        &self,
        caller_id: i32,
        handle: notify_rust::NotificationHandle,
        title: Option<String>,
        body: Option<String>,
        replaced: Option<u32>,
    ) -> (u64, std::sync::Arc<notify_rust::NotificationHandle>) {
        let handle = std::sync::Arc::new(handle);
        let entry_id = self
            .active_counter
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let entry = ActiveEntry {
            caller_id,
            handle: handle.clone(),
            title,
            body,
        };
        {
            let mut active = self.active.lock().unwrap_or_else(|poisoned| {
                log::warn!("active notifications mutex was poisoned; recovering");
                poisoned.into_inner()
            });
            if let Some(id) = replaced {
                active.retain(|_, entry| entry.handle.id() != id);
            }
            active.insert(entry_id, entry);
        }
        (entry_id, handle)
    }

    fn forget(&self, entry_id: u64) -> bool {
        self.active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&entry_id)
            .is_some()
    }
}

#[cfg(all(target_os = "linux", feature = "push-notifications"))]
impl<R: Runtime> Notifications<R> {
    async fn unifiedpush_state(
        &self,
    ) -> crate::Result<&std::sync::Arc<crate::unifiedpush::UnifiedPushState>> {
        self.unifiedpush
            .get_or_try_init(|| {
                let displayer = Self::build_push_displayer(self.app.clone());
                crate::unifiedpush::UnifiedPushState::new(&self.app, Some(displayer))
            })
            .await
    }

    /// Builds the `PushDisplayer` callback handed to `UnifiedPushState`. The
    /// callback runs `notify_rust::Notification::show()` on a blocking thread
    /// and routes the resulting handle into the same `active` map that local
    /// notifications use, so push toasts:
    ///   * Stay visible (handle is held → D-Bus connection stays alive →
    ///     daemons don't dismiss-on-disconnect).
    ///   * Show up in [`Notifications::active`] alongside local notifications.
    ///   * Can be cancelled via the existing `cancel`/`cancel_all` methods
    ///     (caller id is `0` because `UnifiedPush` messages don't carry one).
    ///   * Focus the app and emit `notificationClicked` when clicked.
    fn build_push_displayer(app: AppHandle<R>) -> crate::unifiedpush::PushDisplayer {
        std::sync::Arc::new(move |title: Option<String>, body: Option<String>| {
            let app = app.clone();
            let identifier = app.config().identifier.clone();
            let app_name = app_name(&app);
            let desktop_entry = desktop_entry_name(app.config().product_name.as_deref());
            tauri::async_runtime::spawn_blocking(move || {
                let notification = match imp::build_notification(
                    title.as_deref(),
                    body.as_deref(),
                    None,
                    &identifier,
                    &app_name,
                    desktop_entry.as_deref(),
                ) {
                    Ok(n) => n,
                    Err(e) => {
                        log::warn!("Failed to build push notification: {e}");
                        return;
                    }
                };
                match notification.show() {
                    Ok(handle) => {
                        use tauri::Manager;
                        let state = app.state::<Self>();
                        let reply = Reply::new(
                            0,
                            title.as_deref(),
                            body.as_deref(),
                            None,
                            &std::collections::HashMap::new(),
                        );
                        let (entry_id, handle) = state.track(0, handle, title, body, None);
                        watch_notification(app.clone(), entry_id, handle, reply);
                    }
                    Err(e) => log::warn!("Failed to show push notification toast: {e}"),
                }
            });
        })
    }
}

// `async` and `Result` mirror the mobile/macOS plugin API so callers can `.await` and `?` uniformly.
impl<R: Runtime> crate::NotificationsBuilder<R> {
    pub async fn show(self) -> crate::Result<()> {
        let caller_id = self.data.id;
        #[cfg(target_os = "linux")]
        let extras = {
            use tauri::Manager;
            self.app.state::<Notifications<R>>().extras(&self.data)
        };
        #[cfg(target_os = "linux")]
        let replaces_id = extras.replaces_id;
        #[cfg(target_os = "linux")]
        let action_type_id = self.data.action_type_id.clone();
        let title = self
            .data
            .title
            .or_else(|| self.app.config().product_name.clone());
        let body = self.data.body;
        let icon = self.data.icon;
        #[cfg(target_os = "linux")]
        let extra = self.data.extra;
        let identifier = self.app.config().identifier.clone();
        let app_name = app_name(&self.app);
        let desktop_entry = desktop_entry_name(self.app.config().product_name.as_deref());
        let app = self.app.clone();

        // `notify_rust` blocks on D-Bus via zbus's `block_on`, which panics inside an async task.
        // Building the notification queries the server too, so all of it runs on a blocking thread.
        let (shown_title, shown_body) = (title.clone(), body.clone());
        let join_result = tauri::async_runtime::spawn_blocking(move || {
            #[cfg_attr(not(target_os = "linux"), allow(unused_mut))]
            let mut notification = imp::build_notification(
                shown_title.as_deref(),
                shown_body.as_deref(),
                icon.as_deref(),
                &identifier,
                &app_name,
                desktop_entry.as_deref(),
            )?;
            #[cfg(target_os = "linux")]
            apply_extras(&mut notification, extras);
            notification.show().map_err(|e| {
                crate::Error::Io(std::io::Error::other(format!(
                    "Failed to show notification: {e}"
                )))
            })
        })
        .await
        .map_err(|e| {
            crate::Error::Io(std::io::Error::other(format!(
                "notification spawn_blocking join error: {e}"
            )))
        })?;

        let handle = join_result?;

        #[cfg(target_os = "linux")]
        {
            use tauri::Manager;
            let state = app.state::<Notifications<R>>();
            let reply = Reply::new(
                caller_id,
                title.as_deref(),
                body.as_deref(),
                action_type_id.as_deref(),
                &extra,
            );
            let (entry_id, handle) = state.track(caller_id, handle, title, body, replaces_id);
            watch_notification(app.clone(), entry_id, handle, reply);
        }
        #[cfg(any(target_os = "macos", target_os = "windows"))]
        let _ = (handle, caller_id, title, body, app);

        Ok(())
    }
}

/// Flatpak names the `.desktop` file after the app id, the Tauri bundler after the
/// product name. Omitted when neither is known, since a wrong name breaks the
/// daemon's grouping and icon lookup.
fn desktop_entry_name(product_name: Option<&str>) -> Option<String> {
    desktop_entry_from(std::env::var("FLATPAK_ID").ok().as_deref(), product_name)
}

fn desktop_entry_from(flatpak_id: Option<&str>, product_name: Option<&str>) -> Option<String> {
    flatpak_id
        .or(product_name)
        .filter(|name| !name.is_empty())
        .map(str::to_owned)
}

fn app_name<R: Runtime>(app: &AppHandle<R>) -> String {
    app.config()
        .product_name
        .clone()
        .unwrap_or_else(|| app.config().identifier.clone())
}

// `async` mirrors the mobile/macOS plugin API so callers can `.await` uniformly.
#[allow(clippy::unused_async)]
impl<R: Runtime> Notifications<R> {
    pub fn builder(&self) -> NotificationsBuilder<R> {
        NotificationsBuilder::new(self.app.clone())
    }

    pub async fn request_permission(&self) -> crate::Result<PermissionState> {
        Ok(PermissionState::Granted)
    }

    /// On Linux with the `push-notifications` feature this registers with the
    /// selected (or first available) `UnifiedPush` distributor and returns the
    /// endpoint URL. Apps that need endpoint stability across launches should
    /// call [`set_token`](Self::set_token) before this with a persisted token.
    pub async fn register_for_push_notifications(
        &self,
        vapid: Option<String>,
    ) -> crate::Result<crate::models::PushNotificationResponse> {
        #[cfg(all(target_os = "linux", feature = "push-notifications"))]
        {
            if vapid.is_some() {
                log::warn!(
                    "Ignoring the VAPID key: the UnifiedPush D-Bus v1 distributor API has no VAPID support"
                );
            }
            let state = self.unifiedpush_state().await?;
            let endpoint = state.register().await?;
            Ok(crate::models::PushNotificationResponse::from_token(
                endpoint,
            ))
        }
        #[cfg(not(all(target_os = "linux", feature = "push-notifications")))]
        {
            let _ = vapid;
            Err(crate::Error::Io(std::io::Error::other(
                "Push notifications are not supported on desktop platforms",
            )))
        }
    }

    /// Sync signature preserved for source compatibility — callers that need
    /// the Linux `UnifiedPush` unregister path should use
    /// [`unregister_for_push_notifications_async`] instead.
    pub fn unregister_for_push_notifications(&self) -> crate::Result<()> {
        Err(crate::Error::Io(std::io::Error::other(
            "Push notifications are not supported on desktop platforms",
        )))
    }

    /// Async unregister used by the Tauri command bridge. On Linux with the
    /// `push-notifications` feature this calls
    /// `org.unifiedpush.Distributor1.Unregister` and clears the in-memory
    /// active registration.
    pub async fn unregister_for_push_notifications_async(&self) -> crate::Result<()> {
        #[cfg(all(target_os = "linux", feature = "push-notifications"))]
        {
            if let Some(state) = self.unifiedpush.get() {
                state.unregister().await?;
            }
            Ok(())
        }
        #[cfg(not(all(target_os = "linux", feature = "push-notifications")))]
        {
            Err(crate::Error::Io(std::io::Error::other(
                "Push notifications are not supported on desktop platforms",
            )))
        }
    }

    /// Lists currently running `UnifiedPush` distributors. Linux-only.
    #[cfg(all(target_os = "linux", feature = "push-notifications"))]
    pub async fn list_distributors(&self) -> crate::Result<Vec<String>> {
        let state = self.unifiedpush_state().await?;
        state.list_distributors().await
    }

    /// Pins the chosen `UnifiedPush` distributor for this process. Linux-only.
    #[cfg(all(target_os = "linux", feature = "push-notifications"))]
    pub async fn set_distributor(&self, name: String) -> crate::Result<()> {
        let state = self.unifiedpush_state().await?;
        state.set_distributor(name).await
    }

    /// Sets the `UnifiedPush` client token used on subsequent register calls.
    /// Pass the same token across launches to keep the endpoint URL stable.
    /// Linux-only.
    #[cfg(all(target_os = "linux", feature = "push-notifications"))]
    pub async fn set_token(&self, token: String) -> crate::Result<()> {
        let state = self.unifiedpush_state().await?;
        state.set_token(token).await
    }

    pub async fn permission_state(&self) -> crate::Result<PermissionState> {
        Ok(PermissionState::Granted)
    }

    pub async fn pending(&self) -> crate::Result<Vec<crate::PendingNotification>> {
        Err(crate::Error::Io(std::io::Error::other(
            "Pending notifications are not supported with notify-rust",
        )))
    }

    /// Linux: returns the currently-tracked notifications. The list is
    /// populated by [`NotificationsBuilder::show`] and pruned by
    /// `cancel`/`cancel_all`/`remove_active`. Entries dismissed by the user
    /// or expired by the OS may linger until the next explicit cancel call,
    /// since notify-rust doesn't expose a non-consuming "closed" callback.
    ///
    /// macOS / Windows: still unsupported.
    pub async fn active(&self) -> crate::Result<Vec<crate::ActiveNotification>> {
        #[cfg(target_os = "linux")]
        {
            let active = self.active.lock().map_err(active_lock_err)?;
            Ok(active
                .values()
                .map(|entry| {
                    crate::ActiveNotification::new(
                        entry.caller_id,
                        entry.title.clone(),
                        entry.body.clone(),
                    )
                })
                .collect())
        }
        #[cfg(not(target_os = "linux"))]
        {
            Err(crate::Error::Io(std::io::Error::other(
                "Active notifications are not supported with notify-rust",
            )))
        }
    }

    pub const fn set_click_listener_active(&self, _active: bool) -> crate::Result<()> {
        Ok(())
    }

    pub const fn set_action_listener_active(&self, _active: bool) -> crate::Result<()> {
        Ok(())
    }

    pub const fn set_push_message_listener_active(&self, _active: bool) -> crate::Result<()> {
        Ok(())
    }

    #[allow(clippy::unused_self)]
    pub const fn is_ignoring_battery_optimizations(&self) -> crate::Result<bool> {
        Ok(true)
    }

    pub const fn request_ignore_battery_optimizations(&self) -> crate::Result<()> {
        Ok(())
    }

    #[allow(clippy::unused_self)]
    pub const fn can_schedule_exact_alarms(&self) -> crate::Result<bool> {
        Ok(true)
    }

    pub const fn request_schedule_exact_alarms(&self) -> crate::Result<()> {
        Ok(())
    }

    pub fn push_transport(&self) -> crate::Result<crate::models::PushTransport> {
        Ok(crate::models::PushTransport::default())
    }

    /// Linux: closes every tracked notification whose caller-supplied id
    /// appears in `ids` and removes it from the active map.
    /// macOS / Windows: unsupported.
    // Existing public signature; switching to `&[i32]` would be breaking.
    #[allow(clippy::needless_pass_by_value)]
    pub fn remove_active(&self, ids: Vec<i32>) -> crate::Result<()> {
        #[cfg(target_os = "linux")]
        {
            self.close_by_caller_ids(&ids)
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = ids;
            Err(crate::Error::Io(std::io::Error::other(
                "Removing active notifications is not supported with notify-rust",
            )))
        }
    }

    /// Linux: closes every tracked notification, like [`cancel_all`](Self::cancel_all).
    /// macOS / Windows: unsupported.
    pub fn remove_all_active(&self) -> crate::Result<()> {
        #[cfg(target_os = "linux")]
        {
            self.cancel_all()
        }
        #[cfg(not(target_os = "linux"))]
        {
            Err(crate::Error::Io(std::io::Error::other(
                "Removing active notifications is not supported with notify-rust",
            )))
        }
    }

    /// Same semantics as [`remove_active`](Self::remove_active) on Linux;
    /// macOS / Windows: unsupported.
    // Existing public signature; switching to `&[i32]` would be breaking.
    #[allow(clippy::needless_pass_by_value)]
    pub fn cancel(&self, notifications: Vec<i32>) -> crate::Result<()> {
        #[cfg(target_os = "linux")]
        {
            self.close_by_caller_ids(&notifications)
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = notifications;
            Err(crate::Error::Io(std::io::Error::other(
                "Canceling notifications is not supported with notify-rust",
            )))
        }
    }

    /// Linux: closes every tracked notification.
    /// macOS / Windows: unsupported.
    pub fn cancel_all(&self) -> crate::Result<()> {
        #[cfg(target_os = "linux")]
        {
            let drained: Vec<ActiveEntry> = {
                let mut active = self.active.lock().map_err(active_lock_err)?;
                active.drain().map(|(_, v)| v).collect()
            };
            for entry in drained {
                tauri::async_runtime::spawn(async move { entry.handle.close_async().await });
            }
            Ok(())
        }
        #[cfg(not(target_os = "linux"))]
        {
            Err(crate::Error::Io(std::io::Error::other(
                "Canceling notifications is not supported with notify-rust",
            )))
        }
    }

    /// Linux: buttons are only shown when the server advertises the `actions` capability.
    /// macOS / Windows: unsupported.
    // Existing public signature; switching to `&[ActionType]` would be breaking.
    #[allow(clippy::needless_pass_by_value)]
    pub fn register_action_types(&self, types: Vec<crate::ActionType>) -> crate::Result<()> {
        #[cfg(target_os = "linux")]
        {
            self.action_types
                .lock()
                .map_err(active_lock_err)?
                .extend(types.into_iter().map(|t| (t.id().to_owned(), t)));
            Ok(())
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = types;
            Err(crate::Error::Io(std::io::Error::other(
                "Action types are not supported with notify-rust",
            )))
        }
    }

    pub fn create_channel(&self, _channel: crate::Channel) -> crate::Result<()> {
        Err(crate::Error::Io(std::io::Error::other(
            "Notification channels are not supported with notify-rust",
        )))
    }

    pub fn delete_channel(&self, _id: impl Into<String>) -> crate::Result<()> {
        Err(crate::Error::Io(std::io::Error::other(
            "Notification channels are not supported with notify-rust",
        )))
    }

    pub fn list_channels(&self) -> crate::Result<Vec<crate::Channel>> {
        Err(crate::Error::Io(std::io::Error::other(
            "Notification channels are not supported with notify-rust",
        )))
    }
}

mod imp {
    //! Helpers for assembling the cross-platform `notify_rust::Notification`
    //! before handing it off to a blocking thread for delivery.

    #[cfg(windows)]
    use std::path::MAIN_SEPARATOR as SEP;

    /// Builds a fully-configured `notify_rust::Notification` from the parts
    /// the cross-platform builder produced. Returns an error only on Windows
    /// if `current_exe` lookup fails; other platforms are infallible — the
    /// `Result` wrapper exists for the Windows branch only.
    #[allow(clippy::unnecessary_wraps)]
    pub fn build_notification(
        title: Option<&str>,
        body: Option<&str>,
        icon: Option<&str>,
        identifier: &str,
        app_name: &str,
        desktop_entry: Option<&str>,
    ) -> crate::Result<notify_rust::Notification> {
        let mut notification = notify_rust::Notification::new();
        #[cfg(target_os = "linux")]
        let capabilities = super::server_capabilities();
        #[cfg(target_os = "linux")]
        let has = |name: &str| capabilities.iter().any(|capability| capability == name);

        if let Some(body) = body {
            #[cfg(target_os = "linux")]
            if has("body-markup") {
                notification.body(&super::escape_markup(body));
            } else {
                notification.body(body);
            }
            #[cfg(not(target_os = "linux"))]
            notification.body(body);
        }
        if let Some(title) = title {
            notification.summary(title);
        }
        if let Some(icon) = icon {
            notification.icon(icon);
        } else {
            notification.auto_icon();
        }

        #[cfg(target_os = "linux")]
        {
            notification.appname(app_name);
            if let Some(desktop_entry) = desktop_entry {
                notification.hint(notify_rust::Hint::DesktopEntry(desktop_entry.to_owned()));
            }
            // Servers without the `actions` capability ignore or mishandle actions.
            if has("actions") {
                notification.action(super::DEFAULT_ACTION, super::DEFAULT_ACTION);
            }
        }

        #[cfg(windows)]
        {
            let exe = tauri::utils::platform::current_exe()?;
            let exe_dir = exe.parent().expect("failed to get exe directory");
            let curr_dir = exe_dir.display().to_string();
            // Only set System.AppUserModel.ID on the installed app, not when
            // running from `cargo`'s target dirs.
            if !(curr_dir.ends_with(format!("{SEP}target{SEP}debug").as_str())
                || curr_dir.ends_with(format!("{SEP}target{SEP}release").as_str()))
            {
                notification.app_id(identifier);
            }
        }
        #[cfg(target_os = "macos")]
        {
            let _ = notify_rust::set_application(if tauri::is_dev() {
                "com.apple.Terminal"
            } else {
                identifier
            });
        }
        #[cfg(not(target_os = "linux"))]
        let _ = (app_name, desktop_entry);
        #[cfg(not(any(windows, target_os = "macos")))]
        let _ = identifier;

        Ok(notification)
    }
}

#[cfg(test)]
mod tests {
    use super::escape_markup;

    #[test]
    fn markup_characters_in_the_body_are_escaped() {
        assert_eq!(
            escape_markup(r#"<b>"a" & 'b'</b>"#),
            "&lt;b&gt;&quot;a&quot; &amp; 'b'&lt;/b&gt;"
        );
        assert_eq!(escape_markup("plain"), "plain");
    }

    #[test]
    fn desktop_entry_follows_flatpak_id_then_product_name() {
        use super::desktop_entry_from;
        assert_eq!(
            desktop_entry_from(Some("org.example.App"), Some("App")).as_deref(),
            Some("org.example.App")
        );
        assert_eq!(
            desktop_entry_from(None, Some("My App")).as_deref(),
            Some("My App")
        );
        assert_eq!(desktop_entry_from(None, None), None);
        assert_eq!(desktop_entry_from(Some(""), None), None);
    }

    #[cfg(target_os = "linux")]
    use super::click_payload;
    #[cfg(target_os = "linux")]
    use std::collections::HashMap;

    #[cfg(target_os = "linux")]
    #[test]
    fn click_payload_carries_the_id_and_the_extra_data() {
        let extra = HashMap::from([
            ("room_id".to_owned(), serde_json::json!("!room:example.org")),
            ("user_id".to_owned(), serde_json::json!("@ada:example.org")),
        ]);

        assert_eq!(
            click_payload(7, &extra),
            serde_json::json!({
                "id": 7,
                "data": { "room_id": "!room:example.org", "user_id": "@ada:example.org" }
            })
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn click_payload_without_extra_data_has_an_empty_object() {
        assert_eq!(
            click_payload(-3, &HashMap::new()),
            serde_json::json!({ "id": -3, "data": {} })
        );
    }
}
