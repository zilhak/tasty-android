//! The device key of the Tasty network (`tasty-net-identity`), exposed to Kotlin.
//!
//! This is a temporary bridge: when tasty-network ships its own UniFFI crate, these exports
//! move there. Only app records cross the boundary, never tasty-network types.

use std::path::Path;

use tasty_net_identity::{DeviceKey, IdentityError};

/// The device key that was loaded or created.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceKeyInfo {
    /// The device ID (the ed25519 public key) as 64 lowercase hex digits.
    pub device_id: String,
}

/// Why the device key could not be loaded or created. Branch on the variant; `reason` is the
/// message of the tasty-network error.
#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum DeviceKeyError {
    /// A file system call failed.
    Io { reason: String },
    /// The key file does not have the expected format. It is left as it is.
    Corrupt { reason: String },
    /// The key file or its directory is not private to this app.
    UnsafeFile { reason: String },
    /// A new key file could not be made private, so nothing was written.
    Protect { reason: String },
    /// The OS random source failed, so no key was made.
    Random { reason: String },
    /// A temporary file that can hold the secret could not be removed.
    Leftover { reason: String },
    /// Any other failure.
    Other { reason: String },
}

impl std::fmt::Display for DeviceKeyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io { reason }
            | Self::Corrupt { reason }
            | Self::UnsafeFile { reason }
            | Self::Protect { reason }
            | Self::Random { reason }
            | Self::Leftover { reason }
            | Self::Other { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for DeviceKeyError {}

impl From<IdentityError> for DeviceKeyError {
    fn from(error: IdentityError) -> Self {
        let reason = error.to_string();
        match error {
            IdentityError::Io { .. } => Self::Io { reason },
            IdentityError::Corrupt { .. } => Self::Corrupt { reason },
            IdentityError::UnsafeFile { .. } => Self::UnsafeFile { reason },
            IdentityError::Protect { .. } => Self::Protect { reason },
            IdentityError::Random(_) => Self::Random { reason },
            IdentityError::Leftover { .. } => Self::Leftover { reason },
            _ => Self::Other { reason },
        }
    }
}

/// Loads the device key from `key_dir`, or creates it there when there is none, and returns
/// its device ID. Every caller that passes the same directory gets the same key, also when
/// several threads create it at the same time. The directory is created when missing.
///
/// This does file I/O and must not be called on the UI thread.
#[uniffi::export]
pub fn load_or_create_device_key(key_dir: String) -> Result<DeviceKeyInfo, DeviceKeyError> {
    let key = DeviceKey::load_or_create(Path::new(&key_dir))?;
    Ok(DeviceKeyInfo {
        device_id: key.id().to_string(),
    })
}

#[cfg(test)]
mod tests {
    use std::fs::OpenOptions;
    use std::io::Write;
    use std::path::PathBuf;
    use std::sync::{Arc, Barrier};
    use std::thread;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    /// A fresh test directory, removed with everything in it on drop. Its `keys` folder does
    /// not exist yet, so the first call also creates the key directory.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default();
            let parent = std::env::temp_dir().join(format!(
                "tasty-mobile-core-{name}-{}-{nanos}",
                std::process::id()
            ));
            std::fs::create_dir(&parent).expect("create the test directory");
            Self(parent)
        }

        fn key_dir(&self) -> String {
            self.0.join("keys").to_string_lossy().into_owned()
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ignored = std::fs::remove_dir_all(&self.0);
        }
    }

    fn is_device_id(text: &str) -> bool {
        text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    }

    #[test]
    fn creates_then_reloads_the_same_device_id() {
        let dir = TempDir::new("reload");
        let first = load_or_create_device_key(dir.key_dir()).expect("create the key");
        let second = load_or_create_device_key(dir.key_dir()).expect("reload the key");
        assert!(is_device_id(&first.device_id), "{}", first.device_id);
        assert_eq!(first, second);
        assert!(
            Path::new(&dir.key_dir())
                .join(tasty_net_identity::DEVICE_KEY_FILE)
                .is_file()
        );
    }

    #[test]
    fn different_directories_get_different_keys() {
        let (a, b) = (TempDir::new("first"), TempDir::new("second"));
        let a = load_or_create_device_key(a.key_dir()).expect("create the first key");
        let b = load_or_create_device_key(b.key_dir()).expect("create the second key");
        assert_ne!(a, b);
    }

    #[test]
    fn threads_creating_at_once_get_the_same_device_id() {
        const THREADS: usize = 8;
        let dir = TempDir::new("race");
        let barrier = Arc::new(Barrier::new(THREADS));
        let handles: Vec<_> = (0..THREADS)
            .map(|_| {
                let (barrier, key_dir) = (Arc::clone(&barrier), dir.key_dir());
                thread::spawn(move || {
                    barrier.wait();
                    load_or_create_device_key(key_dir)
                })
            })
            .collect();
        let ids: Vec<_> = handles
            .into_iter()
            .map(|h| h.join().expect("thread").expect("load or create"))
            .collect();
        assert!(ids.iter().all(|id| *id == ids[0]), "{ids:?}");
    }

    #[test]
    fn a_corrupt_key_file_is_reported_and_kept() {
        let dir = TempDir::new("corrupt");
        load_or_create_device_key(dir.key_dir()).expect("create the key");
        let path = Path::new(&dir.key_dir()).join(tasty_net_identity::DEVICE_KEY_FILE);
        // Rewrite the existing file so it stays private and only its contents are wrong.
        let mut file = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&path)
            .expect("open the key file");
        file.write_all(b"not a key\n").expect("write");
        drop(file);
        let error = load_or_create_device_key(dir.key_dir()).expect_err("refuse the file");
        assert!(
            matches!(error, DeviceKeyError::Corrupt { .. }),
            "{error:?}: {error}"
        );
        assert_eq!(std::fs::read(&path).expect("read"), b"not a key\n");
    }
}
