//! Toast activation payloads shared by the in-process and COM paths on Windows.
//! A cold-start COM activation only receives the `launch=` and `arguments=`
//! strings, so both paths decode them identically.

use std::collections::HashMap;
use std::sync::Mutex;

use serde_json::{Map, Value, json};

const ACTION_ID_KEY: &str = "actionId";

pub struct DecodedActivation {
    pub click: Option<Value>,
    pub action: Value,
}

/// Cold-start payloads queued per event until a listener subscribes.
/// The lock is held across delivery, push and drain so a payload cannot be
/// pushed after a drain found the queue empty. `deliver` must not retake it.
#[derive(Default)]
pub struct PendingActivations {
    queues: Mutex<HashMap<&'static str, Vec<Value>>>,
}

impl PendingActivations {
    /// Never both deliver and queue, or the event replays on the next subscription (hot reload).
    pub fn deliver_or_buffer(
        &self,
        event: &'static str,
        payload: Value,
        deliver: impl Fn(&Value) -> bool,
    ) {
        let mut queues = self
            .queues
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !deliver(&payload) {
            queues.entry(event).or_default().push(payload);
        }
    }

    #[allow(clippy::significant_drop_tightening)]
    pub fn drain(&self, event: &str, deliver: impl Fn(&Value) -> bool) {
        let mut queues = self
            .queues
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(queue) = queues.get_mut(event) else {
            return;
        };
        let mut payloads = std::mem::take(queue).into_iter();
        while let Some(payload) = payloads.next() {
            if !deliver(&payload) {
                queue.push(payload);
                queue.extend(payloads);
                return;
            }
        }
    }
}

pub fn launch_context(
    id: i32,
    title: Option<&str>,
    body: Option<&str>,
    action_type_id: Option<&str>,
    extra: &HashMap<String, Value>,
) -> Value {
    let mut context = Map::new();
    context.insert("id".into(), json!(id));
    context.insert("data".into(), json!(extra));
    for (key, value) in [
        ("title", title),
        ("body", body),
        ("actionTypeId", action_type_id),
    ] {
        if let Some(value) = value {
            context.insert(key.into(), json!(value));
        }
    }
    Value::Object(context)
}

/// Title and body stay out of button arguments because every button repeats
/// them and the toast XML must stay under 5 KB.
/// Wrapping the id keeps an id that is itself JSON from being read as a tap.
pub fn button_arguments(context: &Value, action_id: &str) -> String {
    let mut arguments = Map::new();
    for key in ["id", "data", "actionTypeId"] {
        if let Some(value) = context.get(key) {
            arguments.insert(key.into(), value.clone());
        }
    }
    arguments.insert(ACTION_ID_KEY.into(), json!(action_id));
    Value::Object(arguments).to_string()
}

fn notification(context: &Value) -> Value {
    let mut notification = Map::new();
    notification.insert(
        "id".into(),
        context.get("id").cloned().unwrap_or(Value::Null),
    );
    for key in ["title", "body", "actionTypeId"] {
        if let Some(value) = context.get(key) {
            notification.insert(key.into(), value.clone());
        }
    }
    notification.insert(
        "extra".into(),
        context.get("data").cloned().unwrap_or_else(|| json!({})),
    );
    Value::Object(notification)
}

fn click(context: &Value) -> Value {
    json!({
        "id": context.get("id").cloned().unwrap_or(Value::Null),
        "data": context.get("data").cloned().unwrap_or_else(|| json!({})),
    })
}

pub fn decode_activation(
    invoked_args: &str,
    inputs: &HashMap<String, String>,
) -> DecodedActivation {
    // Deterministic pick, the inputs have no meaningful order.
    let input_value = inputs
        .iter()
        .min_by(|a, b| a.0.cmp(b.0))
        .map_or(Value::Null, |(_, value)| json!(value));

    let context = serde_json::from_str::<Value>(invoked_args)
        .ok()
        .filter(Value::is_object);

    match context {
        Some(context) => {
            let action_id = context.get(ACTION_ID_KEY).and_then(Value::as_str);
            DecodedActivation {
                click: action_id.is_none().then(|| click(&context)),
                action: json!({
                    "actionId": action_id.unwrap_or("tap"),
                    "inputValue": input_value,
                    "notification": notification(&context),
                }),
            }
        }
        None if invoked_args.is_empty() => DecodedActivation {
            click: Some(click(&Value::Null)),
            action: json!({
                "actionId": "tap",
                "inputValue": input_value,
                "notification": Value::Null,
            }),
        },
        None => DecodedActivation {
            click: None,
            action: json!({
                "actionId": invoked_args,
                "inputValue": input_value,
                "notification": Value::Null,
            }),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn context() -> Value {
        let extra = HashMap::from([("room".to_string(), json!("!a"))]);
        launch_context(7, Some("Title"), Some("Body"), Some("reply"), &extra)
    }

    #[test]
    fn tap_decodes_to_click_and_matching_action() {
        let decoded = decode_activation(&context().to_string(), &HashMap::new());
        assert_eq!(
            decoded.click,
            Some(json!({ "id": 7, "data": { "room": "!a" } }))
        );
        assert_eq!(decoded.action["actionId"], "tap");
        assert_eq!(decoded.action["notification"]["title"], "Title");
        assert_eq!(decoded.action["notification"]["extra"]["room"], "!a");
    }

    #[test]
    fn button_routes_like_a_tap_without_repeating_the_text() {
        let ctx = context();
        let tap = decode_activation(&ctx.to_string(), &HashMap::new());
        let arguments = button_arguments(&ctx, "reply");
        assert!(!arguments.contains("Title") && !arguments.contains("Body"));
        let button = decode_activation(&arguments, &HashMap::new());
        assert!(button.click.is_none());
        assert_eq!(button.action["actionId"], "reply");
        for key in ["id", "extra", "actionTypeId"] {
            assert_eq!(
                button.action["notification"][key],
                tap.action["notification"][key]
            );
        }
    }

    #[test]
    fn legacy_button_arguments_with_the_full_context_still_decode() {
        let mut legacy = context().as_object().cloned().expect("test");
        legacy.insert(ACTION_ID_KEY.into(), json!("reply"));
        let decoded = decode_activation(&Value::Object(legacy).to_string(), &HashMap::new());
        assert!(decoded.click.is_none());
        assert_eq!(decoded.action["actionId"], "reply");
        assert_eq!(decoded.action["notification"]["title"], "Title");
    }

    #[test]
    fn button_whose_id_is_a_json_object_is_not_a_tap() {
        let id = r#"{"a":1}"#;
        let decoded = decode_activation(&button_arguments(&context(), id), &HashMap::new());
        assert!(decoded.click.is_none());
        assert_eq!(decoded.action["actionId"], id);
    }

    #[test]
    fn legacy_toasts_still_decode() {
        let empty = decode_activation("", &HashMap::new());
        assert_eq!(empty.action["actionId"], "tap");
        assert!(empty.click.is_some());
        let legacy_button = decode_activation("reply", &HashMap::new());
        assert_eq!(legacy_button.action["actionId"], "reply");
        assert!(legacy_button.click.is_none());
    }

    #[test]
    fn a_payload_buffered_while_a_listener_subscribes_is_not_stranded() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::{Arc, Mutex};
        use std::time::Duration;

        let pending = Arc::new(PendingActivations::default());
        let listening = Arc::new(AtomicBool::new(false));
        let received = Arc::new(Mutex::new(Vec::new()));
        let (checked_tx, checked_rx) = std::sync::mpsc::channel();

        let trigger = std::thread::spawn({
            let (pending, listening, received) =
                (pending.clone(), listening.clone(), received.clone());
            move || {
                pending.deliver_or_buffer("tap", json!(1), |payload| {
                    let live = listening.load(Ordering::SeqCst);
                    checked_tx.send(()).expect("test");
                    std::thread::sleep(Duration::from_millis(100));
                    if live {
                        received.lock().expect("test").push(payload.clone());
                    }
                    live
                });
            }
        });

        checked_rx.recv().expect("test");
        listening.store(true, Ordering::SeqCst);
        pending.drain("tap", |payload| {
            received.lock().expect("test").push(payload.clone());
            true
        });
        trigger.join().expect("test");

        assert_eq!(*received.lock().expect("test"), vec![json!(1)]);
    }

    #[test]
    fn undelivered_payloads_stay_queued_in_order() {
        let pending = PendingActivations::default();
        for n in 1..=3 {
            pending.deliver_or_buffer("tap", json!(n), |_| false);
        }
        let seen = Mutex::new(Vec::new());
        pending.drain("tap", |payload| {
            seen.lock().expect("test").push(payload.clone());
            payload != &json!(2)
        });
        let rest = Mutex::new(Vec::new());
        pending.drain("tap", |payload| {
            rest.lock().expect("test").push(payload.clone());
            true
        });
        assert_eq!(*seen.lock().expect("test"), vec![json!(1), json!(2)]);
        assert_eq!(*rest.lock().expect("test"), vec![json!(2), json!(3)]);
    }

    #[test]
    fn input_value_is_chosen_by_key() {
        let inputs = HashMap::from([
            ("b".to_string(), "second".to_string()),
            ("a".to_string(), "first".to_string()),
        ]);
        for _ in 0..8 {
            assert_eq!(decode_activation("", &inputs).action["inputValue"], "first");
        }
    }
}
