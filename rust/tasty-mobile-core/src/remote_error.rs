//! Sorts the error object of a remote Tasty reply (`{"code": ..., "message": ...}`).
//!
//! Only a code that is the JSON number `METHOD_NOT_FOUND` means the remote lacks the method.
//! Any other code, including the string `"-32601"`, is an ordinary remote error, so the app
//! does not take a failing method for a missing one.

use std::collections::HashMap;

use serde_json::value::RawValue;
use tasty_attach_protocol::METHOD_NOT_FOUND;

/// What a remote error object means to the app.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RemoteErrorKind {
    /// The remote does not have the method.
    MethodNotFound,
    /// Any other remote error. `code` is the JSON text of the code as sent (a string code
    /// keeps its quotes, a null code is `null`) and None only when the key is missing.
    /// `message` is None when the key is missing or null.
    Other {
        code: Option<String>,
        message: Option<String>,
    },
    /// Not a JSON object, or a code other than `METHOD_NOT_FOUND` with a `message` that is
    /// not a string.
    Malformed,
}

/// Sorts a remote error object given as JSON text.
#[uniffi::export]
pub fn classify_remote_error(error_json: String) -> RemoteErrorKind {
    let Ok(mut fields) = serde_json::from_str::<HashMap<String, Box<RawValue>>>(&error_json) else {
        return RemoteErrorKind::Malformed;
    };
    // The code decides first: a missing method stays a missing method whatever the message.
    let code = fields.remove("code").map(|raw| raw.get().to_owned());
    let method_not_found = code.as_deref().is_some_and(|text| {
        matches!(
            serde_json::from_str::<serde_json::Value>(text),
            Ok(serde_json::Value::Number(n)) if n.as_i64() == Some(i64::from(METHOD_NOT_FOUND))
        )
    });
    if method_not_found {
        return RemoteErrorKind::MethodNotFound;
    }
    let message = match fields.remove("message") {
        None => None,
        Some(raw) => match serde_json::from_str::<Option<String>>(raw.get()) {
            Ok(message) => message,
            Err(_) => return RemoteErrorKind::Malformed,
        },
    };
    RemoteErrorKind::Other { code, message }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classify(json: &str) -> RemoteErrorKind {
        classify_remote_error(json.to_owned())
    }

    fn other(code: Option<&str>, message: Option<&str>) -> RemoteErrorKind {
        RemoteErrorKind::Other {
            code: code.map(str::to_owned),
            message: message.map(str::to_owned),
        }
    }

    #[test]
    fn only_the_number_means_method_not_found() {
        assert_eq!(
            classify(r#"{"code": -32601, "message": "Method not found"}"#),
            RemoteErrorKind::MethodNotFound
        );
        assert_eq!(
            classify(r#"{"code":-32601}"#),
            RemoteErrorKind::MethodNotFound
        );
        // The code decides before the shape of the message.
        assert_eq!(
            classify(r#"{"code": -32601, "message": 5}"#),
            RemoteErrorKind::MethodNotFound
        );
        assert_eq!(
            classify(r#"{"code": "-32601", "message": "Method not found"}"#),
            other(Some(r#""-32601""#), Some("Method not found"))
        );
        // Not an integer, so not the code the remote sends.
        assert_eq!(
            classify(r#"{"code": -32601.0}"#),
            other(Some("-32601.0"), None)
        );
    }

    #[test]
    fn other_codes_are_ordinary_errors() {
        assert_eq!(
            classify(r#"{"code": -32602, "message": "Invalid params"}"#),
            other(Some("-32602"), Some("Invalid params"))
        );
        assert_eq!(
            classify(r#"{"code": {"x": 1}, "message": "m"}"#),
            other(Some(r#"{"x": 1}"#), Some("m"))
        );
        assert_eq!(
            classify(r#"{"message": "no code"}"#),
            other(None, Some("no code"))
        );
        // A null code is kept as sent; only a missing key gives None.
        assert_eq!(
            classify(r#"{"code": null, "message": "m"}"#),
            other(Some("null"), Some("m"))
        );
        assert_eq!(classify(r#"{"message": "m"}"#), other(None, Some("m")));
        assert_eq!(
            classify(r#"{"code": null, "message": null}"#),
            other(Some("null"), None)
        );
        assert_eq!(classify("{}"), other(None, None));
    }

    #[test]
    fn rejects_what_is_not_an_error_object() {
        assert_eq!(classify("not json"), RemoteErrorKind::Malformed);
        assert_eq!(classify(""), RemoteErrorKind::Malformed);
        assert_eq!(classify("-32601"), RemoteErrorKind::Malformed);
        assert_eq!(
            classify(r#"[{"code": -32601}]"#),
            RemoteErrorKind::Malformed
        );
        assert_eq!(
            classify(r#"{"code": -32602, "message": 5}"#),
            RemoteErrorKind::Malformed
        );
    }
}
